"""Scope reuse refusals in temporary repositories; no product services/checks."""
import json
from pathlib import Path
import unittest

from sdlc_lib import evidence, model, scoped_evidence, runner
from tests import test_evidence_progress as fixtures


class ScopedEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.EvidenceTest()
        self.fixture.setUp()
        f = self.fixture
        _, base_report = f.record("full-feature-qa")
        self.base = f.revision
        f.write("apps/source.txt", "reviewed product v2\n")
        f.commit()
        _, commands = f.record("developer-commands")
        command_revision = evidence.git("rev-parse", "HEAD")
        f.feature["integration"] = {"qa_revision": self.base}
        f.feature["evidence"] = {"last_run": base_report, "last_result": "pass"}
        f.yaml(f.feature_dir + "/feature.yaml", f.feature)
        self.reviewed = f.commit()
        f.write(".local/targeted.log", "Running 2 tests\n  2 passed (0.1s)\n")
        self.path = f.feature_dir + "/scoped-qa.json"
        self.data = {"version": 1, "kind": "independent-scoped-qa", "reviewed_revision": self.reviewed,
            "owner_instruction": "Reuse intact evidence", "qa_identity": {"agent": "fixture-qa",
            "role": "independent QA", "recorded_at_utc": "2026-10-15T00:00:00Z"},
            "limits": ["Tooling fixture; no product execution"],
            "features": {"F07": {"base_report": base_report, "base_revision": self.base,
                "base_summary_sha256": evidence.file_digest(Path(base_report) / "summary.json"),
                "changed_paths": scoped_evidence._paths(self.base, self.reviewed),
                "impact": "Independent fixture assessment of one reviewed source change"}},
            "targeted": {"log": ".local/targeted.log", "sha256": evidence.file_digest(".local/targeted.log"),
                "passed": 2, "failed": 0, "coverage": ["reviewed change"]},
            "commands": {"report": commands, "revision": command_revision,
                "summary_sha256": evidence.file_digest(Path(commands) / "summary.json")}}
        f.feature["evidence"]["scoped_assessment"] = self.path
        f.yaml(f.feature_dir + "/feature.yaml", f.feature)
        self.save()

    def tearDown(self):
        self.fixture.tearDown()

    def save(self):
        self.fixture.write(self.path, json.dumps(self.data))
        self.fixture.commit()
        self.scope = model.load()["features"][0]

    def check(self):
        return scoped_evidence.check(self.scope)

    def test_intact_composition_and_process_changes_reuse_without_execution(self):
        self.assertEqual(self.check()["reviewed_revision"], self.reviewed)
        self.fixture.write("qa/scripts/sdlc_lib/process.py", "# process only\n")
        self.fixture.commit()
        self.assertEqual(self.check()["targeted_passed"], 2)

    def test_new_committed_product_change_refuses(self):
        self.fixture.write("apps/source.txt", "unreviewed v3\n")
        self.fixture.commit()
        with self.assertRaisesRegex(evidence.Refusal, "unreviewed change"):
            self.check()

    def test_untracked_qa_or_dirty_requirements_refuse(self):
        self.fixture.write("qa/web/unreviewed.spec.ts", "new coverage\n")
        with self.assertRaisesRegex(evidence.Refusal, "unreviewed change"):
            self.check()
        Path("qa/web/unreviewed.spec.ts").unlink()
        self.fixture.write("docs/brd/requirements.yaml", "requirements: []\n")
        with self.assertRaisesRegex(evidence.Refusal, "unreviewed change"):
            self.check()

    def test_semantic_yaml_metadata_allowed_but_cases_refused(self):
        f = self.fixture
        f.feature["status"] = "complete"
        f.yaml(f.feature_dir + "/feature.yaml", f.feature)
        self.check()
        f.feature["cases"].append({"id": "TC-F07-002"})
        f.yaml(f.feature_dir + "/feature.yaml", f.feature)
        with self.assertRaisesRegex(evidence.Refusal, "unreviewed change"):
            self.check()

    def test_missing_inventory_and_relabelled_base_refuse(self):
        self.data["features"]["F07"]["changed_paths"] = []
        self.save()
        with self.assertRaisesRegex(evidence.Refusal, "exact base-to-reviewed"):
            self.check()
        self.data["features"]["F07"]["base_revision"] = self.reviewed
        self.save()
        with self.assertRaisesRegex(evidence.Refusal, "preserve the original"):
            self.check()

    def test_tampered_base_log_and_partial_delta_refuse(self):
        report = self.data["features"]["F07"]["base_report"]
        row = json.loads((Path(report) / "summary.json").read_text())["entries"][0]
        original = Path(row["log"]).read_bytes()
        Path(row["log"]).write_text("edited")
        with self.assertRaisesRegex(evidence.Refusal, "log is missing or changed"):
            self.check()
        Path(row["log"]).write_bytes(original)
        self.fixture.write(".local/targeted.log", "  2 passed (0.1s)\n  1 skipped\n")
        self.data["targeted"]["sha256"] = evidence.file_digest(".local/targeted.log")
        self.save()
        with self.assertRaisesRegex(evidence.Refusal, "partial"):
            self.check()

    def test_testplan_environment_and_unattested_process_document_refuse(self):
        for path in ("docs/qa/test-plan.md", "docs/operations/environments-and-releases.md",
                     "docs/changes/CR-010-business-rule.md"):
            self.fixture.write(path, "new fixture or product rule\n")
            with self.assertRaisesRegex(evidence.Refusal, "unreviewed change"):
                self.check()
            Path(path).unlink()
        path = "docs/decisions/DECISION_LOG.md"
        self.fixture.write(path, "CR009 process decision\n")
        with self.assertRaisesRegex(evidence.Refusal, "unattested process document"):
            self.check()
        self.data["process_documents"] = {path: evidence.file_digest(path)}
        self.save()
        self.check()
        self.fixture.write(path, "future unreviewed business rule\n")
        with self.assertRaisesRegex(evidence.Refusal, "unattested process document"):
            self.check()

    def test_changed_manifest_must_be_committed(self):
        self.fixture.write(self.path, json.dumps({**self.data, "limits": ["uncommitted"]}))
        with self.assertRaisesRegex(evidence.Refusal, "commit the independent"):
            self.check()

    def scoped_report(self):
        bundle = model.load()
        entries = runner.select(bundle, feature="F07")
        code, report = runner.run(entries, "F07-delta",
            certification=evidence.scoped_certification(bundle, "F07"),
            certify_again=lambda: evidence.scoped_certification(model.load(), "F07"))
        self.assertEqual(code, 0)
        revision = evidence.git("rev-parse", "HEAD")
        receipt = {"feature": "F07", "report": report, "revision": revision,
                   "summary_sha256": evidence.file_digest(Path(report) / "summary.json"),
                   "automation_ids": [row["id"] for row in entries]}
        return {"reports": [receipt], "coverage": ["reviewed delta"]}, revision

    def test_sealed_delta_report_keeps_exact_registered_selection(self):
        delta, revision = self.scoped_report()
        self.assertEqual(scoped_evidence._delta_reports(delta, self.data, "F07", revision, self.path), 2)
        delta["reports"][0]["automation_ids"] = ["unknown"]
        with self.assertRaisesRegex(evidence.Refusal, "registered automation"):
            scoped_evidence._delta_reports(delta, self.data, "F07", revision, self.path)

    def test_delta_report_tampered_log_refuses(self):
        delta, revision = self.scoped_report()
        report = delta["reports"][0]["report"]
        row = json.loads((Path(report) / "summary.json").read_text())["entries"][0]
        Path(row["log"]).write_text("edited")
        with self.assertRaisesRegex(evidence.Refusal, "log is missing or changed"):
            scoped_evidence._delta_reports(delta, self.data, "F07", revision, self.path)

    def test_delta_report_exit_zero_with_skipped_checks_refuses(self):
        self.fixture.write("qa/checks/F07/pass.sh", "#!/bin/bash\necho '  1 skipped'\nexit 0\n")
        self.fixture.commit()
        delta, revision = self.scoped_report()
        with self.assertRaisesRegex(evidence.Refusal, "incomplete checks"):
            scoped_evidence._delta_reports(delta, self.data, "F07", revision, self.path)

    def test_delta_report_different_feature_or_duplicate_ids_refuses(self):
        delta, revision = self.scoped_report()
        delta["reports"][0]["feature"] = "F99"
        with self.assertRaisesRegex(evidence.Refusal, "unassessed"):
            scoped_evidence._delta_reports(delta, self.data, "F07", revision, self.path)
        delta["reports"][0]["feature"] = "F07"
        delta["reports"][0]["automation_ids"] = ["works", "works"]
        with self.assertRaisesRegex(evidence.Refusal, "selection differs"):
            scoped_evidence._delta_reports(delta, self.data, "F07", revision, self.path)

    def test_qa_only_gate_reuse_requires_exact_inventory_and_reason(self):
        command = {"revision": evidence.git("rev-parse", "HEAD")}
        self.fixture.write("qa/web/followup.spec.ts", "independent followup")
        reviewed = self.fixture.commit()
        with self.assertRaisesRegex(evidence.Refusal, "QA-only inventory"):
            scoped_evidence._command_delta(command, reviewed)
        command.update(reused_qa_paths=["qa/web/followup.spec.ts"], reuse_reason="Product gates do not execute QA package")
        scoped_evidence._command_delta(command, reviewed)
        self.fixture.write("apps/source.txt", "unverified product")
        reviewed = self.fixture.commit()
        with self.assertRaisesRegex(evidence.Refusal, "substantive"):
            scoped_evidence._command_delta(command, reviewed)

    def test_change_request_closure_only_metadata_can_follow_gates(self):
        path = "docs/changes/CR-010-scope.md"
        self.fixture.write(path, "---\nid: CR-010\nstatus: approved\n---\nScope unchanged\n")
        before = self.fixture.commit()
        self.fixture.write(path, "---\nid: CR-010\nstatus: implemented\n---\nScope unchanged\n")
        after = self.fixture.commit()
        self.assertTrue(scoped_evidence._request_closure(before, after, path))
        scoped_evidence._command_delta({"revision": before}, after)
        self.fixture.write(path, "---\nid: CR-010\nstatus: implemented\n---\nNew requirement\n")
        after = self.fixture.commit()
        self.assertFalse(scoped_evidence._request_closure(before, after, path))
        with self.assertRaisesRegex(evidence.Refusal, "substantive"):
            scoped_evidence._command_delta({"revision": before}, after)

    def test_sealed_failed_aggregate_can_retain_only_its_passing_entry(self):
        f = self.fixture
        f.write("qa/checks/F07/fail.sh", "#!/bin/bash\nexit 1\n")
        f.feature["verification"]["automation"][0]["file"] = "qa/checks/F07/fail.sh"
        f.yaml(f.feature_dir + "/feature.yaml", f.feature)
        f.commit()
        bundle = model.load()
        entries = runner.select(bundle, feature="F07")
        code, report = runner.run(entries, "F07-delta",
            certification=evidence.scoped_certification(bundle, "F07"),
            certify_again=lambda: evidence.scoped_certification(model.load(), "F07"))
        self.assertEqual(code, 1)
        revision = evidence.git("rev-parse", "HEAD")
        summary = json.loads((Path(report) / "summary.json").read_text())
        self.assertEqual(summary["result"], "fail")
        with self.assertRaisesRegex(evidence.Refusal, "partial, nonpassing"):
            evidence.proof(report, summary["certification"], entries)
        receipt = {"feature": "F07", "report": report, "revision": revision,
                   "summary_sha256": evidence.file_digest(Path(report) / "summary.json"),
                   "automation_ids": ["works"]}
        delta = {"reports": [receipt], "coverage": ["retained unaffected group"]}
        self.assertEqual(scoped_evidence._delta_reports(delta, self.data, "F07", revision, self.path), 1)
        receipt["automation_ids"] = ["journey"]
        with self.assertRaisesRegex(evidence.Refusal, "nonpassing entry"):
            scoped_evidence._delta_reports(delta, self.data, "F07", revision, self.path)
        receipt["automation_ids"] = ["works"]
        f.write("qa/web/other.spec.ts", "unrelated browser assertion correction")
        reviewed = f.commit()
        with self.assertRaisesRegex(evidence.Refusal, "unreviewed change"):
            scoped_evidence._delta_reports(delta, self.data, "F07", reviewed, self.path)
        receipt.update(retained_qa_paths=["qa/web/other.spec.ts"], retention_reason="Independent other spec, no shared/selected dependency changed")
        self.assertEqual(scoped_evidence._delta_reports(delta, self.data, "F07", reviewed, self.path), 1)
        receipt["retained_qa_paths"] = ["qa/checks/F07/pass.sh"]
        with self.assertRaisesRegex(evidence.Refusal, "only other explicitly assessed"):
            scoped_evidence._delta_reports(delta, self.data, "F07", reviewed, self.path)

    def test_request_completion_after_scoped_qa_keeps_intact_assessment(self):
        path = "docs/changes/CR-010-scope.md"
        f = self.fixture
        f.write(path, "---\nid: CR-010\nstatus: approved\n---\nApproved behavior\n")
        f.commit()
        _, commands = f.record("developer-commands")
        delta, reviewed = self.scoped_report()
        self.data["reviewed_revision"] = reviewed
        self.data["features"]["F07"]["changed_paths"] = scoped_evidence._paths(self.base, reviewed)
        self.data["commands"] = {"report": commands, "revision": reviewed,
            "summary_sha256": evidence.file_digest(Path(commands) / "summary.json")}
        self.data["targeted"] = delta
        self.save()
        self.check()
        f.write(path, "---\nid: CR-010\nstatus: implemented\n---\nApproved behavior\n")
        f.commit()
        self.check()
        f.write(path, "---\nid: CR-010\nstatus: implemented\n---\nUnreviewed behavior\n")
        with self.assertRaisesRegex(evidence.Refusal, "unreviewed change"):
            self.check()
