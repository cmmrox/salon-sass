"""A recorded pass names a real report folder, and a run says whether its checkout was clean."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from sdlc_lib import runner, validate
from tests import fixtures as fx


class LastRunTest(unittest.TestCase):
    def found(self, last_run):
        stage = fx.stage(evidence={"last_run": last_run, "last_result": "pass"})
        return [f for f in validate.validate(fx.bundle(stages=[stage])) if "must name the run's report folder" in f]

    def test_the_report_folder_form_is_accepted(self):
        for value in ("qa/reports/20261110-060856-F07-S03-d5c497afa957", "qa/reports/20261012-130333-F01-S01/"):
            self.assertEqual(self.found(value), [], value)

    def test_anything_else_is_refused(self):
        for value in ("passed on 2026-11-10", "reports/20261110-F07", "/abs/qa/reports/x", "qa/reports/",
                      "qa/reports/a/b", "qa/reports/../x", "see PR #26"):
            self.assertTrue(self.found(value), value)

    def test_an_empty_last_run_is_left_to_the_qa_gate(self):
        self.assertEqual(self.found(None), [])
        self.assertTrue(any("without evidence" in f for f in validate.validate(
            fx.bundle(stages=[fx.stage(evidence={"last_run": None, "last_result": "pass"})]))))


class SourceStateTest(unittest.TestCase):
    def git(self, *args):
        subprocess.run(["git", *args], check=True, capture_output=True)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = os.getcwd()
        os.chdir(self.tmp.name)
        self.git("init", "-q", "-b", "feature")
        self.git("config", "user.email", "qa@example.invalid")
        self.git("config", "user.name", "Tooling QA")
        Path(".gitignore").write_text("qa/reports/\n")
        Path("check.sh").write_text("exit 0\n")
        self.git("add", ".")
        self.git("commit", "-q", "-m", "fixture")
        self.entry = [{"scope": "F07", "id": "check", "tool": "script", "file": "check.sh"}]

    def tearDown(self):
        os.chdir(self.previous)
        self.tmp.cleanup()

    def run_once(self):
        with contextlib.redirect_stdout(io.StringIO()) as out:
            _code, report = runner.run(self.entry, "F07")
        return json.loads((Path(report) / "summary.json").read_text()), out.getvalue()

    def test_a_clean_checkout_is_recorded_as_clean(self):
        summary, out = self.run_once()
        self.assertIs(summary["source_dirty"], False)
        self.assertEqual(summary["source_changes"], [])
        self.assertNotIn("uncommitted", out)

    def test_uncommitted_and_untracked_changes_are_recorded(self):
        Path("check.sh").write_text("exit 0 # repaired\n")
        Path("new.spec.ts").write_text("test('x', () => {});\n")
        summary, out = self.run_once()
        self.assertIs(summary["source_dirty"], True)
        self.assertEqual(sorted(summary["source_changes"]), [" M check.sh", "?? new.spec.ts"])
        self.assertIn("WARNING: the checkout has 2 uncommitted change(s)", out)


if __name__ == "__main__":
    unittest.main()
