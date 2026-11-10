"""What a QA pass needs on record (decision 069): a full run of the scope, which may have failed,
and later reruns that passed each entry it did not pass."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from sdlc_lib import model, runner, validate
from tests import fixtures as fx

FULL = "qa/reports/20261109-223501-F07-10961619c25e"
RERUN = "qa/reports/20261110-060856-F07-partial-d5c497afa957"
STAGE_RERUN = "qa/reports/20261110-070000-F07-S03-aaaaaaaaaaaa"
MERGED = {"branch": "charithm/feature/F07-online-booking", "qa_revision": "a" * 40,
          "pull_request": "https://github.com/example/salon/pull/26", "merged_commit": "b" * 40,
          "merged_at": "2026-11-10T01:21:23Z"}


def summary(label="F07", outcomes=None, **changes):
    outcomes = outcomes if outcomes is not None else {"F07-S01:api": "pass", "F07:journey": "pass"}
    entries = [{"scope": ref.split(":")[0], "id": ref.split(":")[1], "outcome": outcome}
               for ref, outcome in outcomes.items()]
    data = {"label": label, "state": "complete", "result": "pass" if all(o == "pass" for o in outcomes.values())
            else "fail", "partial": False, "selected": list(outcomes), "selected_count": len(outcomes),
            "entries": entries}
    data.update(changes)
    return data


def feature(evidence, reports=None, integration=None):
    scope = fx.feature(status="in-qa", qa_status="pass", evidence={"last_result": "pass", **evidence},
                       _reports=reports or {})
    if integration:
        scope["integration"] = integration
    return scope


def found(scope):
    return validate.check_full_run(scope, scope.get("id"))


class FormTest(unittest.TestCase):
    """What is checked everywhere, CI included, where qa/reports is absent."""

    def test_a_full_run_as_last_run_or_beside_a_narrowed_one(self):
        self.assertEqual(found(feature({"last_run": FULL})), [])
        self.assertEqual(found(feature({"last_run": RERUN, "full_pass_run": FULL})), [])
        self.assertEqual(found(feature({"last_run": STAGE_RERUN, "full_pass_run": FULL})), [])
        # Folders named before the run id existed keep their names.
        self.assertEqual(found(feature({"last_run": "qa/reports/20261012-130333-F01-S01"})), [])

    def test_a_narrowed_run_is_never_the_full_run(self):
        alone = found(feature({"last_run": RERUN}))
        self.assertTrue(any("narrowed run" in f and "full_pass_run" in f for f in alone), alone)
        named = found(feature({"last_run": RERUN, "full_pass_run": RERUN}))
        self.assertTrue(any("evidence.full_pass_run" in f and "narrowed run" in f for f in named), named)

    def test_reruns_are_report_folders_later_than_the_full_run(self):
        self.assertTrue(found(feature({"last_run": FULL, "reruns": RERUN})))
        self.assertTrue(found(feature({"last_run": FULL, "reruns": ["see the PR"]})))
        earlier = found(feature({"last_run": FULL, "reruns": ["qa/reports/20261101-000000-F07-partial-x"]}))
        self.assertTrue(any("not later than the full run" in f for f in earlier), earlier)
        self.assertTrue(found(feature({"last_run": FULL, "full_pass_run": "somewhere"})))


class SummaryTest(unittest.TestCase):
    """What is checked where the folders are in the checkout."""

    def test_the_full_run_is_of_the_scope_whole_and_finished_and_may_have_failed(self):
        self.assertEqual(found(feature({"last_run": FULL}, {FULL: summary()})), [])
        for wrong, fragment in ((summary(label="F07-S03"), "a run of 'F07-S03'"),
                                (summary(partial=True), "narrowed run (partial)"),
                                (summary(state="interrupted"), "did not finish"),
                                (summary(selected=["F07-S01:api", "F07:journey", "F07-S02:holds"]), "did not finish"),
                                (summary(selected_count=3, selected=None), "did not finish")):
            problems = found(feature({"last_run": FULL}, {FULL: wrong}))
            self.assertTrue(any(fragment in f for f in problems), (fragment, problems))
        # An older runner wrote no state, selection or partial flag, only at the end.
        old = {"label": "F07", "result": "pass", "entries": [{"scope": "F07", "id": "journey", "outcome": "pass"}]}
        self.assertEqual(found(feature({"last_run": FULL}, {FULL: old})), [])

    def test_each_failure_of_the_full_run_passed_in_a_listed_rerun(self):
        failed = summary(outcomes={"F07-S01:api": "fail", "F07-S02:holds": "timeout", "F07:journey": "pass"})
        none_listed = found(feature({"last_run": FULL}, {FULL: failed}))
        self.assertTrue(any("F07-S01:api, F07-S02:holds" in f and "evidence.reruns" in f for f in none_listed))
        one = summary(label="F07", outcomes={"F07-S01:api": "pass"}, partial=True)
        other = summary(label="F07", outcomes={"F07-S02:holds": "pass"}, partial=True)
        second = "qa/reports/20261110-080000-F07-partial-bbbbbbbbbbbb"
        covered = feature({"last_run": second, "full_pass_run": FULL, "reruns": [RERUN, second]},
                          {FULL: failed, RERUN: one, second: other})
        self.assertEqual(found(covered), [])
        still = feature({"last_run": RERUN, "full_pass_run": FULL, "reruns": [RERUN]}, {FULL: failed, RERUN: one})
        self.assertTrue(any("no listed rerun passed: F07-S02:holds" in f for f in found(still)))
        failed_again = summary(outcomes={"F07-S01:api": "fail", "F07-S02:holds": "pass"}, partial=True)
        self.assertTrue(found(feature({"last_run": RERUN, "full_pass_run": FULL, "reruns": [RERUN]},
                                      {FULL: failed, RERUN: failed_again})))

    def test_a_listed_rerun_not_in_this_checkout_leaves_only_the_form(self):
        failed = summary(outcomes={"F07-S01:api": "fail", "F07:journey": "pass"})
        absent = feature({"last_run": RERUN, "full_pass_run": FULL, "reruns": [RERUN]}, {FULL: failed, RERUN: None})
        self.assertEqual(found(absent), [])

    def test_a_merged_feature_is_exempt_from_the_reruns_only(self):
        failed = summary(outcomes={"F07-S01:api": "fail", "F07:journey": "pass"})
        merged = feature({"last_run": STAGE_RERUN, "full_pass_run": FULL}, {FULL: failed}, integration=MERGED)
        self.assertEqual(found(merged), [])
        partial = feature({"last_run": FULL}, {FULL: summary(partial=True)}, integration=MERGED)
        self.assertTrue(found(partial))
        narrowed = feature({"last_run": RERUN}, integration=MERGED)
        self.assertTrue(found(narrowed))

    def test_validate_applies_it_to_a_feature_pass(self):
        passed = fx.reviewed(fx.feature(status="in-qa", qa_status="pass", _has_review=True,
                                        evidence={"last_run": RERUN, "last_result": "pass"},
                                        cases=[{"id": "TC-F07-001", "title": "Book", "type": "functional",
                                                "priority": "critical", "covers": ["BKG-01"]}],
                                        _cases_md=["TC-F07-001"],
                                        verification={"automation": [{"id": "journey", "tool": "playwright",
                                                                      "file": "qa/web/F07/journey.spec.ts",
                                                                      "covers": ["TC-F07-001"]}]}))
        self.assertTrue(any("narrowed run" in f for f in validate.validate(fx.bundle(features=[passed]))))


class RecordingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = os.getcwd()
        os.chdir(self.tmp.name)
        subprocess.run(["git", "init", "-q", "-b", "feature"], check=True)
        Path(".gitignore").write_text("qa/reports/\n.local/\n")
        Path("pass.sh").write_text("exit 0\n")
        self.entry = [{"scope": "F07", "id": "journey", "tool": "script", "file": "pass.sh"}]

    def tearDown(self):
        os.chdir(self.previous)
        self.tmp.cleanup()

    def test_a_narrowed_run_is_marked_in_its_folder_name_and_its_summary(self):
        with contextlib.redirect_stdout(io.StringIO()):
            _, full = runner.run(self.entry, "F07")
            _, narrowed = runner.run(self.entry, "F07", selection={"selected_from": 3, "entry": ["journey"],
                                                                     "rerun_failed": None})
        self.assertNotIn("-partial-", full)
        self.assertRegex(os.path.basename(narrowed), r"^\d{8}-\d{6}-F07-partial-[0-9a-f]{12}$")
        self.assertTrue(json.loads((Path(narrowed) / "summary.json").read_text())["partial"])
        self.assertFalse(json.loads((Path(full) / "summary.json").read_text())["partial"])

    def test_the_evidence_folders_are_read_from_the_checkout_given(self):
        other = Path("other")
        (other / FULL).mkdir(parents=True)
        (other / FULL / "summary.json").write_text(json.dumps(summary()))
        stage_dir = "features/F07-x/stages/S01-x"
        Path(stage_dir).mkdir(parents=True)
        Path(stage_dir, "stage.yaml").write_text(
            f"id: F07-S01\nevidence:\n  last_run: {RERUN}\n  full_pass_run: {FULL}\n  reruns: [{RERUN}]\n")
        here = model._scope(stage_dir, "stage.yaml")
        self.assertEqual(here["_reports"], {RERUN: None, FULL: None})
        there = model._scope(stage_dir, "stage.yaml", str(other))
        self.assertEqual(there["_reports"][FULL]["label"], "F07")
        self.assertIsNone(there["_reports"][RERUN])


if __name__ == "__main__":
    unittest.main()
