"""The runner's narrowing options, its per-entry time limit and the suite budgets.

Synthetic shell entries in a temporary directory; no product runtime is started.
"""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

import yaml

from sdlc_lib import __main__ as cli, model, runner, schema as S, validate
from tests import fixtures as fx


class Workspace(unittest.TestCase):
    """A feature F07 with two stages whose entries share one id, plus a feature journey."""
    feature_dir = "features/F07-example"

    def write(self, name, value):
        path = Path(name)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = os.getcwd()
        os.chdir(self.tmp.name)
        subprocess.run(["git", "init", "-q", "-b", "feature"], check=True)
        self.write("qa/checks/F07/pass.sh", "exit 0\n")
        self.write("qa/checks/F07/fail.sh", "exit 1\n")
        self.write("docs/brd/requirements.yaml", "requirements:\n- id: REQ-01\n  text: Works\n")
        for sequence, (sid, extra) in enumerate((("S01", "slots"), ("S02", "holds")), 1):
            stage = {"id": f"F07-{sid}", "sequence": sequence, "status": "complete", "qa_status": "not-run",
                     "verification": {"automation": [
                         {"id": "api", "tool": "script", "file": "qa/checks/F07/pass.sh", "suites": ["smoke"]},
                         {"id": extra, "tool": "script", "file": "qa/checks/F07/fail.sh"}]}}
            self.write(f"{self.feature_dir}/stages/{sid}-x/stage.yaml", yaml.safe_dump(stage))
        feature = {"id": "F07", "status": "in-qa", "qa_status": "not-run",
                   "verification": {"automation": [{"id": "journey", "tool": "script",
                                                    "file": "qa/checks/F07/pass.sh"}]}}
        self.write(self.feature_dir + "/feature.yaml", yaml.safe_dump(feature))
        self.output = io.StringIO()
        self.quiet = contextlib.redirect_stdout(self.output)
        self.quiet.__enter__()

    def tearDown(self):
        self.quiet.__exit__(None, None, None)
        os.chdir(self.previous)
        self.tmp.cleanup()

    def entries(self, **scope):
        return runner.select(model.load(), **(scope or {"feature": "F07"}))

    def refs(self, entries):
        return [f"{e['scope']}:{e['id']}" for e in entries]


class EntryOptionTest(Workspace):
    def test_an_entry_is_named_by_id_or_by_scope_and_id_in_the_scope_order(self):
        narrowed = runner.narrow(self.entries(), ["journey", "F07-S02:holds", "slots"])
        self.assertEqual(self.refs(narrowed), ["F07-S01:slots", "F07-S02:holds", "F07:journey"])

    def test_an_id_two_stages_share_must_name_its_scope(self):
        with self.assertRaisesRegex(runner.SelectionError, "F07-S01:api or F07-S02:api"):
            runner.narrow(self.entries(), ["api"])
        self.assertEqual(self.refs(runner.narrow(self.entries(), ["F07-S02:api"])), ["F07-S02:api"])

    def test_an_entry_outside_the_scope_is_refused(self):
        with self.assertRaisesRegex(runner.SelectionError, "'journey' is not an automation entry of this scope"):
            runner.narrow(self.entries(stage="F07-S01"), ["journey"])

    def test_the_command_line_runs_only_the_named_entries(self):
        self.assertEqual(cli.main(["run", "--feature", "F07", "--entry", "slots", "--entry", "journey",
                                   "--dry-run"]), 0)
        out = self.output.getvalue()
        self.assertIn("F07: 2 automation entries", out)
        self.assertIn("F07-S01 slots:", out)
        self.assertNotIn("F07-S02 holds:", out)

    def test_a_release_run_cannot_be_narrowed(self):
        self.assertEqual(cli.main(["run", "--release", "v0.1.0", "--entry", "api"]), 2)
        self.assertIn("not a release's gates", self.output.getvalue())


class RerunFailedTest(Workspace):
    def earlier_run(self, outcomes):
        report = Path("qa/reports/earlier")
        report.mkdir(parents=True)
        entries = [{"scope": ref.split(":")[0], "id": ref.split(":")[1], "outcome": outcome}
                   for ref, outcome in outcomes.items()]
        (report / "summary.json").write_text(json.dumps({"label": "F07", "entries": entries}))
        return str(report)

    def test_only_the_entries_that_did_not_pass_run_again(self):
        report = self.earlier_run({"F07-S01:api": "pass", "F07-S01:slots": "fail", "F07-S02:api": "timeout",
                                   "F07-S02:holds": "tool-missing", "F07:journey": "pass"})
        self.assertEqual(self.refs(runner.narrow(self.entries(), rerun_report=report)),
                         ["F07-S01:slots", "F07-S02:api", "F07-S02:holds"])

    def test_a_run_with_nothing_failed_or_no_summary_is_refused(self):
        with self.assertRaisesRegex(runner.SelectionError, "records no failed entry"):
            runner.narrow(self.entries(), rerun_report=self.earlier_run({"F07:journey": "pass"}))
        with self.assertRaisesRegex(runner.SelectionError, "cannot read"):
            runner.narrow(self.entries(), rerun_report="qa/reports/missing")

    def test_a_failed_entry_the_scope_no_longer_has_is_refused(self):
        report = self.earlier_run({"F07-S03:gone": "fail"})
        with self.assertRaisesRegex(runner.SelectionError, "'F07-S03:gone' is not an automation entry"):
            runner.narrow(self.entries(), rerun_report=report)

    def test_the_rerun_is_recorded_as_partial(self):
        report = self.earlier_run({"F07:journey": "fail"})
        self.assertEqual(cli.main(["run", "--feature", "F07", "--rerun-failed", report]), 0)
        summaries = [p for p in Path("qa/reports").glob("*/summary.json") if p.parent.name != "earlier"]
        data = json.loads(summaries[0].read_text())
        self.assertTrue(data["partial"])
        self.assertEqual(data["selection"], {"selected_from": 5, "entry": [], "rerun_failed": report})
        self.assertEqual([e["id"] for e in data["entries"]], ["journey"])


class TimeLimitTest(Workspace):
    def test_the_default_limit_and_an_entry_of_its_own(self):
        self.assertEqual(runner.time_limit_seconds({}), S.ENTRY_TIME_LIMIT_MINUTES * 60)
        self.assertEqual(runner.time_limit_seconds({"timeout_minutes": 45}), 45 * 60)
        for bad in (0, -1, "10", True):
            self.assertEqual(runner.time_limit_seconds({"timeout_minutes": bad}), S.ENTRY_TIME_LIMIT_MINUTES * 60)

    @unittest.skipUnless(os.name == "posix", "POSIX child process groups")
    def test_an_entry_past_its_limit_is_stopped_as_timed_out_and_the_run_goes_on(self):
        self.write("qa/checks/F07/hang.sh", "sleep 30 &\necho $! > .hang.pid\nwait\n")
        entries = [{"scope": "F07", "id": "hang", "tool": "script", "file": "qa/checks/F07/hang.sh",
                    "timeout_minutes": 0.01},
                   {"scope": "F07", "id": "after", "tool": "script", "file": "qa/checks/F07/pass.sh"}]
        started = time.monotonic()
        code, report = runner.run(entries, "F07", heartbeat_seconds=0.05)
        self.assertLess(time.monotonic() - started, 8)
        self.assertEqual(code, 1)
        data = json.loads((Path(report) / "summary.json").read_text())
        self.assertEqual([e["outcome"] for e in data["entries"]], ["timeout", "pass"])
        self.assertEqual(data["entries"][0]["time_limit_seconds"], 0.6)
        self.assertTrue(data["entries"][0]["cancellation"]["term_sent"])
        self.assertEqual(data["result"], "fail")
        self.assertIn("time limit", Path(data["entries"][0]["log"]).read_text())
        self.assertIn("TIMEOUT", self.output.getvalue())
        grandchild = int(Path(".hang.pid").read_text())
        state = subprocess.run(["ps", "-p", str(grandchild), "-o", "stat="], capture_output=True, text=True)
        self.assertTrue(not state.stdout.strip() or state.stdout.strip().startswith("Z"))

    def test_validate_accepts_only_a_positive_limit(self):
        for limit, ok in ((10, True), (2.5, True), (0, False), ("10", False), (True, False)):
            stage = fx.stage()
            stage["verification"]["automation"][0]["timeout_minutes"] = limit
            found = [f for f in validate.validate(fx.bundle(stages=[stage])) if "timeout_minutes" in f]
            self.assertEqual(not found, ok, (limit, found))


class BudgetTest(Workspace):
    def test_the_budgets_are_the_test_plan_numbers(self):
        self.assertEqual(S.SUITE_BUDGET_MINUTES, {"smoke": 5, "regression": 20})
        self.assertIsNone(runner.budget_check("release", 10_000))
        self.assertIsNone(runner.budget_check("smoke", 10_000, partial=True))
        self.assertEqual(runner.budget_check("smoke", 301),
                         {"suite": "smoke", "minutes": 5, "elapsed_minutes": 5.0, "exceeded": True})
        self.assertFalse(runner.budget_check("regression", 1199)["exceeded"])

    def test_a_suite_over_its_budget_warns_and_records_it(self):
        with patch.dict(S.SUITE_BUDGET_MINUTES, {"smoke": 0}):
            code, report = runner.run(self.entries(suite="smoke"), "smoke", suite="smoke")
        self.assertEqual(code, 0)  # over budget is a warning, not a failure
        data = json.loads((Path(report) / "summary.json").read_text())
        self.assertTrue(data["budget"]["exceeded"])
        self.assertIn("WARNING: smoke took", self.output.getvalue())

    def test_a_suite_within_its_budget_records_it_without_a_warning(self):
        code, report = runner.run(self.entries(suite="smoke"), "smoke", suite="smoke")
        data = json.loads((Path(report) / "summary.json").read_text())
        self.assertEqual(data["budget"]["minutes"], 5)
        self.assertFalse(data["budget"]["exceeded"])
        self.assertNotIn("WARNING: smoke", self.output.getvalue())


class ToolVocabularyTest(unittest.TestCase):
    def test_the_runner_runs_exactly_the_tools_validate_accepts(self):
        for tool in S.TOOLS:
            self.assertTrue(runner.command({"id": "x", "tool": tool, "file": "qa/x"}), tool)
        for tool in ("command", "jest"):
            with self.assertRaises(runner.SelectionError):
                runner.command({"id": "x", "tool": tool, "file": "qa/x", "cmd": "true"})


if __name__ == "__main__":
    unittest.main()
