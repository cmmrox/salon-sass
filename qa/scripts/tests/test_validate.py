import unittest

from sdlc_lib import generate, validate
from tests import fixtures as fx


def findings(bundle):
    return validate.validate(bundle)


class ValidateTest(unittest.TestCase):
    def assertFinding(self, bundle, fragment):
        found = findings(bundle)
        self.assertTrue(any(fragment in f for f in found), f"expected a finding containing {fragment!r}, got {found}")

    def test_known_good_bundle_is_clean(self):
        self.assertEqual(findings(fx.bundle()), [])

    # --- closing a stage: on the developer's gates, with QA waiting for the feature ---

    def test_stage_closes_without_a_stage_qa_pass(self):
        self.assertEqual(findings(fx.bundle(stages=[fx.unverified_stage()])), [])
        self.assertEqual(findings(fx.bundle(stages=[fx.unverified_stage(status="built")])), [])

    def test_stage_cannot_close_over_an_unfinished_stage_pass(self):
        for left in ("failing", "blocked"):
            self.assertFinding(fx.bundle(stages=[fx.unverified_stage(qa_status=left, blocked_reason="x")]),
                               f"a stage QA pass was left '{left}'")

    def test_handed_over_stage_needs_the_developer_gates_and_the_handoff(self):
        self.assertFinding(fx.bundle(stages=[fx.unverified_stage(status="built", verification={})]),
                           "lists no verification.commands")
        self.assertFinding(fx.bundle(stages=[fx.unverified_stage(status="built", handoff={"notes": " "})]),
                           "handoff.notes is empty")
        building = fx.unverified_stage(status="in-progress", handoff={})
        self.assertEqual(findings(fx.bundle(stages=[building])), [])

    # --- the QA gate: a feature, or a stage pass the owner asked for, cannot claim what QA has not proved ---

    def passed_feature(self, **changes):
        data = dict(status="in-qa", qa_status="pass", _has_review=True,
                    evidence={"last_run": "qa/reports/x", "last_result": "pass"},
                    cases=[{"id": "TC-F07-001", "title": "Book end to end", "type": "functional",
                            "priority": "critical", "covers": ["BKG-01"]}],
                    _cases_md=["TC-F07-001"],
                    verification={"automation": [{"id": "journey", "tool": "playwright",
                                                  "file": "qa/web/F07/journey-book.spec.ts",
                                                  "covers": ["TC-F07-001"]}]})
        data.update(changes)
        return fx.feature(**data)

    def test_feature_pass_needs_every_stage_criterion_covered_by_a_case(self):
        covered = fx.stage(qa_status="not-run", evidence={}, _has_review=False)
        self.assertEqual(findings(fx.bundle(features=[self.passed_feature()], stages=[covered])), [])
        self.assertFinding(fx.bundle(features=[self.passed_feature()], stages=[fx.unverified_stage()]),
                           "AC-1 is not covered by any test case (F07 cannot pass QA until it is)")
        self.assertEqual(findings(fx.bundle(stages=[fx.unverified_stage()])), [])

    def test_feature_pass_needs_every_stage_case_accounted_for(self):
        loose = fx.stage(qa_status="not-run", evidence={}, _has_review=False)
        loose["verification"]["automation"] = []
        self.assertFinding(fx.bundle(features=[self.passed_feature()], stages=[loose]),
                           "neither automated, manual nor deferred")

    def test_feature_cannot_pass_over_a_blocking_bug_carried_from_a_stage(self):
        carried = fx.stage(qa_status="not-run", evidence={}, _has_review=False, _bugs=[fx.bug()])
        self.assertFinding(fx.bundle(features=[self.passed_feature()], stages=[carried]), "QA cannot pass with it unresolved")
        carried["_bugs"][0]["severity"] = "minor"
        self.assertEqual(findings(fx.bundle(features=[self.passed_feature()], stages=[carried])), [])

    def test_feature_pass_needs_evidence_and_a_review(self):
        self.assertFinding(fx.bundle(features=[self.passed_feature(evidence={})]), "without evidence")
        self.assertFinding(fx.bundle(features=[self.passed_feature(_has_review=False)]), "has no review.md")

    def test_qa_pass_needs_evidence(self):
        self.assertFinding(fx.bundle(stages=[fx.stage(evidence={})]), "without evidence")

    def test_unresolved_major_bug_blocks_the_pass(self):
        self.assertFinding(fx.bundle(stages=[fx.stage(_bugs=[fx.bug()])]), "QA cannot pass with it unresolved")

    def test_fixed_but_unverified_bug_still_blocks(self):
        self.assertFinding(fx.bundle(stages=[fx.stage(_bugs=[fx.bug(status="fixed")])]), "QA cannot pass")

    def test_verified_bug_and_open_minor_bug_do_not_block(self):
        bugs = [fx.bug(status="verified"),
                fx.bug(id="BUG-F07-S01-002", severity="minor",
                       _path=fx.STAGE_DIR + "/bugs/BUG-F07-S01-002.md")]
        self.assertEqual(findings(fx.bundle(stages=[fx.stage(_bugs=bugs)])), [])

    def test_only_the_owner_may_decide_not_to_fix(self):
        self.assertFinding(fx.bundle(stages=[fx.stage(_bugs=[fx.bug(status="wont-fix")])]), "needs approved_by")

    def test_every_acceptance_criterion_needs_a_case(self):
        spec = dict(fx.stage()["_spec"], acs=["AC-1", "AC-2"])
        self.assertFinding(fx.bundle(stages=[fx.stage(_spec=spec)]), "AC-2 is not covered by any test case")

    def test_every_case_must_be_automated_manual_or_deferred(self):
        broken = fx.stage()
        broken["verification"]["automation"][0]["covers"] = []
        self.assertFinding(fx.bundle(stages=[broken]), "neither automated, manual nor deferred")

    def test_a_case_cannot_be_both_covered_and_deferred(self):
        broken = fx.stage()
        broken["verification"]["deferred"] = [{"case": "TC-F07-S01-001", "reason": "later", "unblocked_by": "F07-S02"}]
        self.assertFinding(fx.bundle(stages=[broken]), "both deferred and claimed as covered")

    def test_deferred_case_must_say_what_unblocks_it(self):
        broken = fx.stage()
        broken["cases"].append({"id": "TC-F07-S01-002", "title": "On iOS", "type": "mobile",
                                "priority": "low", "covers": ["AC-1"]})
        broken["_cases_md"].append("TC-F07-S01-002")
        broken["verification"]["deferred"] = [{"case": "TC-F07-S01-002", "reason": "no iOS simulator"}]
        self.assertFinding(fx.bundle(stages=[broken]), "does not say what unblocks it")
        broken["verification"]["deferred"][0]["unblocked_by"] = "the owner provides a Mac build machine"
        self.assertEqual(findings(fx.bundle(stages=[broken])), [])

    def test_manual_case_counts_only_once_performed(self):
        broken = fx.stage()
        broken["verification"]["automation"] = []
        broken["verification"]["manual"] = [{"case": "TC-F07-S01-001", "reason": "needs a person"}]
        self.assertFinding(fx.bundle(stages=[broken]), "has no performed_on date")
        broken["verification"]["manual"][0]["performed_on"] = "2026-10-12"
        self.assertEqual(findings(fx.bundle(stages=[broken])), [])

    def test_major_bug_cannot_be_deferred_but_can_be_withdrawn_or_declined_by_the_owner(self):
        deferred = fx.bug(status="deferred", approved_by="owner")
        self.assertFinding(fx.bundle(stages=[fx.stage(_bugs=[deferred])]), "cannot be deferred")
        for status in ("not-a-bug", "wont-fix"):
            settled = fx.bug(status=status, approved_by="owner")
            self.assertEqual(findings(fx.bundle(stages=[fx.stage(_bugs=[settled])])), [])

    def test_bug_found_after_release_does_not_un_pass_released_work(self):
        late = fx.bug(found_in_release="v1.0.0")
        released = fx.stage(status="released", released_in="v1.0.0", _bugs=[late])
        self.assertEqual(findings(fx.bundle(stages=[released])), [])
        self.assertFinding(fx.bundle(stages=[fx.stage(_bugs=[late])]), "QA cannot pass")

    def test_blocked_qa_needs_a_reason(self):
        blocked = fx.stage(status="built", qa_status="blocked", evidence={})
        self.assertFinding(fx.bundle(stages=[blocked]), "gives no blocked_reason")

    def test_stage_may_keep_building_on_a_dependency_reopened_by_a_change_request(self):
        reopened = fx.stage(status="in-progress", qa_status="not-run", reopened_by="CR-001")
        second = fx.stage(id="F07-S02", sequence=2, status="in-progress", qa_status="not-run",
                          depends_on=["F07-S01"], cases=[], _cases_md=None,
                          verification={"commands": ["cd apps/api && ./mvnw verify"]},
                          _dir=fx.FEATURE_DIR + "/stages/S02-hold", _path=fx.FEATURE_DIR + "/stages/S02-hold/stage.yaml")
        self.assertEqual(findings(fx.bundle(stages=[reopened, second])), [])

    def test_missing_automation_file_is_reported(self):
        self.assertFinding(fx.bundle(stages=[fx.stage(_missing_files=["qa/api/F07/slots.spec.ts"])]), "does not exist")

    def test_a_pass_needs_a_product_review(self):
        self.assertFinding(fx.bundle(stages=[fx.stage(_has_review=False)]), "has no review.md")

    def test_stage_pass_in_progress_is_not_held_to_the_gate_until_it_claims_a_pass(self):
        started = fx.unverified_stage(status="built", qa_status="failing")
        self.assertEqual(findings(fx.bundle(stages=[started])), [])

    # --- structure ---

    def test_spec_must_carry_every_required_heading(self):
        spec = dict(fx.stage()["_spec"], headings=["Objective", "Scope"])
        self.assertFinding(fx.bundle(stages=[fx.stage(_spec=spec)]), "missing the '## Acceptance criteria' section")

    def test_spec_must_send_the_reader_to_project_documents_that_exist(self):
        nothing = dict(fx.stage()["_spec"], read_first=[])
        self.assertFinding(fx.bundle(stages=[fx.stage(_spec=nothing)]), "names no project document under 'Read first'")
        gone = dict(fx.stage()["_spec"], read_first=["docs/x.md"], missing_docs=["docs/x.md"])
        self.assertFinding(fx.bundle(stages=[fx.stage(_spec=gone)]), "read docs/x.md, which does not exist")

    def test_spec_and_stage_yaml_must_list_the_same_verification_commands(self):
        drifted = dict(fx.stage()["_spec"], commands=["cd apps/api && ./mvnw verify", "pnpm -r test"])
        self.assertFinding(fx.bundle(stages=[fx.stage(_spec=drifted)]), "differs from the bash block")

    def test_later_milestone_cannot_start_before_the_earlier_one_is_released(self):
        app = fx.feature(id="F15", title="Customer app", status="planned", milestone="M2-mobile",
                         requirements=[], _dir="features/F15-customer-mobile-app",
                         _path="features/F15-customer-mobile-app/feature.yaml")
        app_stage = fx.stage(id="F15-S01", feature="F15", status="outlined", qa_status="not-run", requirements=[],
                             cases=[], _cases_md=None, verification={}, _spec=None,
                             _feature_dir="features/F15-customer-mobile-app",
                             _dir="features/F15-customer-mobile-app/stages/S01-shell",
                             _path="features/F15-customer-mobile-app/stages/S01-shell/stage.yaml")
        early = fx.bundle(features=[fx.feature(), app], stages=[fx.stage(), app_stage])
        self.assertFinding(early, "milestone M1-web is not released")
        app["started_early"] = "owner, 2026-10-12: start the app shell in parallel"
        self.assertEqual(findings(fx.bundle(features=[fx.feature(), app], stages=[fx.stage(), app_stage])), [])

    def test_project_documents_are_indexed_and_pointers_resolve(self):
        self.assertFinding(fx.bundle(docs={"index": False}), "index of project documents is missing")
        state = {"index": True, "unlisted": ["docs/qa/new.md"], "dangling": ["docs/gone.md"],
                 "pointers": [{"source": "AGENTS.md", "target": "docs/moved.md"}]}
        found = findings(fx.bundle(docs=state))
        self.assertTrue(any("docs/qa/new.md is not listed in the index" in f for f in found))
        self.assertTrue(any("lists docs/gone.md, which does not exist" in f for f in found))
        self.assertTrue(any("AGENTS.md: points to docs/moved.md" in f for f in found))

    def test_cases_md_and_stage_yaml_must_agree(self):
        self.assertFinding(fx.bundle(stages=[fx.stage(_cases_md=["TC-F07-S01-009"])]), "not declared under cases:")

    def test_stage_cannot_be_built_on_an_unfinished_dependency(self):
        first = fx.stage(status="in-progress", qa_status="not-run")
        second = fx.stage(id="F07-S02", sequence=2, status="in-progress", qa_status="not-run",
                          depends_on=["F07-S01"], cases=[], _cases_md=None,
                          _dir=fx.FEATURE_DIR + "/stages/S02-hold", _path=fx.FEATURE_DIR + "/stages/S02-hold/stage.yaml",
                          verification={})
        self.assertFinding(fx.bundle(stages=[first, second]), "depends on F07-S01, which is only 'in-progress'")

    def test_stage_nfr_focus_must_come_from_its_feature(self):
        self.assertFinding(fx.bundle(stages=[fx.stage(nfr_focus=["NFR-01"])]), "not in feature F07's nfr_focus")
        self.assertEqual(findings(fx.bundle(features=[fx.feature(nfr_focus=["NFR-01"])],
                                            stages=[fx.stage(nfr_focus=["NFR-01"])])), [])

    def test_stage_requirement_must_belong_to_its_feature(self):
        self.assertFinding(fx.bundle(stages=[fx.stage(requirements=["PAY-01"])]), "does not belong to feature F07")

    # --- requirement ownership ---

    def test_release_one_requirement_must_have_an_owner(self):
        self.assertFinding(fx.bundle(features=[fx.feature(requirements=[])], stages=[fx.stage(requirements=[])]),
                           "BKG-01 is a Release 1 requirement that no feature owns")

    def test_requirement_cannot_have_two_owners(self):
        other = fx.feature(id="F08", title="Payments", status="backlog",
                           _dir="features/F08-payments", _path="features/F08-payments/feature.yaml")
        self.assertFinding(fx.bundle(features=[fx.feature(), other]), "owned by more than one feature")

    def test_feature_may_extend_a_requirement_another_feature_owns(self):
        app = fx.feature(id="F15", title="Customer app", status="backlog", milestone="M2-mobile",
                         requirements=[], extends=["BKG-01"],
                         _dir="features/F15-customer-mobile-app", _path="features/F15-customer-mobile-app/feature.yaml")
        self.assertEqual(findings(fx.bundle(features=[fx.feature(), app])), [])
        app["extends"] = ["XXX-99"]
        self.assertFinding(fx.bundle(features=[fx.feature(), app]), "not a functional requirement")
        self.assertFinding(fx.bundle(features=[fx.feature(extends=["BKG-01"])]), "both owned and extended")

    def test_feature_needs_a_milestone(self):
        self.assertFinding(fx.bundle(features=[fx.feature(milestone=None)]), "must look like M1-web")

    def test_planned_feature_must_assign_every_requirement_to_a_stage(self):
        self.assertFinding(fx.bundle(stages=[fx.stage(requirements=[])]), "not assigned to any stage of F07")

    def test_feature_cannot_complete_before_its_stages(self):
        done = fx.feature(status="complete", qa_status="pass", _has_review=True,
                          evidence={"last_run": "qa/reports/x", "last_result": "pass"},
                          cases=[{"id": "TC-F07-001", "title": "Book end to end", "type": "functional",
                                  "priority": "critical", "covers": ["BKG-01"]}],
                          _cases_md=["TC-F07-001"],
                          verification={"manual": [{"case": "TC-F07-001", "reason": "needs a real phone",
                                                    "performed_on": "2026-10-12"}]})
        self.assertEqual(findings(fx.bundle(features=[done])), [])
        self.assertFinding(fx.bundle(features=[done], stages=[fx.stage(status="built", qa_status="not-run")]),
                           "but stage F07-S01 is 'built'")

    # --- generated documents and skills ---

    def test_stale_generated_document_is_reported(self):
        bundle = fx.bundle()
        expected = generate.render(bundle)
        self.assertTrue(any("out of date" in f for f in validate.validate(bundle, expected)))
        bundle["generated"] = dict(expected)
        self.assertEqual(validate.validate(bundle, expected), [])

    def test_skill_rules(self):
        unlinked = fx.skill(references=[{"name": "x.md", "lines": 10, "has_contents": False, "linked": False}])
        self.assertFinding(fx.bundle(skills=[unlinked]), "not linked from SKILL.md")
        long_ref = fx.skill(references=[{"name": "x.md", "lines": 300, "has_contents": False, "linked": True}])
        self.assertFinding(fx.bundle(skills=[long_ref]), "needs a '## Contents' section")
        self.assertFinding(fx.bundle(skills=[fx.skill(mirror="diverged")]), "is diverged")
        self.assertFinding(fx.bundle(skills=[fx.skill(body_lines=600)]), "keep it under 500")


if __name__ == "__main__":
    unittest.main()
