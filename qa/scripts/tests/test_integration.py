"""Merge gates and destructive cleanup: Git operations run against temporary local repos.
GitHub snapshots are synthetic. These tests never touch the owner's remote or worktrees.
"""
import copy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from sdlc_lib import flow, integration, validate
from tests import fixtures as fx


def merged_record():
    return dict(branch="charithm/feature/F07-online-booking", qa_revision="a" * 40,
                pull_request="https://github.com/example/salon/pull/7", merged_commit="b" * 40,
                merged_at="2026-10-13T00:00:00Z")


class StateTest(unittest.TestCase):
    def test_complete_is_waiting_for_submission_or_owner_merge(self):
        scope = fx.feature(status="complete", qa_status="pass")
        self.assertEqual(flow.feature_turn(scope, []), ("lead", "feature-submit F07"))
        scope["integration"]["pull_request"] = merged_record()["pull_request"]
        self.assertEqual(flow.feature_turn(scope, []), ("lead", "merge-sync F07 (after owner merges the PR)"))
        scope["integration"] = merged_record()
        self.assertEqual(flow.feature_turn(scope, []), flow.NOBODY)

    def test_portfolio_order_waits_even_without_an_explicit_dependency(self):
        previous = fx.feature(status="complete", qa_status="pass")
        following = fx.feature(id="F08", status="backlog", requirements=[])
        self.assertEqual(flow.feature_turn(following, [], [previous, following]),
                         ("waiting", "after GitHub merge of F07"))
        previous["integration"] = merged_record()
        self.assertEqual(flow.feature_turn(following, [], [previous, following]), ("lead", "feature-new F08"))

    def test_validator_rejects_started_feature_before_merge(self):
        following = fx.feature(id="F08", status="planned", requirements=[],
                               _dir="features/F08-payments", _path="features/F08-payments/feature.yaml")
        messages = validate.check_features(fx.bundle(features=[fx.feature(), following]))
        self.assertTrue(any("started before confirmed GitHub merge of F07" in m for m in messages))

    def test_merge_evidence_cannot_be_partial_or_on_unverified_work(self):
        bad = fx.feature(integration={"merged_commit": "b" * 40})
        messages = validate.check_integration(bad)
        self.assertTrue(any("merge evidence needs" in m for m in messages))
        self.assertTrue(any("QA-passed, closed" in m for m in messages))

    def test_validator_checks_qa_revision_url_branch_and_timestamp(self):
        scope = fx.feature(status="complete", qa_status="pass", integration=dict(
            branch="main", pull_request="https://example.com/pull/1", qa_revision="short",
            merged_commit="b" * 40, merged_at="yesterday"))
        messages = validate.check_integration(scope)
        for fragment in ("full Git commit SHA", "GitHub pull request URL", "integration branch", "timezone"):
            self.assertTrue(any(fragment in m for m in messages), messages)


class LiveMergeTest(unittest.TestCase):
    def setUp(self):
        self.scope = fx.feature(status="complete", qa_status="pass", integration=merged_record())
        self.snapshot = {"base": {"ref": "main", "repo": {"full_name": "example/salon"}},
                         "head": {"ref": self.scope["integration"]["branch"], "sha": "c" * 40},
                         "merged": True, "merged_at": "2026-10-13T00:00:00Z", "merge_commit_sha": "b" * 40}

    def call(self):
        result = subprocess.CompletedProcess([], 0, json.dumps(self.snapshot), "")
        with patch.object(integration, "repository", return_value="example/salon"), \
                patch.object(integration, "run", return_value=result), \
                patch.object(integration, "ancestor", return_value=True), \
                patch.object(integration, "qa_unchanged"), patch.object(integration, "ci_passed"):
            return integration.merged_pr(self.scope, "d" * 40)

    def test_confirmed_merge_records_github_commit_and_time(self):
        self.assertEqual(self.call()["merged_commit"], "b" * 40)

    def test_open_pr_does_not_unlock_next_feature(self):
        self.snapshot["merged"] = False
        with self.assertRaisesRegex(integration.Refusal, "waiting for the owner"):
            self.call()

    def test_wrong_base_or_head_is_refused(self):
        for field in ("base", "head"):
            original = copy.deepcopy(self.snapshot)
            self.snapshot[field]["ref"] = "unrelated"
            with self.assertRaisesRegex(integration.Refusal, "branches do not match"):
                self.call()
            self.snapshot = original

    def test_wrong_repository_is_refused(self):
        self.scope["integration"]["pull_request"] = "https://github.com/other/repo/pull/7"
        with self.assertRaisesRegex(integration.Refusal, "origin's GitHub"):
            self.call()

    def test_unfinished_feature_explains_the_block_before_pr_metadata(self):
        self.scope.update(status="in-progress", qa_status="not-run", integration={})
        with self.assertRaisesRegex(integration.Refusal, "F07: finish independent QA"):
            self.call()

    def test_unfetched_merge_and_squashed_history_are_refused(self):
        result = subprocess.CompletedProcess([], 0, json.dumps(self.snapshot), "")
        for answers, message in (([False], "not included"), ([True, False], "merge commit")):
            with patch.object(integration, "repository", return_value="example/salon"), \
                    patch.object(integration, "run", return_value=result), \
                    patch.object(integration, "ancestor", side_effect=answers):
                with self.assertRaisesRegex(integration.Refusal, message):
                    integration.merged_pr(self.scope, "d" * 40)

    def test_start_check_requires_live_merge_even_when_yaml_claims_one(self):
        next_feature = fx.feature(id="F08", status="backlog", depends_on=["F07"])
        with patch.object(integration, "remote_main", return_value="d" * 40), \
                patch.object(integration, "merged_pr", side_effect=integration.Refusal("PR is open")):
            with self.assertRaisesRegex(integration.Refusal, "PR is open"):
                integration.start_check(fx.bundle(features=[self.scope, next_feature]), "F08")

    def test_start_check_detects_recorded_merge_mismatch(self):
        next_feature = fx.feature(id="F08", status="backlog", depends_on=["F07"])
        record = {**merged_record(), "merged_commit": "e" * 40}
        with patch.object(integration, "remote_main", return_value="d" * 40), \
                patch.object(integration, "merged_pr", return_value=record):
            with self.assertRaisesRegex(integration.Refusal, "merge-sync F07"):
                integration.start_check(fx.bundle(features=[self.scope, next_feature]), "F08")

    def test_start_check_passes_only_after_live_merge_matches_record(self):
        next_feature = fx.feature(id="F08", status="backlog", depends_on=["F07"])
        with patch.object(integration, "remote_main", return_value="d" * 40), \
                patch.object(integration, "merged_pr", return_value=merged_record()), \
                patch.object(integration, "ancestor", return_value=True):
            self.assertEqual(integration.start_check(fx.bundle(features=[self.scope, next_feature]), "F08")["id"], "F08")

    def test_ci_must_pass_on_the_pr_head(self):
        head = "c" * 40
        check = dict(name="Verification commands", head_sha=head, status="completed", conclusion="success", id=1)
        for checks, passes in (([], False), ([check], True),
                               ([{**check, "conclusion": "failure"}], False),
                               ([{**check, "status": "in_progress"}], False),
                               ([{**check, "head_sha": "d" * 40}], False),
                               ([check, {**check, "id": 2, "conclusion": "failure"}], False)):
            result = subprocess.CompletedProcess([], 0, json.dumps({"check_runs": checks}), "")
            with patch.object(integration, "run", return_value=result):
                if passes:
                    integration.ci_passed("example/salon", head)
                else:
                    with self.assertRaisesRegex(integration.Refusal, "successful Verification commands"):
                        integration.ci_passed("example/salon", head)


class GitCleanupTest(unittest.TestCase):
    branch = "charithm/feature/F07-online-booking"

    def git(self, *args):
        result = subprocess.run(["git", *args], text=True, capture_output=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.previous = os.getcwd()
        root = Path(self.directory.name)
        self.remote = root / "origin.git"
        subprocess.run(["git", "init", "--bare", str(self.remote)], check=True, capture_output=True)
        self.checkout = root / "repo"
        self.checkout.mkdir()
        os.chdir(self.checkout)
        self.git("init", "-b", "main")
        self.git("config", "user.name", "Lifecycle test")
        self.git("config", "user.email", "lifecycle@example.invalid")
        self.git("remote", "add", "origin", str(self.remote))
        Path(".gitignore").write_text("qa/reports/\n.env.local\n")
        Path("apps").mkdir()
        Path("apps/source.txt").write_text("baseline\n")
        self.git("add", ".")
        self.git("commit", "-m", "chore: baseline")
        self.baseline = self.git("rev-parse", "HEAD")
        self.git("switch", "-c", self.branch)
        Path("apps/source.txt").write_text("feature\n")
        self.git("commit", "-am", "feat(F07): feature")
        self.tested = self.git("rev-parse", "HEAD")
        self.git("switch", "main")
        self.git("merge", "--no-ff", self.branch, "-m", "Merge feature")
        self.git("push", "origin", "main", self.branch)
        self.worktree = root / "feature worktree"
        self.git("worktree", "add", str(self.worktree), self.branch)
        self.repo_patch = patch.object(integration, "repository", return_value="example/salon")
        self.repo_patch.start()

    def tearDown(self):
        self.repo_patch.stop()
        os.chdir(self.previous)
        self.directory.cleanup()

    def test_preview_keeps_branch_remote_and_worktree(self):
        result = integration.cleanup([self.branch])
        self.assertEqual(result[0]["branch"], self.branch)
        self.assertTrue(self.worktree.exists())
        self.assertTrue(self.git("ls-remote", "--heads", "origin", f"refs/heads/{self.branch}"))

    def test_apply_archives_evidence_and_preserves_unrelated_branch(self):
        self.git("branch", "charithm/feature/F08-active")
        report = self.worktree / "qa/reports/run-1"
        report.mkdir(parents=True)
        (report / "summary.json").write_text('{"result":"pass"}')
        result = integration.cleanup([self.branch], apply=True)
        archived = Path(result[0]["archives"][0]) / "reports/run-1/summary.json"
        self.assertEqual(archived.read_text(), '{"result":"pass"}')
        self.assertFalse(self.worktree.exists())
        self.assertFalse(self.git("ls-remote", "--heads", "origin", f"refs/heads/{self.branch}"))
        self.git("rev-parse", "--verify", "refs/heads/charithm/feature/F08-active")

    def test_unmerged_target_refuses_the_entire_cleanup(self):
        self.git("switch", "-c", "charithm/feature/F08-active")
        Path("unique.txt").write_text("keep this")
        self.git("add", "unique.txt")
        self.git("commit", "-m", "feat(F08): unfinished")
        self.git("switch", "main")
        with self.assertRaisesRegex(integration.Refusal, "not merged"):
            integration.cleanup([self.branch, "charithm/feature/F08-active"], apply=True)
        self.assertTrue(self.worktree.exists())
        self.git("rev-parse", "--verify", f"refs/heads/{self.branch}")

    def test_dirty_worktree_is_not_removed(self):
        (self.worktree / "notes.txt").write_text("uncommitted work")
        with self.assertRaisesRegex(integration.Refusal, "uncommitted or untracked"):
            integration.cleanup([self.branch], apply=True)
        self.assertTrue(self.worktree.exists())

    def test_ignored_credentials_are_not_discarded(self):
        (self.worktree / ".env.local").write_text("TEST_ONLY=value")
        with self.assertRaisesRegex(integration.Refusal, "preserve ignored file"):
            integration.cleanup([self.branch], apply=True)
        self.assertTrue((self.worktree / ".env.local").exists())

    def test_main_and_current_branch_are_retained(self):
        with self.assertRaisesRegex(integration.Refusal, "main or the current"):
            integration.cleanup(["main"], apply=True)

    def test_product_change_invalidates_qa_but_metadata_change_does_not(self):
        scope = fx.feature(integration={"qa_revision": self.tested})
        integration.qa_unchanged(scope, "HEAD")
        Path("metadata.md").write_text("QA summary")
        self.git("add", "metadata.md")
        self.git("commit", "-m", "docs: record QA")
        integration.qa_unchanged(scope, "HEAD")
        Path("apps/source.txt").write_text("changed after QA\n")
        self.git("commit", "-am", "feat: changed product")
        with self.assertRaisesRegex(integration.Refusal, "changed since feature QA"):
            integration.qa_unchanged(scope, "HEAD")

    def test_archive_failure_removes_nothing(self):
        with patch.object(integration, "archive_evidence", side_effect=integration.Refusal("archive failed")):
            with self.assertRaisesRegex(integration.Refusal, "archive failed"):
                integration.cleanup([self.branch], apply=True)
        self.assertTrue(self.worktree.exists())
        self.git("rev-parse", "--verify", f"refs/heads/{self.branch}")

    def test_report_written_after_archive_keeps_the_worktree(self):
        real_archive = integration.archive_evidence

        def archive_then_write(record, archive):
            saved = real_archive(record, archive)
            report = self.worktree / "qa/reports/new-run"
            report.mkdir(parents=True)
            (report / "summary.json").write_text("new evidence")
            return saved

        with patch.object(integration, "archive_evidence", side_effect=archive_then_write):
            with self.assertRaisesRegex(integration.Refusal, "evidence changed after archival"):
                integration.cleanup([self.branch], apply=True)
        self.assertTrue(self.worktree.exists())


if __name__ == "__main__":
    unittest.main()
