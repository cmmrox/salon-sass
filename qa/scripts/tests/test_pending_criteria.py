"""Criteria a change request adds to a merged stage's spec wait for their cases while the request
is approved (decision 098), and are required like any other once it is implemented."""
import unittest

from sdlc_lib import generate, model, validate
from tests import fixtures as fx

SPEC = """# F07-S01 Slot search

## Acceptance criteria

- AC-1: a free slot is listed.

## Done criteria

- AC-9 is mentioned here only as prose, not as a criterion line.

## CR-028 (follow-up after the merge of PR #26)

### F07-S01 (`features/F07-online-booking/stages/S01-slot-search/spec.md`)

- AC-2: a visit holds several services.
- **AC-3**: a visit is placed whole or not at all.
"""


def passed_feature():
    """A feature that claims its QA pass, so every stage criterion must be covered."""
    return fx.feature(status="in-qa", qa_status="pass", _has_review=True,
                      evidence={"last_run": "qa/reports/x", "last_result": "pass"},
                      cases=[{"id": "TC-F07-001", "title": "Book end to end", "type": "functional",
                              "priority": "critical", "covers": ["BKG-01"]}],
                      _cases_md=["TC-F07-001"],
                      verification={"automation": [{"id": "journey", "tool": "playwright",
                                                    "file": "qa/web/F07/journey-book.spec.ts",
                                                    "covers": ["TC-F07-001"]}]})


def with_cr_criteria(stage, cr_criteria):
    stage["_spec"]["acs"] = ["AC-1"] + sorted(cr_criteria)
    stage["_spec"]["cr_criteria"] = dict(cr_criteria)
    return stage


def request(status, cid="CR-028"):
    return {"id": cid, "status": status, "_path": f"docs/changes/{cid}-x.md"}


class ReadingTest(unittest.TestCase):
    def test_criteria_under_a_change_request_heading_are_recorded_with_it(self):
        spec = model._spec(SPEC)
        self.assertEqual(spec["acs"], ["AC-1", "AC-2", "AC-3"])
        self.assertEqual(spec["cr_criteria"], {"AC-2": "CR-028", "AC-3": "CR-028"})

    def test_a_spec_without_such_a_section_has_none(self):
        self.assertEqual(model._spec("## Acceptance criteria\n\n- AC-1: x\n")["cr_criteria"], {})


class PendingTest(unittest.TestCase):
    def found(self, stage, requests, features=None):
        return validate.validate(fx.bundle(stages=[stage], features=features, change_requests=requests))

    def uncovered(self, found):
        return [f for f in found if "is not covered by any test case" in f]

    def test_an_approved_request_leaves_its_criteria_pending_at_a_stage_pass(self):
        stage = with_cr_criteria(fx.stage(), {"AC-2": "CR-028"})
        self.assertEqual(self.found(stage, [request("approved")]), [])

    def test_an_approved_request_leaves_its_criteria_pending_at_a_feature_pass(self):
        stage = with_cr_criteria(fx.stage(qa_status="not-run", evidence={}, _has_review=False),
                                 {"AC-2": "CR-028"})
        self.assertEqual(self.found(stage, [request("approved")], [passed_feature()]), [])

    def test_once_implemented_proposed_rejected_or_unknown_the_criteria_are_required(self):
        for requests in ([request("implemented")], [request("proposed")], [request("rejected")], []):
            for scoped in (fx.stage(), fx.stage(qa_status="not-run", evidence={}, _has_review=False)):
                stage = with_cr_criteria(scoped, {"AC-2": "CR-028"})
                found = self.found(stage, requests, [passed_feature()])
                self.assertTrue(any("AC-2 is not covered" in f for f in self.uncovered(found)), (requests, found))

    def test_a_criterion_outside_the_section_is_required_as_before(self):
        stage = with_cr_criteria(fx.stage(), {"AC-2": "CR-028"})
        stage["_spec"]["acs"].append("AC-4")
        found = self.uncovered(self.found(stage, [request("approved")]))
        self.assertEqual(len(found), 1)
        self.assertIn("AC-4 is not covered", found[0])

    def test_a_pending_criterion_may_already_have_a_case(self):
        stage = with_cr_criteria(fx.stage(), {"AC-2": "CR-028"})
        stage["cases"][0]["covers"] = ["AC-1", "AC-2"]
        self.assertEqual(self.found(stage, [request("approved")]), [])

    def test_coverage_shows_pending_criteria(self):
        stage = with_cr_criteria(fx.stage(), {"AC-2": "CR-028", "AC-3": "CR-028"})
        text = generate.render_coverage(fx.bundle(stages=[stage], change_requests=[request("approved")]))
        row = [line for line in text.splitlines() if line.startswith("| F07-S01 |")][0]
        self.assertEqual(row.split("|")[5].strip(), "3 (2 pending)")
        pending = text.split("## Pending criteria", 1)[1]
        self.assertIn("| F07-S01 | AC-2 | CR-028 |", pending)
        self.assertIn("| F07-S01 | AC-3 | CR-028 |", pending)
        done = generate.render_coverage(fx.bundle(stages=[stage], change_requests=[request("implemented")]))
        self.assertIn("| F07-S01 | complete | pass | BKG-01 | 3 |", done)
        self.assertIn("| - | none | - |", done.split("## Pending criteria", 1)[1])


if __name__ == "__main__":
    unittest.main()
