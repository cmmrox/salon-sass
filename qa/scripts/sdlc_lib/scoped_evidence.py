"""Verify an independent, explicit base-plus-delta assessment without running QA.

Historical receipts keep their original source and context. This is an auditable
scope decision, not a new full-feature pass or a claim of identical environments.
"""
import json
from pathlib import Path
import re
import subprocess
import yaml

from . import evidence as E, schema as S


def _ancestor(before, after):
    if not S.COMMIT_RE.fullmatch(str(before or "")) or subprocess.run(
            ["git", "merge-base", "--is-ancestor", before, after], capture_output=True).returncode:
        raise E.Refusal("scoped assessment revision is missing or not an ancestor")


def _paths(before, after):
    return sorted(filter(None, E.git("diff", "--no-renames", "--name-only", before, after).splitlines()))


def _process(path):
    # Explicit process-only exceptions. Requirements/design/API docs are not here.
    return (path in {"AGENTS.md", "CLAUDE.md", "features/README.md", "qa/coverage.md", "docs/README.md",
                     "qa/scripts/sdlc", "docs/decisions/DECISION_LOG.md",
                     "docs/operations/verification-evidence.md", "docs/operations/skill-evaluation.md",
                     "docs/operations/git-delivery.md", "docs/changes/README.md",
                     "docs/changes/CR-008-skill-productivity-and-progress.md",
                     "docs/changes/CR-009-resume-without-duplicate-qa.md"}
            or path.startswith((".claude/skills/", ".agents/skills/", ".claude/agents/",
                                "qa/scripts/sdlc_lib/", "qa/scripts/tests/"))
            or (path.startswith("features/") and
                ("/bugs/" in path or path.endswith("/review.md"))))


def _metadata(path):
    return path.startswith("features/") and Path(path).name in {"feature.yaml", "stage.yaml"}


def _historical(report, expected_hash, revision, kind, *, allow_partial=False):
    summary_path = E.local_file(str(Path(report) / "summary.json"))
    if E.file_digest(summary_path) != expected_hash:
        raise E.Refusal("scoped historical summary digest differs")
    summary = json.loads(Path(summary_path).read_text())
    if not summary.get("entries"):
        raise E.Refusal("scoped historical report selects no checks")
    cert = summary.get("certification") or {}
    if cert.get("source_revision") != revision or cert.get("kind") != kind:
        raise E.Refusal("scoped historical source/kind differs")
    # The original sealed selection, including argv, must be fully passing and intact.
    # Its definitions are historical; reviewed changed definitions are inventoried below.
    E.proof(report, cert, [E.definition(row) for row in summary.get("entries", [])], allow_partial=allow_partial)
    return summary


def _unchanged(reviewed, target, assessment_path, process_documents, retained_paths=()):
    _ancestor(reviewed, target)
    tree = E.git("ls-tree", "-r", target)
    modes = {row.split("\t", 1)[1]: row.split()[0] for row in tree.splitlines()}
    changed = set(_paths(reviewed, target))
    changed.update(filter(None, E.git("diff", "--name-only", "HEAD").splitlines()))
    changed.update(filter(None, E.git("diff", "--cached", "--name-only").splitlines()))
    changed.update(filter(None, E.git("ls-files", "--others", "--exclude-standard").splitlines()))
    for path in changed:
        if path == assessment_path:
            continue
        if path in retained_paths:
            continue
        if _request_closure(reviewed, target, path):
            if Path(path).read_text().strip() == E.git("show", f"{target}:{path}"):
                continue
        if _process(path):
            if path.startswith("docs/") and process_documents.get(path) != E.file_digest(path):
                raise E.Refusal(f"unattested process document change: {path}")
            continue
        if _metadata(path):
            try:
                if E._substance(E.git("show", f"{reviewed}:{path}")) == E._substance(Path(path).read_text()):
                    continue
            except (OSError, E.Refusal):
                pass
        raise E.Refusal(f"unreviewed change after scoped QA: {path}")
    # Git may ignore chmod under core.filemode=false; verify actual source modes too.
    for path, mode in modes.items():
        if path != assessment_path and not _process(path):
            E.file_identity(path, mode)


def _qa_definition(path):
    return path.startswith(("qa/web/", "qa/api/", "qa/mobile/", "qa/load/", "qa/checks/", "qa/shared/"))


def _request_closure(before, after, path):
    if not re.fullmatch(r"docs/changes/CR-[0-9]+-[^/]+[.]md", path):
        return False
    try:
        old, new = E.git("show", f"{before}:{path}"), E.git("show", f"{after}:{path}")
        _, old_head, old_body = old.split("---", 2)
        _, new_head, new_body = new.split("---", 2)
        left, right = yaml.safe_load(old_head), yaml.safe_load(new_head)
        if not isinstance(left, dict) or not isinstance(right, dict):
            return False
        return (left.pop("status") == "approved" and right.pop("status") == "implemented"
                and left == right and old_body == new_body)
    except (ValueError, KeyError, TypeError, E.Refusal, yaml.YAMLError):
        return False


def _command_delta(command, reviewed):
    """QA's separate package is not executed by product developer commands."""
    changes = _paths(command["revision"], reviewed)
    qa_paths = sorted(p for p in changes if _qa_definition(p))
    declared = command.get("reused_qa_paths", [])
    if declared != qa_paths or (qa_paths and not command.get("reuse_reason")):
        raise E.Refusal("developer gate reuse needs exact QA-only inventory and reason")
    for changed in changes:
        if _process(changed) or changed in qa_paths or _request_closure(command["revision"], reviewed, changed):
            continue
        if _metadata(changed) and E._substance(E.git("show", f"{command['revision']}:{changed}")) == E._substance(E.git("show", f"{reviewed}:{changed}")):
            continue
        raise E.Refusal("developer gates predate a substantive reviewed change")


def _delta_reports(delta, data, fid, reviewed, assessment_path):
    from . import model, runner
    receipts = delta.get("reports")
    if not isinstance(receipts, list) or not receipts or not delta.get("coverage"):
        raise E.Refusal("scoped delta requires passing reports and coverage")
    passed, features, selections = 0, set(), set()
    bundle = model.load()
    for receipt in receipts:
        feature = receipt["feature"]
        if feature not in data["features"]:
            raise E.Refusal("scoped delta report feature is unassessed")
        features.add(feature)
        revision = receipt["revision"]
        _ancestor(revision, reviewed)
        report = _historical(receipt["report"], receipt["summary_sha256"], revision, "scoped-feature-qa", allow_partial=True)
        if report.get("label") != feature + "-delta" or report["certification"].get("feature") != feature:
            raise E.Refusal("scoped delta report belongs to a different selection")
        ids = receipt["automation_ids"]
        try:
            expected = runner.select_delta(runner.select(bundle, feature=feature), ids)
        except runner.SelectionError as error:
            raise E.Refusal(str(error)) from error
        rows = [row for row in report["entries"] if row["id"] in ids]
        actual = [E.definition(row) for row in rows]
        if actual != expected or ids != [row["id"] for row in expected]:
            raise E.Refusal("scoped delta registered selection differs")
        for row in rows:
            if (feature, row["id"]) in selections or row["outcome"] != "pass" or row["exit_code"] != 0:
                raise E.Refusal("scoped delta repeats or selects a nonpassing entry")
            selections.add((feature, row["id"]))
            if re.search(r"(?m)^\s*\d+ (failed|skipped|interrupted|did not run)\b", Path(E.local_file(row["log"])).read_text()):
                raise E.Refusal("scoped delta log contains incomplete checks")
        retained = receipt.get("retained_qa_paths", [])
        selected_files = {row.get("file") for row in rows}
        if retained and (not receipt.get("retention_reason") or len(set(retained)) != len(retained)
                         or any(not p.startswith("qa/web/") or not p.endswith(".spec.ts")
                                or p in selected_files or p not in _paths(revision, reviewed) for p in retained)):
            raise E.Refusal("retained scoped entries may exclude only other explicitly assessed browser specs")
        _unchanged(revision, reviewed, assessment_path, data.get("process_documents") or {}, retained)
        passed += len(actual)
    if fid not in features:
        raise E.Refusal("scoped delta lacks this feature's report")
    return passed


def check(scope, target="HEAD"):
    """Validate the feature's committed independent assessment; never execute checks."""
    try:
        pointer = (scope.get("evidence") or {}).get("scoped_assessment")
        path = E.local_file(pointer)
        if path != pointer or not path.startswith("features/") or not path.endswith("/scoped-qa.json"):
            raise E.Refusal("scoped assessment requires a canonical feature-local JSON path")
        if not E.git("ls-files", "--", path) or E.git("status", "--porcelain", "--", path):
            raise E.Refusal("commit the independent scoped assessment before using it")
        if E.git("show", f"{target}:{path}") != Path(path).read_text().strip():
            raise E.Refusal("scoped assessment is not present unchanged at proposed revision")
        data = json.loads(Path(path).read_text())
        identity = data.get("qa_identity") or {}
        if (data.get("version") != 1 or data.get("kind") != "independent-scoped-qa" or
                not all(identity.get(k) for k in ("agent", "role", "recorded_at_utc")) or
                "independent" not in identity["role"].lower() or not data.get("owner_instruction") or
                not data.get("limits")):
            raise E.Refusal("scoped assessment needs independent QA identity, authority and limits")
        reviewed = data.get("reviewed_revision")
        _ancestor(reviewed, target)
        base = data["features"][scope["id"]]
        revision, report = base["base_revision"], base["base_report"]
        if ((scope.get("integration") or {}).get("qa_revision") != revision or
                (scope.get("evidence") or {}).get("last_run") != report or not base.get("impact")):
            raise E.Refusal("scoped assessment must preserve the original full QA revision and report")
        _ancestor(revision, reviewed)
        if base.get("changed_paths") != _paths(revision, reviewed):
            raise E.Refusal("scoped assessment does not inventory the exact base-to-reviewed changes")
        full = _historical(report, base["base_summary_sha256"], revision, "full-feature-qa")
        if (full.get("certification") or {}).get("feature") != scope["id"]:
            raise E.Refusal("scoped base full report belongs to a different feature")
        command = data["commands"]
        _ancestor(command["revision"], reviewed)
        commands = _historical(command["report"], command["summary_sha256"], command["revision"], "developer-commands")
        _command_delta(command, reviewed)
        from . import model
        required = E.gates(model.load(), scope["id"])
        passed_commands = {row.get("cmd") for row in commands["entries"]}
        if any(entry["cmd"] not in passed_commands for entry in required):
            raise E.Refusal("scoped developer report does not cover current required commands")
        delta = data["targeted"]
        if "reports" in delta:
            targeted_passed = _delta_reports(delta, data, scope["id"], reviewed, path)
        else:
            log = E.local_file(delta["log"])
            if (E.file_digest(log) != delta["sha256"] or not isinstance(delta.get("passed"), int) or
                    delta["passed"] < 1 or delta.get("failed") != 0 or not delta.get("coverage") or
                    not re.search(rf"(?m)^\s*{delta['passed']} passed \([^)]+\)\s*$", Path(log).read_text()) or
                    re.search(r"(?m)^\s*\d+ (failed|skipped|interrupted|did not run)\b", Path(log).read_text())):
                raise E.Refusal("targeted QA log is changed, partial or not positive passing evidence")
            targeted_passed = delta["passed"]
        _unchanged(reviewed, target, path, data.get("process_documents") or {})
        return {"feature": scope["id"], "qa_report": report, "commands_report": command["report"],
                "scoped_assessment": path, "reviewed_revision": reviewed, "targeted_passed": targeted_passed}
    except (OSError, ValueError, KeyError, TypeError) as error:
        if isinstance(error, E.Refusal):
            raise
        raise E.Refusal("independent scoped QA assessment is missing or invalid") from None
