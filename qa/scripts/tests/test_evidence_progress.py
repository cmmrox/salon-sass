"""Real temporary Git repositories exercise fail-closed certification and progress.

No product runtime or feature QA is started by these tooling tests.
"""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import signal
import sys
import time
import tempfile
import unittest
from unittest.mock import patch

import yaml

from sdlc_lib import evidence, model, runner
from sdlc_lib.__main__ import main


class EvidenceTest(unittest.TestCase):
    feature_dir = "features/F07-example"
    stage_dir = feature_dir + "/stages/S01-example"

    def git(self, *args):
        result = subprocess.run(["git", *args], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def write(self, name, value):
        path = Path(name)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    def yaml(self, name, data):
        self.write(name, yaml.safe_dump(data))

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-m", "test: fixture source")
        return self.git("rev-parse", "HEAD")

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = os.getcwd()
        os.chdir(self.tmp.name)
        self.git("init", "-b", "feature")
        self.git("config", "user.email", "qa@example.invalid")
        self.git("config", "user.name", "Tooling QA")
        self.write(".gitignore", "qa/reports/\n.local/\n__pycache__/\n")
        self.write("apps/source.txt", "product v1\n")
        self.write("qa/checks/F07/pass.sh", "#!/bin/bash\nexit 0\n")
        self.write("docs/brd/requirements.yaml", "requirements:\n- id: REQ-01\n  text: Works\n")
        self.write("docs/engineering/rules.md", "Rules\n")
        self.write(self.feature_dir + "/README.md", "Feature criteria\n")
        self.write(self.feature_dir + "/cases.md", "## TC-F07-001\nJourney\n")
        self.write(self.stage_dir + "/spec.md", "## Read first\n- `docs/engineering/rules.md`\n\n## Acceptance criteria\n- AC-1: Works\n")
        self.write(self.stage_dir + "/cases.md", "## TC-F07-S01-001\nWorks\n")
        self.stage = {"id": "F07-S01", "sequence": 1, "status": "complete", "qa_status": "not-run",
            "cases": [{"id": "TC-F07-S01-001", "covers": ["AC-1"]}],
            "handoff": {"run": "local", "checked_at": "old"},
            "verification": {"commands": ["true", "true"], "automation": [
                {"id": "works", "tool": "script", "file": "qa/checks/F07/pass.sh",
                 "covers": ["TC-F07-S01-001"]}]}}
        self.feature = {"id": "F07", "status": "in-qa", "qa_status": "not-run",
            "requirements": ["REQ-01"], "cases": [{"id": "TC-F07-001"}],
            "verification": {"automation": [{"id": "journey", "tool": "script",
                "file": "qa/checks/F07/pass.sh", "covers": ["TC-F07-001"]}]}}
        self.yaml(self.stage_dir + "/stage.yaml", self.stage)
        self.yaml(self.feature_dir + "/feature.yaml", self.feature)
        self.revision = self.commit()
        self.context_path = ".local/context.json"
        self.context = {"environment": "local", "profiles": ["dev"], "fixture_identity": "seed/reset-1",
                        "targets": {"api": "http://localhost:9999"}, "config_files": [".local/config.json"],
                        "environment_variables": ["SDLC_TEST_FIXTURE_VALUE"]}
        self.write(".local/config.json", '{"synthetic": true}')
        self.write(self.context_path, json.dumps(self.context))
        self.env = patch.dict(os.environ, {"SDLC_TEST_FIXTURE_VALUE": "secret-test-value"})
        self.env.start()
        self.output = io.StringIO()
        self.quiet = contextlib.redirect_stdout(self.output)
        self.quiet.__enter__()

    def tearDown(self):
        self.quiet.__exit__(None, None, None)
        self.env.stop()
        os.chdir(self.previous)
        self.tmp.cleanup()

    def cert(self, kind="full-feature-qa"):
        return evidence.certification(model.load(), "F07", self.context_path, kind)

    def record(self, kind):
        bundle = model.load()
        entries = evidence.gates(bundle, "F07") if kind == "developer-commands" else runner.select(bundle, feature="F07")
        return runner.run(entries, "F07-commands" if kind == "developer-commands" else "F07",
            certification=self.cert(kind), certify_again=lambda: self.cert(kind), stop_first_failure=kind == "developer-commands")

    def valid_reports(self):
        code, self.commands_report = self.record("developer-commands")
        self.assertEqual(code, 0)
        code, self.qa_report = self.record("full-feature-qa")
        self.assertEqual(code, 0)
        self.feature.update(qa_status="pass", evidence={"last_run": self.qa_report, "last_result": "pass"},
                            integration={"qa_revision": self.revision})
        self.yaml(self.feature_dir + "/feature.yaml", self.feature)

    def close(self):
        return evidence.closure_check(model.load(), "F07", self.context_path, self.commands_report)

    def edit_summary(self, change, reseal=False):
        path = Path(self.qa_report) / "summary.json"
        data = json.loads(path.read_text())
        change(data)
        path.write_text(json.dumps(data))
        if reseal:
            evidence.seal(self.qa_report)

    def test_exact_passing_reports_allow_same_revision_and_metadata_commits(self):
        self.valid_reports()
        self.assertEqual(self.close()["feature"], "F07")
        self.feature["status"] = "complete"
        self.stage["handoff"]["checked_at"] = "new"
        self.yaml(self.stage_dir + "/stage.yaml", self.stage)
        self.yaml(self.feature_dir + "/feature.yaml", self.feature)
        self.write("docs/notes.md", "Closure bookkeeping\n")
        self.commit()
        self.assertEqual(self.close()["feature"], "F07")

    def test_changed_certified_files_refuse_even_after_committing(self):
        self.valid_reports()
        names = ["apps/source.txt", "qa/checks/F07/pass.sh", self.stage_dir + "/spec.md",
                 self.stage_dir + "/cases.md", self.feature_dir + "/cases.md", self.feature_dir + "/README.md",
                 "docs/brd/requirements.yaml", "docs/engineering/rules.md"]
        for name in names:
            with self.subTest(name=name):
                original = Path(name).read_text()
                self.write(name, original + "\n# Changed\n")
                self.commit()
                with self.assertRaisesRegex(evidence.Refusal, "changed"):
                    self.close()
                self.write(name, original)
                self.commit()
        self.close()

    def test_substantive_yaml_changes_refuse(self):
        self.valid_reports()
        for key, value in (("commands", ["printf changed"]), ("automation", [])):
            original = self.stage["verification"][key]
            self.stage["verification"][key] = value
            self.yaml(self.stage_dir + "/stage.yaml", self.stage)
            with self.assertRaisesRegex(evidence.Refusal, "uncommitted"):
                self.close()
            self.commit()
            with self.assertRaisesRegex(evidence.Refusal, "changed"):
                self.close()
            self.stage["verification"][key] = original
            self.yaml(self.stage_dir + "/stage.yaml", self.stage)
            self.commit()

    def test_context_config_and_env_changes_refuse_without_exposing_values(self):
        self.valid_reports()
        saved = json.loads((Path(self.qa_report) / "summary.json").read_text())
        serialized = json.dumps(saved)
        self.assertNotIn("secret-test-value", serialized)
        self.assertNotIn("http://localhost:9999", serialized)
        self.assertNotIn("seed/reset-1", serialized)
        original = Path(self.context_path).read_text()
        self.write(self.context_path, original + "\n")
        with self.assertRaisesRegex(evidence.Refusal, "context changed"):
            self.close()
        self.write(self.context_path, original)
        self.write(".local/config.json", '{"synthetic": false}')
        with self.assertRaisesRegex(evidence.Refusal, "context changed"):
            self.close()
        self.write(".local/config.json", '{"synthetic": true}')
        os.environ["SDLC_TEST_FIXTURE_VALUE"] = "changed-secret"
        with self.assertRaisesRegex(evidence.Refusal, "context changed"):
            self.close()

    def test_dirty_and_untracked_source_refuse_before_new_certification(self):
        self.write("apps/source.txt", "uncommitted")
        with self.assertRaisesRegex(evidence.Refusal, "uncommitted or untracked"):
            self.cert()
        self.git("restore", "apps/source.txt")
        self.write("apps/new.txt", "untracked")
        with self.assertRaisesRegex(evidence.Refusal, "uncommitted or untracked"):
            self.cert()

    def test_missing_context_config_env_or_certified_file_refuses(self):
        for name in (self.context_path, ".local/config.json", self.stage_dir + "/spec.md", "qa/checks/F07/pass.sh"):
            with self.subTest(name=name):
                original = Path(name).read_text()
                Path(name).unlink()
                with self.assertRaises(evidence.Refusal):
                    self.cert()
                self.write(name, original)
        del os.environ["SDLC_TEST_FIXTURE_VALUE"]
        self.cert()  # Optional overrides may be absent; their absence is fingerprinted.

    def test_context_requires_identity_fields_and_local_paths(self):
        for key in ("environment", "profiles", "fixture_identity", "targets"):
            changed = dict(self.context)
            del changed[key]
            self.write(self.context_path, json.dumps(changed))
            with self.assertRaisesRegex(evidence.Refusal, "context requires"):
                self.cert()
        with self.assertRaisesRegex(evidence.Refusal, "workspace-local"):
            evidence.context_fingerprint("/etc/hosts")

    def test_private_config_permissions_change_invalidates_aggregate_context(self):
        Path(".local/config.json").chmod(0o600)
        self.valid_reports()
        self.close()
        Path(".local/config.json").chmod(0o644)
        with self.assertRaisesRegex(evidence.Refusal, "context changed"):
            self.close()
        summary = json.loads((Path(self.qa_report) / "summary.json").read_text())
        self.assertEqual(set(summary["certification"]["context"]),
                         {"path", "config_files", "environment_variables", "sha256"})

    def test_context_rejects_truthy_invalid_identity_types(self):
        for key, value in (("environment", 1), ("environment", "  "), ("fixture_identity", ["seed"]),
                           ("profiles", "local"), ("profiles", [1]), ("targets", True),
                           ("targets", [" "]), ("targets", {"api": 9})):
            with self.subTest(key=key, value=value):
                changed = dict(self.context)
                changed[key] = value
                self.write(self.context_path, json.dumps(changed))
                with self.assertRaisesRegex(evidence.Refusal, "context requires"):
                    self.cert()
        self.context["targets"] = ["http://localhost:9999"]
        self.write(self.context_path, json.dumps(self.context))
        self.cert()

    def test_absent_environment_override_is_distinct_from_empty_or_set(self):
        del os.environ["SDLC_TEST_FIXTURE_VALUE"]
        self.valid_reports()
        self.close()
        os.environ["SDLC_TEST_FIXTURE_VALUE"] = ""
        with self.assertRaisesRegex(evidence.Refusal, "context changed"):
            self.close()
        os.environ["SDLC_TEST_FIXTURE_VALUE"] = "new-override"
        with self.assertRaisesRegex(evidence.Refusal, "context changed"):
            self.close()

    def test_yaml_manual_dates_are_fingerprinted_without_serialization_errors(self):
        self.stage["verification"]["manual"] = [{"case": "TC-F07-S01-001", "performed_on": "2026-10-14"}]
        self.yaml(self.stage_dir + "/stage.yaml", self.stage)
        path = Path(self.stage_dir + "/stage.yaml")
        path.write_text(path.read_text().replace("'2026-10-14'", "2026-10-14"))
        self.commit()
        self.cert()

    def test_modified_summary_is_refused_by_local_receipt(self):
        self.valid_reports()
        self.edit_summary(lambda s: s.update(label="tampered"))
        with self.assertRaisesRegex(evidence.Refusal, "summary was modified"):
            self.close()

    def test_missing_and_tampered_log_refuse(self):
        self.valid_reports()
        summary = json.loads((Path(self.qa_report) / "summary.json").read_text())
        log = Path(summary["entries"][0]["log"])
        log.write_text("changed")
        with self.assertRaisesRegex(evidence.Refusal, "log is missing or changed"):
            self.close()
        log.unlink()
        with self.assertRaises(evidence.Refusal):
            self.close()

    def test_partial_nonpass_or_wrong_scope_is_refused_even_with_valid_receipt(self):
        self.valid_reports()
        original = (Path(self.qa_report) / "summary.json").read_text()
        changes = [lambda s: s.update(state="running"), lambda s: s.update(result="fail"),
                   lambda s: s["entries"].pop(), lambda s: s.update(selected_count=99),
                   lambda s: s.update(selection_sha256="stale"),
                   lambda s: s["entries"][0].update(exit_code=1),
                   lambda s: s["entries"][0].update(covers=["other-case"])]
        for change in changes:
            with self.subTest(change=change):
                (Path(self.qa_report) / "summary.json").write_text(original)
                self.edit_summary(change, reseal=True)
                with self.assertRaises(evidence.Refusal):
                    self.close()

    def test_missing_or_legacy_reports_never_reuse(self):
        self.valid_reports()
        path = Path(self.qa_report) / "summary.json"
        path.unlink()
        with self.assertRaises(evidence.Refusal):
            self.close()
        path.write_text('{"result": "pass", "entries": []}')
        evidence.seal(self.qa_report)
        with self.assertRaises(evidence.Refusal):
            self.close()

    def test_unclosed_stage_missing_qa_revision_or_unfinished_qa_refuses(self):
        self.valid_reports()
        self.stage["status"] = "built"
        self.yaml(self.stage_dir + "/stage.yaml", self.stage)
        with self.assertRaisesRegex(evidence.Refusal, "must be closed"):
            self.close()
        self.stage.update(status="complete", qa_status="blocked")
        self.yaml(self.stage_dir + "/stage.yaml", self.stage)
        with self.assertRaisesRegex(evidence.Refusal, "independent QA"):
            self.close()
        self.stage["qa_status"] = "not-run"
        self.yaml(self.stage_dir + "/stage.yaml", self.stage)
        self.feature["integration"]["qa_revision"] = "a" * 40
        self.yaml(self.feature_dir + "/feature.yaml", self.feature)
        with self.assertRaisesRegex(evidence.Refusal, "qa_revision"):
            self.close()

    def test_run_rechecks_source_and_context_at_completion(self):
        entries = runner.select(model.load(), feature="F07")
        before = self.cert()
        def changed():
            self.write(".local/config.json", '{"changed": true}')
            return self.cert()
        with self.assertRaisesRegex(evidence.Refusal, "changed during"):
            runner.run(entries, "F07", certification=before, certify_again=changed)
        summaries = list(Path("qa/reports").glob("*/summary.json"))
        self.assertEqual(len(summaries), 1)
        self.assertEqual(json.loads(summaries[0].read_text())["result"], "interrupted")
        self.assertFalse(Path(".git/sdlc-certifications").exists())

    def test_dirty_source_during_run_cannot_receive_receipt(self):
        entries = runner.select(model.load(), feature="F07")
        before = self.cert()
        def changed():
            self.write("apps/source.txt", "changed during run")
            return self.cert()
        with self.assertRaisesRegex(evidence.Refusal, "uncommitted"):
            runner.run(entries, "F07", certification=before, certify_again=changed)
        summary_path = next(Path("qa/reports").glob("*/summary.json"))
        self.assertEqual(json.loads(summary_path.read_text())["state"], "interrupted")
        self.assertFalse(Path(".git/sdlc-certifications").exists())

    def test_reusable_full_feature_run_refuses_unclosed_stages(self):
        self.stage["status"] = "built"
        self.yaml(self.stage_dir + "/stage.yaml", self.stage)
        with self.assertRaisesRegex(evidence.Refusal, "already closed"):
            self.cert()
        # Developer gates remain available before stage closure.
        self.cert("developer-commands")

    def test_commands_deduplicate_and_stop_first_failure(self):
        self.stage["verification"]["commands"] = ["true", "false", "true", "printf should-not-run"]
        self.yaml(self.stage_dir + "/stage.yaml", self.stage)
        self.commit()
        self.assertEqual(len(evidence.gates(model.load(), "F07")), 3)
        code, report = self.record("developer-commands")
        self.assertEqual(code, 1)
        summary = json.loads((Path(report) / "summary.json").read_text())
        self.assertEqual([row["exit_code"] for row in summary["entries"]], [0, 1])
        self.assertEqual(summary["selected_count"], 3)
        self.assertEqual(summary["result"], "fail")

    def test_heartbeat_incremental_counts_and_unique_report_ids(self):
        self.write("qa/checks/F07/pass.sh", "#!/bin/bash\necho secret-child-output\nsleep 0.12\nexit 0\n")
        self.commit()
        entries = runner.select(model.load(), feature="F07")
        snapshots = []
        real_write = runner._write_summary
        def capture(report, summary):
            real_write(report, summary)
            snapshots.append(json.loads((Path(report) / "summary.json").read_text()))
        with patch.object(runner, "_write_summary", side_effect=capture):
            code, report = runner.run(entries, "F07", heartbeat_seconds=0.03)
        self.assertEqual(code, 0)
        self.assertTrue(any(s["current"]["index"] == 1 and s["completed"] == 0 for s in snapshots if s["current"]))
        self.assertTrue(any(s["completed"] == 1 for s in snapshots))
        self.assertGreater(len(snapshots), 5)
        spawned = [s for s in snapshots if (s.get("current") or {}).get("child_pid")]
        self.assertTrue(spawned)
        self.assertEqual(spawned[0]["runner"]["pid"], os.getpid())
        self.assertTrue(spawned[0]["runner"]["process_start"])
        self.assertEqual(spawned[0]["current"]["child_pgid"], spawned[0]["current"]["child_pid"])
        self.assertTrue(spawned[0]["current"]["log"].endswith(".log"))
        self.assertEqual(snapshots[-1]["passed"], 2)
        self.assertIn("PROGRESS F07 1/2 F07-S01 works completed=0 pass=0 fail=0 elapsed=", self.output.getvalue())
        self.assertNotIn("secret-child-output", self.output.getvalue())
        self.assertNotEqual(runner.run(entries, "F07")[1], report)

    def test_interruption_leaves_atomic_nonpassing_progress(self):
        entries = runner.select(model.load(), feature="F07")
        original_popen = subprocess.Popen
        def interrupt_child(argv, *args, **kwargs):
            if argv[0] == "bash":
                raise KeyboardInterrupt
            return original_popen(argv, *args, **kwargs)
        with patch.object(runner.subprocess, "Popen", side_effect=interrupt_child):
            with self.assertRaises(KeyboardInterrupt):
                runner.run(entries, "F07")
        summary_path = next(Path("qa/reports").glob("*/summary.json"))
        data = json.loads(summary_path.read_text())
        self.assertEqual(data["state"], "interrupted")
        self.assertEqual(data["completed"], 0)
        self.assertFalse(Path(str(summary_path) + ".tmp").exists())

    def test_empty_selection_and_dry_run_create_no_evidence(self):
        self.assertEqual(runner.run([], "F07"), (2, None))
        self.assertEqual(runner.run(runner.select(model.load(), feature="F07"), "F07", dry_run=True), (0, None))
        self.assertFalse(Path("qa/reports").exists())

    def test_nonancestor_report_revision_refuses(self):
        self.valid_reports()
        self.edit_summary(lambda s: s["certification"].update(source_revision="a" * 40), reseal=True)
        with self.assertRaisesRegex(evidence.Refusal, "not an ancestor"):
            self.close()

    def test_missing_or_nonpassing_developer_report_refuses(self):
        self.valid_reports()
        path = Path(self.commands_report) / "summary.json"
        original = path.read_text()
        path.unlink()
        with self.assertRaises(evidence.Refusal):
            self.close()
        saved = json.loads(original)
        saved["entries"][0].update(exit_code=1, outcome="fail")
        path.write_text(json.dumps(saved))
        evidence.seal(self.commands_report)
        with self.assertRaises(evidence.Refusal):
            self.close()

    def test_legacy_run_preserves_outcomes_and_is_not_reusable(self):
        self.write("qa/checks/F07/fail.sh", "exit 1\n")
        entries = [{"scope": "F07", "id": "failure", "tool": "script", "file": "qa/checks/F07/fail.sh"},
                   {"scope": "F07", "id": "success", "tool": "script", "file": "qa/checks/F07/pass.sh"},
                   {"scope": "F07", "id": "missing", "tool": "maestro", "file": "qa/mobile/missing.yaml"}]
        with patch.dict(os.environ, {"PATH": "/bin:/usr/bin"}):
            code, report = runner.run(entries, "legacy")
        self.assertEqual(code, 1)
        summary = json.loads((Path(report) / "summary.json").read_text())
        self.assertEqual([r["outcome"] for r in summary["entries"]], ["fail", "pass", "tool-missing"])
        self.assertNotIn("certification", summary)
        self.assertEqual(summary["source_revision"], self.revision)
        self.assertEqual(len(summary["entries"]), 3)

    def test_committed_and_dirty_executable_mode_changes_refuse(self):
        self.write("apps/run.sh", "#!/bin/bash\nexit 0\n")
        Path("apps/run.sh").chmod(0o755)
        self.revision = self.commit()
        self.valid_reports()
        self.git("config", "core.filemode", "false")
        Path("apps/run.sh").chmod(0o644)
        self.assertFalse(self.git("status", "--porcelain", "--", "apps/run.sh"))
        with self.assertRaisesRegex(evidence.Refusal, "executable mode.*uncommitted"):
            self.close()
        self.git("update-index", "--chmod=-x", "apps/run.sh")
        self.commit()
        with self.assertRaisesRegex(evidence.Refusal, "source.*changed"):
            self.close()

    @unittest.skipUnless(os.name == "posix", "POSIX symbolic links")
    def test_symlink_target_identity_is_certified(self):
        self.write("apps/target-one.txt", "same bytes")
        self.write("apps/target-two.txt", "same bytes")
        Path("apps/link.txt").symlink_to("target-one.txt")
        self.revision = self.commit()
        self.valid_reports()
        Path("apps/link.txt").unlink()
        Path("apps/link.txt").symlink_to("target-two.txt")
        self.commit()
        with self.assertRaisesRegex(evidence.Refusal, "source.*changed"):
            self.close()

    @unittest.skipUnless(os.name == "posix", "POSIX child process groups")
    def test_interruption_kills_term_ignoring_child_and_grandchild_with_bounded_wait(self):
        child_code = ("import os,signal,time; from pathlib import Path; "
                      "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
                      "Path('.local/grandchild.pid').write_text(str(os.getpid())); time.sleep(30)")
        parent_code = ("import os,signal,subprocess,sys,time\nfrom pathlib import Path\n"
                       "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
                       "Path('.local/parent.pid').write_text(str(os.getpid()))\n"
                       f"subprocess.Popen([sys.executable, '-c', {child_code!r}])\n"
                       "time.sleep(30)\n")
        self.write(".local/ignore-term.py", parent_code)
        self.write("qa/checks/F07/long.sh", f"{sys.executable} .local/ignore-term.py\n")
        entry = {"scope": "F07", "id": "long", "tool": "script", "file": "qa/checks/F07/long.sh"}
        original_popen = subprocess.Popen
        def interrupted_child(argv, *args, **kwargs):
            child = original_popen(argv, *args, **kwargs)
            if argv[0] == "bash":
                original_wait = child.wait
                first = True
                def wait(timeout=None):
                    nonlocal first
                    if first:
                        first = False
                        deadline = time.monotonic() + 2
                        while not Path(".local/grandchild.pid").exists():
                            if time.monotonic() > deadline:
                                raise AssertionError("synthetic descendant did not start")
                            time.sleep(0.01)
                        raise KeyboardInterrupt
                    return original_wait(timeout=timeout)
                child.wait = wait
            return child
        started = time.monotonic()
        try:
            with patch.object(runner.subprocess, "Popen", side_effect=interrupted_child):
                with self.assertRaises(KeyboardInterrupt):
                    runner.run([entry], "F07")
            self.assertLess(time.monotonic() - started, 6)
            summary_path = next(Path("qa/reports").glob("*/summary.json"))
            summary = json.loads(summary_path.read_text())
            self.assertEqual(summary["state"], "interrupted")
            self.assertTrue(summary["cancellation"]["kill_sent"])
            self.assertTrue(summary["cancellation"]["reaped"])
            self.assertEqual(summary["current"]["child_pgid"], summary["current"]["child_pid"])
            self.assertFalse(Path(".git/sdlc-certifications").exists())
            for name in ("parent", "grandchild"):
                pid = int(Path(f".local/{name}.pid").read_text())
                found = subprocess.run(["ps", "-p", str(pid), "-o", "stat="], capture_output=True, text=True)
                self.assertTrue(not found.stdout.strip() or found.stdout.strip().startswith("Z"),
                                f"synthetic {name} process remained active")
        finally:
            # Clean only PIDs created and recorded by this synthetic fixture if a regression fails.
            for name in ("parent", "grandchild"):
                path = Path(f".local/{name}.pid")
                if path.exists():
                    try:
                        os.kill(int(path.read_text()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass

    @unittest.skipUnless(os.name == "posix", "POSIX TERM signal and child groups")
    def test_real_sigterm_cancels_only_the_spawned_group_and_records_interruption(self):
        self.write("qa/checks/F07/term.sh", "sleep 30 &\nwait\n")
        entry = {"scope": "F07", "id": "term", "tool": "script", "file": "qa/checks/F07/term.sh"}
        launch = f"from sdlc_lib import runner; runner.run([{entry!r}], 'F07', heartbeat_seconds=0.1)"
        env = {**os.environ, "PYTHONPATH": str(Path(runner.__file__).resolve().parent.parent)}
        process = subprocess.Popen([sys.executable, "-c", launch], env=env,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        summary_path = None
        pgid = None
        try:
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                paths = list(Path("qa/reports").glob("*/summary.json"))
                if paths:
                    summary_path = paths[0]
                    data = json.loads(summary_path.read_text())
                    pgid = (data.get("current") or {}).get("child_pgid")
                    if pgid:
                        break
                time.sleep(0.01)
            self.assertIsNotNone(pgid, "synthetic runner never persisted its child identity")
            self.assertEqual(data["runner"]["pid"], process.pid)
            started = time.monotonic()
            process.send_signal(signal.SIGTERM)
            self.assertNotEqual(process.wait(timeout=6), 0)
            self.assertLess(time.monotonic() - started, 6)
            data = json.loads(summary_path.read_text())
            self.assertEqual(data["state"], "interrupted")
            self.assertEqual(data["interrupted_signal"], signal.SIGTERM)
            self.assertTrue(data["cancellation"]["reaped"])
            found = subprocess.run(["ps", "-axo", "pgid=,stat="], capture_output=True, text=True)
            active = [line for line in found.stdout.splitlines() if line.split() and
                      line.split()[0] == str(pgid) and not line.split()[1].startswith("Z")]
            self.assertEqual(active, [], "synthetic descendant group remained active")
            self.assertFalse(Path(".git/sdlc-certifications").exists())
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=3)
            if pgid is not None:
                try:
                    os.killpg(pgid, signal.SIGKILL)
                except ProcessLookupError:
                    pass

    def test_term_handler_is_restored_after_normal_run(self):
        previous = signal.getsignal(signal.SIGTERM)
        runner.run(runner.select(model.load(), feature="F07"), "F07")
        self.assertEqual(signal.getsignal(signal.SIGTERM), previous)

    def test_cli_enforces_exact_context_scope_and_required_context(self):
        self.assertEqual(main(["run", "--stage", "F07-S01", "--context", self.context_path]), 2)
        self.assertEqual(main(["run", "--feature", "F07", "--context", self.context_path, "--dry-run"]), 0)
        self.assertEqual(main(["verify-commands", "--feature", "F07", "--context", self.context_path]), 0)
        self.assertEqual(main(["run", "--feature", "F07", "--context", self.context_path]), 0)


if __name__ == "__main__":
    unittest.main()
