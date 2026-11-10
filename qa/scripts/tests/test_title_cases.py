"""Cases declared by the tests that prove them (decision 068), alongside F01 to F03's yaml cases."""
import os
from pathlib import Path
import tempfile
import unittest

from sdlc_lib import generate, model, validate
from tests import fixtures as fx

TITLE = {"id": "TC-F07-S01-001", "covers": ["AC-1"], "file": "qa/api/F07/slots.spec.ts"}


def titled_stage(titles=(TITLE,), **changes):
    """A closed, QA-passed stage whose cases come only from its tests' titles."""
    data = fx.stage(cases=[], _cases_md=None, _title_cases=[dict(t) for t in titles])
    entry = data["verification"]["automation"][0]
    del entry["covers"]
    entry["_covers"] = sorted({t["id"] for t in titles})
    data.update(changes)
    return data


def findings(stage):
    return validate.validate(fx.bundle(stages=[stage]))


class TitleCaseTest(unittest.TestCase):
    def assertFinding(self, stage, fragment):
        found = findings(stage)
        self.assertTrue(any(fragment in f for f in found), f"expected {fragment!r}, got {found}")

    def test_titled_tests_cover_criteria_without_yaml_cases_or_cases_md(self):
        self.assertEqual(findings(titled_stage()), [])

    def test_a_criterion_no_title_names_is_uncovered(self):
        self.assertFinding(titled_stage([{**TITLE, "covers": []}]), "AC-1 is not covered")

    def test_a_title_may_cover_only_the_stage_criteria(self):
        self.assertFinding(titled_stage([{**TITLE, "covers": ["AC-1", "AC-9"]}]), "covers 'AC-9'")

    def test_one_case_is_proved_by_one_test_file(self):
        twice = [TITLE, {**TITLE, "file": "qa/web/F07/slots.spec.ts"}]
        self.assertFinding(titled_stage(twice), "one test proves one case")

    def test_automation_without_a_titled_test_is_not_coverage(self):
        self.assertFinding(titled_stage([]), "name each test 'TC-F07-S01-001 AC-1: ...'")

    def test_a_manual_entry_is_a_case_with_its_own_criteria(self):
        manual = {"case": "TC-F07-S01-002", "covers": ["AC-1"], "reason": "needs a phone",
                  "performed_on": "2026-10-30"}
        stage = titled_stage([{**TITLE, "covers": []}])
        stage["verification"]["manual"] = [manual]
        self.assertEqual(findings(stage), [])
        del manual["performed_on"]
        self.assertFinding(stage, "has no performed_on date")

    def test_coverage_counts_titled_cases(self):
        row = [line for line in generate.render_coverage(fx.bundle(stages=[titled_stage()])).splitlines()
               if line.startswith("| F07-S01 |")][0]
        self.assertEqual(row.split("|")[6].strip(), "1")  # Cases
        self.assertEqual(row.split("|")[7].strip(), "1")  # Automated


class TitleParsingTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.previous = os.getcwd()
        os.chdir(self.tmp.name)

    def tearDown(self):
        os.chdir(self.previous)
        self.tmp.cleanup()

    def write(self, name, text):
        Path(name).parent.mkdir(parents=True, exist_ok=True)
        Path(name).write_text(text)

    def test_ids_and_criteria_are_read_from_titles_and_script_headers(self):
        self.write("qa/web/F07/slots.spec.ts", "\n".join([
            "test('TC-F07-S01-001 AC-1, AC-2: a free slot is listed', async () => {});",
            'test.describe("holds", () => {',
            '  test("TC-F07-S01-002 AC-3: a hold hides the slot", async () => {});',
            '});',
            "test('a helper without an id', async () => {});",
            "test('TC-F07-S02-001 AC-1: another stage', async () => {});",
            "test('TC-F07-S01-003 AC-4: answers HTTP-500 as unavailable', async () => {});",
        ]))
        self.write("qa/load/F07/hold.js", "// TC-F07-S01-004 AC-5: holds stay unique under load\n")
        stage_dir = "features/F07-x/stages/S01-x"
        self.write(stage_dir + "/stage.yaml", "id: F07-S01\nverification:\n  automation:\n"
                   "  - {id: web, tool: playwright, file: qa/web/F07/slots.spec.ts}\n"
                   "  - {id: load, tool: k6, file: qa/load/F07/hold.js}\n")
        stage = model._scope(stage_dir, "stage.yaml")
        self.assertEqual([(c["id"], c["covers"]) for c in stage["_title_cases"]], [
            ("TC-F07-S01-001", ["AC-1", "AC-2"]), ("TC-F07-S01-002", ["AC-3"]),
            ("TC-F07-S01-003", ["AC-4"]), ("TC-F07-S01-004", ["AC-5"])])
        self.assertEqual(stage["verification"]["automation"][1]["_covers"], ["TC-F07-S01-004"])

    def test_only_a_playwright_test_that_runs_declares_a_case(self):
        self.write("qa/web/F07/slots.spec.ts", "\n".join([
            "// TC-F07-S01-001 AC-1: a comment names a case",
            "test('TC-F07-S01-002 AC-1: a real test', async ({ page }) => {",
            "  await page.goto('http://alpha.localhost/*not-a-comment*/'); // trailing test('TC-F07-S01-003 AC-1: x')",
            "  const pattern = /['\"]/;",
            "});",
            "test.skip('TC-F07-S01-004 AC-1: skipped', async () => {});",
            "test.fixme('TC-F07-S01-005 AC-1: to be fixed', async () => {});",
            "// test('TC-F07-S01-006 AC-1: commented out', async () => {});",
            "/*",
            "test('TC-F07-S01-007 AC-1: inside a block comment', async () => {});",
            "*/",
            "test.describe.skip('TC-F07-S01-008 AC-1: a skipped block', () => {",
            "  test('TC-F07-S01-009 AC-1: inside the skipped block', async () => {});",
            "});",
            "test('TC-F07-S01-010 AC-1: skips itself', async () => {",
            "  test.skip();",
            "});",
            "test.describe('TC-F07-S01-011 AC-1: a block that skips', () => {",
            "  test.fixme(true, 'not ready');",
            "  test('TC-F07-S01-012 AC-1: inside it', async () => {});",
            "});",
            "test.only('TC-F07-S01-013 AC-1: only', async () => {});",
            "test.describe.serial(`TC-F07-S01-014 AC-2: a serial block`, () => {",
            "  test(",
            "    \"TC-F07-S01-015 AC-3: a title on the next line\", async () => {});",
            "});",
            "test.step('TC-F07-S01-016 AC-1: a step is not a test', async () => {});",
        ]))
        self.write("qa/checks/F07/check.sh", "#!/bin/bash\n# TC-F07-S01-017 AC-4: a shell check is titled in its header\n")
        stage_dir = "features/F07-x/stages/S01-x"
        self.write(stage_dir + "/stage.yaml", "id: F07-S01\nverification:\n  automation:\n"
                   "  - {id: web, tool: playwright, file: qa/web/F07/slots.spec.ts}\n"
                   "  - {id: check, tool: script, file: qa/checks/F07/check.sh}\n")
        stage = model._scope(stage_dir, "stage.yaml")
        # TC-014 is the title of a describe block: only TC-015, the test inside it, is a case.
        self.assertEqual([(c["id"], c["covers"]) for c in stage["_title_cases"]], [
            ("TC-F07-S01-002", ["AC-1"]), ("TC-F07-S01-015", ["AC-3"]),
            ("TC-F07-S01-017", ["AC-4"])])

    def test_a_describe_title_declares_no_case_and_covers_no_criterion(self):
        self.write("qa/web/F07/slots.spec.ts", "\n".join([
            "test.describe('TC-F07-S01-001 AC-1: free slots', () => {",
            "  test('lists them', async () => {});",
            "  test('hides a held one', async () => {});",
            "});",
        ]))
        stage_dir = "features/F07-x/stages/S01-x"
        self.write(stage_dir + "/stage.yaml", "id: F07-S01\nverification:\n  automation:\n"
                   "  - {id: web, tool: playwright, file: qa/web/F07/slots.spec.ts}\n")
        stage = model._scope(stage_dir, "stage.yaml")
        self.assertEqual(stage["_title_cases"], [])
        self.assertEqual(stage["verification"]["automation"][0]["_covers"], [])
        # So validate reports the criterion uncovered and the entry as proving nothing.
        closed = fx.stage(cases=[], _cases_md=None, _title_cases=stage["_title_cases"])
        closed["verification"]["automation"] = stage["verification"]["automation"]
        found = validate.validate(fx.bundle(stages=[closed]))
        self.assertTrue(any("AC-1 is not covered" in f for f in found), found)
        self.assertTrue(any("covers no case" in f for f in found), found)

    def test_yaml_cases_keep_their_own_rules(self):
        stage_dir = "features/F07-x/stages/S01-x"
        self.write(stage_dir + "/stage.yaml", "id: F07-S01\ncases:\n- {id: TC-F07-S01-001}\n")
        self.assertNotIn("_title_cases", model._scope(stage_dir, "stage.yaml"))


if __name__ == "__main__":
    unittest.main()
