"""Validates the work spine: requirements, features, stages, cases, bugs, change requests,
releases, generated documents and the skills themselves.

Pure logic over the bundle built by model.load(). Every rule returns findings as strings
of the form "<path>: <what is wrong>". An empty list is the only pass.
"""
import os
import re
from datetime import datetime

from . import flow, schema as S

SKILL_NAME_RE = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
RESERVED_SKILL_WORDS = ("anthropic", "claude")


def _add(findings, scope, message):
    findings.append(f"{scope.get('_path', '?')}: {message}")


def _list(value):
    return value if isinstance(value, list) else []


def _verification(scope):
    value = scope.get("verification")
    return value if isinstance(value, dict) else {}


def _automation(scope):
    return [e for e in _list(_verification(scope).get("automation")) if isinstance(e, dict)]


def _covered_cases(scope):
    covered = set()
    for entry in _automation(scope):
        covered.update(_list(entry.get("covers")))
    return covered


def _case_refs(scope, key):
    return [e for e in _list(_verification(scope).get(key)) if isinstance(e, dict)]


# --- requirements ---------------------------------------------------------------------

def check_requirements(bundle):
    findings, seen = [], set()
    path = {"_path": "docs/brd/requirements.yaml"}
    if not bundle["requirements"]:
        _add(findings, path, "no requirements could be read; a validator that reads nothing must fail")
    for req in bundle["requirements"]:
        rid = req.get("id")
        if not rid:
            _add(findings, path, "a requirement has no id")
            continue
        if rid in seen:
            _add(findings, path, f"{rid} is declared twice")
        seen.add(rid)
        if req.get("release") not in S.RELEASES:
            _add(findings, path, f"{rid} has release '{req.get('release')}', expected one of {S.RELEASES}")
        if req.get("status", "active") not in ("active", "changed", "retired"):
            _add(findings, path, f"{rid} has unknown status '{req.get('status')}'")
    return findings


# --- cases, automation, bugs: shared by stages and features -----------------------------

def check_cases(scope, scope_id, cover_targets, target_name):
    findings = []
    cases = [c for c in _list(scope.get("cases")) if isinstance(c, dict)]
    ids = [c.get("id") for c in cases]
    for case in cases:
        cid = case.get("id") or ""
        match = S.CASE_ID_RE.match(cid)
        if not match or match.group(1) != scope_id:
            _add(findings, scope, f"case id '{cid}' must look like TC-{scope_id}-001")
        if ids.count(cid) > 1:
            _add(findings, scope, f"case {cid} is declared more than once")
        if case.get("type") not in S.CASE_TYPES:
            _add(findings, scope, f"case {cid} has type '{case.get('type')}', expected one of {S.CASE_TYPES}")
        if case.get("priority") not in S.CASE_PRIORITIES:
            _add(findings, scope, f"case {cid} has priority '{case.get('priority')}', expected one of {S.CASE_PRIORITIES}")
        if not case.get("title"):
            _add(findings, scope, f"case {cid} has no title")
        if cover_targets is not None:
            for target in _list(case.get("covers")):
                if target not in cover_targets:
                    _add(findings, scope, f"case {cid} covers '{target}', which is not a {target_name} of {scope_id}")

    documented = scope.get("_cases_md")
    if cases and documented is None:
        _add(findings, scope, "cases are declared but cases.md does not exist")
    if documented is not None:
        for cid in sorted(set(ids) - set(documented)):
            _add(findings, scope, f"case {cid} has no '### {cid}' section in cases.md")
        for cid in sorted(set(documented) - set(ids)):
            _add(findings, scope, f"cases.md describes {cid}, which is not declared under cases:")

    entry_ids = []
    for entry in _automation(scope):
        eid = entry.get("id") or "?"
        entry_ids.append(eid)
        if entry.get("tool") not in S.TOOLS:
            _add(findings, scope, f"automation {eid} has tool '{entry.get('tool')}', expected one of {S.TOOLS}")
        if not entry.get("file"):
            _add(findings, scope, f"automation {eid} names no file")
        if not _list(entry.get("covers")):
            _add(findings, scope, f"automation {eid} covers no case; automation that proves nothing is not coverage")
        for cid in _list(entry.get("covers")):
            if cid not in ids:
                _add(findings, scope, f"automation {eid} covers {cid}, which is not a declared case")
        for suite in _list(entry.get("suites")):
            if suite not in S.SUITES:
                _add(findings, scope, f"automation {eid} is in suite '{suite}', expected one of {S.SUITES}")
    for eid in sorted({e for e in entry_ids if entry_ids.count(e) > 1}):
        _add(findings, scope, f"automation id {eid} is used more than once")
    for missing in scope.get("_missing_files") or []:
        _add(findings, scope, f"automation file {missing} does not exist")

    covered = _covered_cases(scope)
    manual = {e.get("case") for e in _case_refs(scope, "manual")}
    for key in ("manual", "deferred"):
        for entry in _case_refs(scope, key):
            if entry.get("case") not in ids:
                _add(findings, scope, f"{key} entry names {entry.get('case')}, which is not a declared case")
            if not entry.get("reason"):
                _add(findings, scope, f"{key} entry for {entry.get('case')} gives no reason")
    for entry in _case_refs(scope, "deferred"):
        if not entry.get("unblocked_by"):
            _add(findings, scope, f"deferred case {entry.get('case')} does not say what unblocks it (unblocked_by)")
        if entry.get("case") in covered or entry.get("case") in manual:
            _add(findings, scope, f"case {entry.get('case')} is both deferred and claimed as covered")
    return findings


def check_bugs(scope, scope_id):
    findings = []
    for bug in scope.get("_bugs") or []:
        bid = bug.get("id") or ""
        match = S.BUG_ID_RE.match(bid)
        if not match or match.group(1) != scope_id:
            _add(findings, bug, f"bug id '{bid}' must look like BUG-{scope_id}-001")
        elif os.path.basename(bug["_path"]).split(".")[0] != bid and not os.path.basename(bug["_path"]).startswith(bid + "-"):
            _add(findings, bug, f"file name must start with the bug id {bid}")
        if bug.get("severity") not in S.BUG_SEVERITIES:
            _add(findings, bug, f"severity '{bug.get('severity')}' is not one of {S.BUG_SEVERITIES}")
        if bug.get("status") not in S.BUG_STATUSES:
            _add(findings, bug, f"status '{bug.get('status')}' is not one of {S.BUG_STATUSES}")
        if not bug.get("title"):
            _add(findings, bug, "bug has no title")
        if bug.get("status") in ("wont-fix", "deferred") and not bug.get("approved_by"):
            _add(findings, bug, f"status '{bug.get('status')}' needs approved_by: only the owner decides not to fix")
        if bug.get("status") == "deferred" and bug.get("severity") not in S.DEFERRABLE_SEVERITIES:
            _add(findings, bug, f"a {bug.get('severity')} bug cannot be deferred; only {S.DEFERRABLE_SEVERITIES} can")
    return findings


def blocking_bugs(scope):
    """Bugs that still stop QA from passing.

    A bug found in a version that has already shipped (found_in_release) does not un-pass the
    released work it was found in; it is fixed as a hotfix and gates the next release instead.
    """
    return [
        bug for bug in scope.get("_bugs") or []
        if bug.get("severity") in S.BLOCKING_SEVERITIES
        and bug.get("status") not in S.RESOLVED_BUG_STATUSES
        and not (scope.get("status") == "released" and bug.get("found_in_release"))
    ]


def check_qa_status(scope, scope_id):
    findings = []
    if scope.get("qa_status") not in S.QA_STATUSES:
        _add(findings, scope, f"qa_status '{scope.get('qa_status')}' is not one of {S.QA_STATUSES}")
    if scope.get("qa_status") == "blocked" and not scope.get("blocked_reason"):
        _add(findings, scope, f"{scope_id} is blocked in QA but gives no blocked_reason")
    return findings


def _cases(scope):
    return [c for c in _list(scope.get("cases")) if isinstance(c, dict)]


def check_coverage(scope, acceptance_criteria):
    """Every criterion has a case, every case is accounted for, and no blocking bug is left.

    acceptance_criteria is the list of AC ids for a stage, or None for a feature.
    """
    findings, cases = [], _cases(scope)
    asserted = set()
    for case in cases:
        asserted.update(_list(case.get("covers")))
    for criterion in acceptance_criteria or []:
        if criterion not in asserted:
            _add(findings, scope, f"{criterion} is not covered by any test case")
    for entry in _case_refs(scope, "manual"):
        if not entry.get("performed_on"):
            _add(findings, scope, f"manual case {entry.get('case')} has no performed_on date; a manual case counts only once it was done")
    accounted = _covered_cases(scope)
    accounted |= {e.get("case") for e in _case_refs(scope, "manual")}
    accounted |= {e.get("case") for e in _case_refs(scope, "deferred")}
    for case in cases:
        if case.get("id") not in accounted:
            _add(findings, scope, f"case {case.get('id')} is neither automated, manual nor deferred with a reason")
    for bug in blocking_bugs(scope):
        _add(findings, scope, f"{bug.get('id')} ({bug.get('severity')}) is '{bug.get('status')}'; QA cannot pass with it unresolved")
    return findings


def check_qa_gate(scope, scope_id, acceptance_criteria):
    """Everything that must be true before QA may be recorded as passed.

    Applies to a feature, and to a stage only when the owner asked for a stage pass.
    acceptance_criteria is the list of AC ids for a stage, or None for a feature.
    """
    findings = []
    if scope.get("qa_status") != "pass":
        _add(findings, scope, f"{scope_id} is '{scope.get('status')}' but qa_status is '{scope.get('qa_status')}'")
    evidence = scope.get("evidence") if isinstance(scope.get("evidence"), dict) else {}
    if evidence.get("last_result") != "pass" or not evidence.get("last_run"):
        _add(findings, scope, f"{scope_id} claims a QA pass without evidence.last_run and evidence.last_result: pass")
    if not _cases(scope):
        _add(findings, scope, f"{scope_id} claims a QA pass with no test cases")
    findings += check_coverage(scope, acceptance_criteria)
    if not scope.get("_has_review"):
        _add(findings, scope, f"{scope_id} has no review.md; QA owes a product review before a pass")
    return findings


def check_handed_over(stage, sid):
    """What a stage is closed on when QA waits for the whole feature: the developer's gates
    and the developer's own record that every acceptance criterion was checked."""
    findings, status = [], stage.get("status")
    if status in S.CLAIMED and stage.get("qa_status") in S.UNFINISHED_QA:
        _add(findings, stage, f"{sid} is '{status}' but a stage QA pass was left '{stage.get('qa_status')}'; "
                              "a stage pass that was started is finished before the stage is closed")
    if not _list(_verification(stage).get("commands")) and _list(stage.get("surfaces")) != ["docs"]:
        _add(findings, stage, f"{sid} is '{status}' but lists no verification.commands for the developer gates")
    handoff = stage.get("handoff") if isinstance(stage.get("handoff"), dict) else {}
    if not str(handoff.get("notes") or "").strip():
        _add(findings, stage, f"{sid} is '{status}' but handoff.notes is empty; the stage is closed on the "
                              "developer's record of what was checked and what was left out")
    return findings


def check_feature_covers_stages(feature, fid, stages):
    """A feature pass is the QA of every stage in it: each stage's criteria are covered by
    that stage's cases, written at the feature pass or at a stage pass the owner asked for."""
    findings = []
    for stage in stages:
        if stage.get("qa_status") == "pass":
            continue  # already held to the full gate as a stage
        spec = stage.get("_spec")
        for finding in check_coverage(stage, spec["acs"] if spec else []):
            findings.append(f"{finding} ({fid} cannot pass QA until it is)")
    return findings


# --- features ---------------------------------------------------------------------------

def check_integration(scope):
    findings = []
    integration = scope.get("integration") or {}
    if not isinstance(integration, dict):
        _add(findings, scope, "integration must be a mapping")
        return findings
    for key in ("qa_revision", "merged_commit"):
        value = integration.get(key)
        if value and not S.COMMIT_RE.fullmatch(str(value)):
            _add(findings, scope, f"integration.{key} must be a full Git commit SHA")
    if integration.get("pull_request") and not S.PR_URL_RE.fullmatch(str(integration["pull_request"])):
        _add(findings, scope, "integration.pull_request must be a GitHub pull request URL")
    branch = integration.get("branch")
    if branch and not re.fullmatch(r"[a-z]+/feature/" + re.escape(scope.get("id", "")) + r"-[A-Za-z0-9-]+", str(branch)):
        _add(findings, scope, "integration.branch must name this feature's integration branch")
    if integration.get("merged_at"):
        try:
            timestamp = datetime.fromisoformat(str(integration["merged_at"]).replace("Z", "+00:00"))
            if timestamp.tzinfo is None:
                raise ValueError("timezone missing")
        except ValueError:
            _add(findings, scope, "integration.merged_at must be a timestamp with a timezone")
    if (scope.get("status") in S.CLAIMED or scope.get("qa_status") == "pass"):
        if not integration.get("qa_revision"):
            _add(findings, scope, "passing feature QA needs integration.qa_revision")
        if not integration.get("branch"):
            _add(findings, scope, "passing feature QA needs its integration.branch")
    if integration.get("merged_commit") or integration.get("merged_at"):
        if not all(integration.get(k) for k in ("branch", "pull_request", "qa_revision", "merged_commit", "merged_at")):
            _add(findings, scope, "merge evidence needs branch, pull_request, qa_revision, merged_commit and merged_at")
        if scope.get("status") not in S.CLAIMED or scope.get("qa_status") != "pass":
            _add(findings, scope, "merge evidence belongs only to a QA-passed, closed feature")
    return findings


def check_features(bundle):
    findings = []
    requirements = {r.get("id"): r for r in bundle["requirements"]}
    feature_ids = [f.get("id") for f in bundle["features"]]
    owners = {}
    for feature in bundle["features"]:
        fid = feature.get("id") or ""
        if feature.get("_unreadable"):
            _add(findings, feature, "feature.yaml is missing or is not a YAML mapping")
            continue
        if not S.FEATURE_ID_RE.match(fid):
            _add(findings, feature, f"feature id '{fid}' must look like F07")
        if feature_ids.count(fid) > 1:
            _add(findings, feature, f"feature id {fid} is used by more than one folder")
        if not os.path.basename(feature["_dir"]).startswith(fid + "-"):
            _add(findings, feature, f"folder name must start with '{fid}-'")
        status = feature.get("status")
        if status not in S.FEATURE_STATUSES:
            _add(findings, feature, f"status '{status}' is not one of {S.FEATURE_STATUSES}")
        if feature.get("release") not in S.RELEASES:
            _add(findings, feature, f"release '{feature.get('release')}' is not one of {S.RELEASES}")
        if not feature.get("title"):
            _add(findings, feature, "feature has no title")
        if not S.MILESTONE_RE.match(str(feature.get("milestone") or "")):
            _add(findings, feature, f"milestone '{feature.get('milestone')}' must look like M1-web")
        elif status not in (None, "backlog") and not feature.get("started_early"):
            unreleased = sorted({str(f.get("milestone")) for f in bundle["features"]
                                 if f.get("milestone") and str(f.get("milestone")) < feature["milestone"]
                                 and f.get("status") != "released"})
            if unreleased:
                _add(findings, feature, f"{fid} is '{status}' but milestone {unreleased[0]} is not released; "
                                        "only the owner starts a later milestone early (record it in started_early)")
        findings += check_qa_status(feature, fid)
        findings += check_integration(feature)
        if status not in (None, "backlog"):
            pending = [f.get("id") for f in flow.predecessors(feature, bundle["features"])
                       if not flow.merged(f) and (not feature.get("started_early") or
                                                f.get("id") in _list(feature.get("depends_on")))]
            if pending:
                _add(findings, feature, "started before confirmed GitHub merge of " + ", ".join(pending))
        for dep in _list(feature.get("depends_on")):
            if dep not in feature_ids:
                _add(findings, feature, f"depends_on names {dep}, which is not a feature")
        for rid in _list(feature.get("requirements")):
            req = requirements.get(rid)
            if req is None:
                _add(findings, feature, f"requirement {rid} is not in the register")
            elif req.get("module") == "NFR":
                _add(findings, feature, f"{rid} is non-functional; list it under nfr_focus, not requirements")
            owners.setdefault(rid, []).append(fid)
        for rid in _list(feature.get("extends")):
            # A requirement another feature owns, delivered here on one more surface (the app
            # after the web). Ownership stays single; coverage is tracked on both.
            if requirements.get(rid, {}).get("module") in (None, "NFR"):
                _add(findings, feature, f"extends names {rid}, which is not a functional requirement")
            elif rid in _list(feature.get("requirements")):
                _add(findings, feature, f"{rid} is both owned and extended by {fid}; list it once")
        for rid in _list(feature.get("nfr_focus")):
            if requirements.get(rid, {}).get("module") != "NFR":
                _add(findings, feature, f"nfr_focus names {rid}, which is not a non-functional requirement")

        stages = [s for s in bundle["stages"] if s.get("_feature_dir") == feature["_dir"]]
        if status and status != "backlog":
            if not feature.get("_has_readme"):
                _add(findings, feature, f"{fid} is '{status}' but has no README.md")
            if not stages:
                _add(findings, feature, f"{fid} is '{status}' but has no stages")
            claimed = set()
            for stage in stages:
                claimed.update(_list(stage.get("requirements")))
            for rid in _list(feature.get("requirements")) + _list(feature.get("extends")):
                if rid not in claimed:
                    _add(findings, feature, f"requirement {rid} is not assigned to any stage of {fid}")
        findings += check_cases(feature, fid, set(_list(feature.get("requirements")) + _list(feature.get("extends"))), "requirement")
        findings += check_bugs(feature, fid)
        if status in S.CLAIMED:
            for stage in stages:
                if stage.get("status") not in S.CLAIMED:
                    _add(findings, feature, f"{fid} is '{status}' but stage {stage.get('id')} is '{stage.get('status')}'")
        if status in S.CLAIMED or feature.get("qa_status") == "pass":
            findings += check_qa_gate(feature, fid, None)
            findings += check_feature_covers_stages(feature, fid, stages)
        if (status == "released") != bool(feature.get("released_in")):
            _add(findings, feature, "released_in must be set exactly when status is 'released'")

    for feature in bundle["features"]:
        for rid in _list(feature.get("extends")):
            if rid in requirements and rid not in owners:
                _add(findings, feature, f"extends names {rid}, which no feature owns")
    for rid, owning in sorted(owners.items()):
        if len(owning) > 1:
            findings.append(f"docs/brd/requirements.yaml: {rid} is owned by more than one feature: {', '.join(owning)}")
    for req in bundle["requirements"]:
        if (req.get("release") == "R1" and req.get("module") != "NFR"
                and req.get("status", "active") != "retired" and req.get("id") not in owners):
            findings.append(f"docs/brd/requirements.yaml: {req.get('id')} is a Release 1 requirement that no feature owns")
    return findings


# --- stages -----------------------------------------------------------------------------

def _has_cycle(stage_id, depends, trail=()):
    if stage_id in trail:
        return True
    return any(_has_cycle(dep, depends, trail + (stage_id,)) for dep in depends.get(stage_id, []))


def check_stages(bundle):
    findings = []
    features = {f["_dir"]: f for f in bundle["features"]}
    by_id = {s.get("id"): s for s in bundle["stages"]}
    ids = [s.get("id") for s in bundle["stages"]]
    depends = {s.get("id"): _list(s.get("depends_on")) for s in bundle["stages"]}
    for stage in bundle["stages"]:
        sid = stage.get("id") or ""
        if stage.get("_unreadable"):
            _add(findings, stage, "stage.yaml is missing or is not a YAML mapping")
            continue
        feature = features.get(stage.get("_feature_dir"), {})
        match = S.STAGE_ID_RE.match(sid)
        if not match:
            _add(findings, stage, f"stage id '{sid}' must look like F07-S02")
            continue
        if ids.count(sid) > 1:
            _add(findings, stage, f"stage id {sid} is used by more than one folder")
        if match.group(1) != feature.get("id") or stage.get("feature") != feature.get("id"):
            _add(findings, stage, f"{sid} must sit under, and name, feature {match.group(1)}")
        if not os.path.basename(stage["_dir"]).startswith(match.group(2) + "-"):
            _add(findings, stage, f"folder name must start with '{match.group(2)}-'")
        status = stage.get("status")
        if status not in S.STAGE_STATUSES:
            _add(findings, stage, f"status '{status}' is not one of {S.STAGE_STATUSES}")
        findings += check_qa_status(stage, sid)
        if not stage.get("title"):
            _add(findings, stage, "stage has no title")
        if not isinstance(stage.get("sequence"), int):
            _add(findings, stage, "sequence must be an integer")
        surfaces = _list(stage.get("surfaces"))
        if not surfaces or any(s not in S.SURFACES for s in surfaces):
            _add(findings, stage, f"surfaces must be a non-empty subset of {S.SURFACES}")
        for rid in _list(stage.get("requirements")):
            if rid not in _list(feature.get("requirements")) + _list(feature.get("extends")):
                _add(findings, stage, f"requirement {rid} does not belong to feature {feature.get('id')}")
        for rid in _list(stage.get("nfr_focus")):
            if rid not in _list(feature.get("nfr_focus")):
                _add(findings, stage, f"nfr_focus names {rid}, which is not in feature {feature.get('id')}'s nfr_focus")
        if feature.get("status") == "backlog" and status not in (None, "outlined"):
            _add(findings, stage, f"{sid} is '{status}' while its feature is still in the backlog")

        for dep in depends[sid]:
            if dep not in by_id:
                _add(findings, stage, f"depends_on names {dep}, which is not a stage")
            elif (status in ("in-progress", "built") + S.CLAIMED
                  and by_id[dep].get("status") not in S.BUILDABLE_ON
                  # A stage reopened by a change request was complete once: what was built on
                  # it stays valid while it is being changed.
                  and not by_id[dep].get("reopened_by")):
                _add(findings, stage, f"{sid} is '{status}' but depends on {dep}, which is only '{by_id[dep].get('status')}'")
        if _has_cycle(sid, depends):
            _add(findings, stage, f"{sid} is part of a depends_on cycle")

        spec = stage.get("_spec")
        if status in S.SPECIFIED:
            if spec is None:
                _add(findings, stage, f"{sid} is '{status}' but has no spec.md")
            else:
                position = 0
                for heading in S.SPEC_HEADINGS:
                    try:
                        position = spec["headings"].index(heading, position) + 1
                    except ValueError:
                        _add(findings, stage, f"spec.md is missing the '## {heading}' section, or it is out of order")
                if not spec["acs"]:
                    _add(findings, stage, "spec.md lists no acceptance criteria ('- AC-1: ...')")
                if not spec.get("read_first"):
                    _add(findings, stage, "spec.md names no project document under 'Read first'; the rules for a stage live in docs/")
                for path in spec.get("missing_docs") or []:
                    _add(findings, stage, f"spec.md tells the reader to read {path}, which does not exist")
                listed = spec.get("commands")
                if listed is not None and listed != _list(_verification(stage).get("commands")):
                    _add(findings, stage, "verification.commands differs from the bash block under 'Verification commands' in spec.md")
                duplicates = {a for a in spec["acs"] if spec["acs"].count(a) > 1}
                for criterion in sorted(duplicates):
                    _add(findings, stage, f"spec.md declares {criterion} more than once")

        criteria = spec["acs"] if spec else []
        findings += check_cases(stage, sid, set(criteria) if spec else None, "acceptance criterion")
        findings += check_bugs(stage, sid)
        if status in S.HANDED_OVER:
            findings += check_handed_over(stage, sid)
        if stage.get("qa_status") == "pass":
            findings += check_qa_gate(stage, sid, criteria)
        if (status == "released") != bool(stage.get("released_in")):
            _add(findings, stage, "released_in must be set exactly when status is 'released'")
    return findings


# --- change requests and releases -------------------------------------------------------

def check_change_requests(bundle):
    findings = []
    requirement_ids = {r.get("id") for r in bundle["requirements"]}
    stage_ids = {s.get("id") for s in bundle["stages"]}
    feature_ids = {f.get("id") for f in bundle["features"]}
    for request in bundle["change_requests"]:
        cid = request.get("id") or ""
        if not S.CR_ID_RE.match(cid):
            _add(findings, request, f"change request id '{cid}' must look like CR-001")
        elif not os.path.basename(request["_path"]).startswith(cid):
            _add(findings, request, f"file name must start with {cid}")
        if request.get("status") not in S.CR_STATUSES:
            _add(findings, request, f"status '{request.get('status')}' is not one of {S.CR_STATUSES}")
        for rid in _list(request.get("requirements")):
            if rid not in requirement_ids:
                _add(findings, request, f"names requirement {rid}, which is not in the register")
        for sid in _list(request.get("stages")):
            if sid not in stage_ids:
                _add(findings, request, f"names stage {sid}, which does not exist")
        for fid in _list(request.get("features")):
            if fid not in feature_ids:
                _add(findings, request, f"names feature {fid}, which does not exist")
    return findings


def check_releases(bundle):
    findings = []
    stages = {s.get("id"): s for s in bundle["stages"]}
    features = {f.get("id"): f for f in bundle["features"]}
    for release in bundle["releases"]:
        version = release.get("release") or ""
        if not S.RELEASE_VERSION_RE.match(version):
            _add(findings, release, f"release '{version}' must look like v1.2.3")
        elif os.path.basename(os.path.dirname(release["_path"])) != version:
            _add(findings, release, f"folder must be named {version}")
        scope = release.get("scope") if isinstance(release.get("scope"), dict) else {}
        for fid in _list(scope.get("features")):
            if fid not in features:
                _add(findings, release, f"scope names feature {fid}, which does not exist")
            elif not flow.merged(features[fid]):
                _add(findings, release, f"scope includes {fid} before its confirmed GitHub merge")
        if not _list(scope.get("stages")):
            _add(findings, release, "release scope lists no stages")
        for sid in _list(scope.get("stages")):
            if sid not in stages:
                _add(findings, release, f"scope names stage {sid}, which does not exist")
            elif stages[sid].get("status") not in S.CLAIMED:
                _add(findings, release, f"scope includes {sid}, which is only '{stages[sid].get('status')}'")
            elif not flow.merged(features.get(stages[sid].get("feature"), {})):
                _add(findings, release, f"scope includes {sid} before its feature's confirmed GitHub merge")
    return findings


# --- skills and generated documents -----------------------------------------------------

def check_skills(bundle):
    findings = []
    for skill in bundle["skills"]:
        where = {"_path": f".claude/skills/{skill['dir']}/SKILL.md"}
        if not skill["has_skill_md"]:
            _add(findings, where, "SKILL.md is missing")
            continue
        name = str(skill["frontmatter"].get("name") or "")
        description = str(skill["frontmatter"].get("description") or "")
        if name != skill["dir"]:
            _add(findings, where, f"name '{name}' must equal the folder name '{skill['dir']}'")
        if not SKILL_NAME_RE.match(name) or len(name) > S.SKILL_NAME_MAX:
            _add(findings, where, "name must be lowercase letters, digits and hyphens, 64 characters at most")
        if any(word in name for word in RESERVED_SKILL_WORDS):
            _add(findings, where, f"name must not contain {RESERVED_SKILL_WORDS}")
        if not description or len(description) > S.SKILL_DESCRIPTION_MAX:
            _add(findings, where, f"description must be 1 to {S.SKILL_DESCRIPTION_MAX} characters (it is {len(description)})")
        if skill["body_lines"] > S.SKILL_BODY_MAX_LINES:
            _add(findings, where, f"body is {skill['body_lines']} lines; keep it under {S.SKILL_BODY_MAX_LINES} and move detail into references/")
        for ref in skill["references"]:
            if not ref["linked"]:
                _add(findings, where, f"references/{ref['name']} is not linked from SKILL.md, so no agent will find it")
            if ref["lines"] > S.REFERENCE_TOC_THRESHOLD and not ref["has_contents"]:
                _add(findings, where, f"references/{ref['name']} is {ref['lines']} lines and needs a '## Contents' section at the top")
        if skill["mirror"] != "ok":
            _add(findings, where, f".agents/skills/{skill['dir']} is {skill['mirror']}; Codex would not see this skill as written")
    return findings


def check_docs(bundle):
    """Project knowledge lives under docs/, is findable from one index, and every pointer to it resolves."""
    findings, docs = [], bundle.get("docs") or {}
    if not docs.get("index"):
        findings.append("docs/README.md: the index of project documents is missing")
    for path in docs.get("unlisted") or []:
        findings.append(f"docs/README.md: {path} is not listed in the index, so nobody will be sent to it")
    for path in docs.get("dangling") or []:
        findings.append(f"docs/README.md: lists {path}, which does not exist")
    for pointer in docs.get("pointers") or []:
        findings.append(f"{pointer['source']}: points to {pointer['target']}, which does not exist")
    return findings


def check_generated(bundle, expected):
    findings = []
    for path, content in (expected or {}).items():
        if bundle["generated"].get(path) != content:
            findings.append(f"{path}: out of date; run qa/scripts/sdlc generate (never edit it by hand)")
    return findings


def validate(bundle, expected_generated=None):
    return (
        check_requirements(bundle)
        + check_features(bundle)
        + check_stages(bundle)
        + check_change_requests(bundle)
        + check_releases(bundle)
        + check_skills(bundle)
        + check_docs(bundle)
        + check_generated(bundle, expected_generated)
    )
