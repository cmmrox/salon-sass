import importlib.util
import os
import unittest

from sdlc_lib import flow, runner
from tests import fixtures as fx

_spec = importlib.util.spec_from_file_location(
    "guard", os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "guard.py"))
guard = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(guard)


class FlowTest(unittest.TestCase):
    """The handoff is read from files, so these states are the whole protocol."""

    def test_stage_turns(self):
        turn = lambda **c: flow.stage_turn(fx.stage(**c))
        self.assertEqual(turn(status="outlined"), ("lead", "stage-plan F07-S01"))
        self.assertEqual(turn(status="planned"), ("developer", "stage-build F07-S01"))
        # QA waits for the whole feature, so a built stage goes straight to the lead.
        self.assertEqual(turn(status="built", qa_status="not-run"), ("lead", "stage-close F07-S01"))
        self.assertEqual(turn(status="complete"), flow.NOBODY)

    def test_stage_turns_when_the_owner_asked_for_a_stage_pass(self):
        turn = lambda **c: flow.stage_turn(fx.stage(status="built", **c))
        self.assertEqual(turn(qa_status="failing", _bugs=[fx.bug()]), ("developer", "bug-fix F07-S01"))
        self.assertEqual(turn(qa_status="failing", _bugs=[fx.bug(status="fixed")]), ("qa", "stage-verify F07-S01"))
        self.assertEqual(turn(qa_status="blocked", blocked_reason="no emulator"), ("lead", "unblock QA: no emulator"))
        self.assertEqual(turn(qa_status="pass"), ("lead", "stage-close F07-S01"))

    def test_feature_turns(self):
        self.assertEqual(flow.feature_turn(fx.feature(status="backlog"), []), ("lead", "feature-new F07"))
        waiting = fx.feature(id="F08", status="backlog", depends_on=["F07"])
        self.assertEqual(flow.feature_turn(waiting, [], [fx.feature(), waiting]), ("waiting", "after F07"))
        self.assertEqual(flow.feature_turn(waiting, [], [fx.feature(status="complete"), waiting]),
                         ("lead", "feature-new F08"))
        app = fx.feature(id="F15", status="backlog", milestone="M2-mobile")
        self.assertEqual(flow.feature_turn(app, [], [fx.feature(status="complete"), app]),
                         ("waiting", "after M1-web is released"))
        self.assertEqual(flow.feature_turn(app, [], [fx.feature(status="released"), app]),
                         ("lead", "feature-new F15"))
        self.assertEqual(flow.feature_turn(fx.feature(), [fx.stage(status="planned")]),
                         ("developer", "stage-build F07-S01"))
        self.assertEqual(flow.feature_turn(fx.feature(), [fx.stage()]), ("qa", "feature-verify F07"))
        self.assertEqual(flow.feature_turn(fx.feature(), [fx.unverified_stage()]), ("qa", "feature-verify F07"))
        self.assertEqual(flow.feature_turn(fx.feature(qa_status="pass"), [fx.stage()]), ("lead", "feature-close F07"))

    def test_feature_pass_settles_a_bug_carried_from_a_stage(self):
        carried = fx.unverified_stage(_bugs=[fx.bug(severity="minor")])
        failing = fx.feature(status="in-qa", qa_status="failing")
        self.assertEqual(flow.feature_turn(failing, [carried]), ("developer", "bug-fix F07"))
        carried["_bugs"][0]["status"] = "fixed"
        self.assertEqual(flow.feature_turn(failing, [carried]), ("qa", "feature-verify F07"))


class RunnerTest(unittest.TestCase):
    def setUp(self):
        second = fx.stage(id="F07-S02", sequence=2, status="built", qa_status="not-run")
        second["verification"]["automation"] = [
            {"id": "hold", "tool": "k6", "file": "qa/load/F07/hold.js",
             "covers": ["TC-F07-S02-001"], "suites": ["regression"]}]
        journey = fx.feature(verification={"automation": [
            {"id": "journey", "tool": "maestro", "file": "qa/mobile/F07/book.yaml",
             "covers": ["TC-F07-001"], "suites": ["smoke", "regression"]}]})
        self.bundle = fx.bundle(features=[journey], stages=[fx.stage(), second])

    def ids(self, **scope):
        return [e["id"] for e in runner.select(self.bundle, **scope)]

    def test_stage_run_selects_only_that_stage(self):
        self.assertEqual(self.ids(stage="F07-S02"), ["hold"])

    def test_feature_run_selects_every_stage_then_the_feature_journeys(self):
        self.assertEqual(self.ids(feature="F07"), ["slots", "hold", "journey"])

    def test_suite_draws_only_on_closed_work(self):
        # F07-S02 is not closed and the feature is not complete, so only the closed stage counts.
        self.assertEqual(self.ids(suite="regression"), ["slots"])
        self.assertEqual(self.ids(suite="smoke"), [])

    def test_unknown_scope_is_refused(self):
        with self.assertRaises(runner.SelectionError):
            runner.select(self.bundle, stage="F99-S01")
        with self.assertRaises(runner.SelectionError):
            runner.select(self.bundle, stage="F07-S01", suite="smoke")

    def test_empty_selection_never_passes(self):
        self.assertEqual(runner.run([], "smoke", dry_run=True)[0], 2)

    def test_commands(self):
        entries = runner.select(self.bundle, feature="F07")
        self.assertEqual(runner.command(entries[0]),
                         ["pnpm", "--dir", "qa", "exec", "playwright", "test", "api/F07/slots.spec.ts", "--project", "api"])
        self.assertEqual(runner.command(entries[1]), ["k6", "run", "qa/load/F07/hold.js"])
        self.assertEqual(runner.command(entries[2]), ["maestro", "test", "qa/mobile/F07/book.yaml"])


class GuardTest(unittest.TestCase):
    def test_developer_paths(self):
        for path in ("apps/api/src/main/java/A.java", "packages/api-contracts/src/index.ts", "package.json",
                     "features/F07-online-booking/stages/S01-slot-search/stage.yaml",
                     "features/F07-online-booking/stages/S01-slot-search/bugs/BUG-F07-S01-001.md",
                     "features/README.md", "qa/coverage.md", "docs/api/openapi.json", ".github/workflows/ci.yml"):
            self.assertTrue(guard.allowed("dev", path), path)
        for path in ("qa/web/F07/book.spec.ts", "features/F07-online-booking/stages/S01-slot-search/cases.md",
                     "features/F07-online-booking/stages/S01-slot-search/spec.md", "AGENTS.md",
                     ".claude/skills/salon-qa/SKILL.md", "docs/brd/requirements.yaml",
                     "docs/engineering/backend.md", "docs/architecture/multi-tenancy.md",
                     "apps/api/AGENTS.md", "apps/web/CLAUDE.md"):
            self.assertFalse(guard.allowed("dev", path), path)

    def test_qa_paths(self):
        for path in ("qa/web/F07/book.spec.ts", "qa/package.json",
                     "features/F07-online-booking/stages/S01-slot-search/cases.md",
                     "features/F07-online-booking/stages/S01-slot-search/review.md",
                     "features/F07-online-booking/feature.yaml", "features/README.md", "qa/coverage.md"):
            self.assertTrue(guard.allowed("qa", path), path)
        for path in ("apps/web/app/page.tsx", "qa/scripts/sdlc_lib/validate.py", "package.json", "qa/AGENTS.md",
                     "docs/qa/test-plan.md",
                     "features/F07-online-booking/stages/S01-slot-search/spec.md"):
            self.assertFalse(guard.allowed("qa", path), path)


if __name__ == "__main__":
    unittest.main()
