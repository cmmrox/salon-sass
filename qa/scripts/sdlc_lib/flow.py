"""Whose turn it is. The handoff between the lead, the developer and QA is read from
stage.yaml, feature.yaml and the bug files, never from a conversation, so that the roles can
be different sessions or different tools.
"""
from . import schema as S

NOBODY = ("-", "-")


def merged(feature):
    """Recorded merge evidence; start-check verifies it live before development starts."""
    integration = feature.get("integration") or {}
    if not isinstance(integration, dict):
        return False
    return bool(feature.get("status") in S.CLAIMED and feature.get("qa_status") == "pass" and
                integration.get("pull_request") and integration.get("merged_commit") and
                integration.get("merged_at"))


def predecessors(feature, features):
    """Portfolio order, plus any explicit dependency outside that order."""
    fid = feature.get("id", "")
    dependencies = feature.get("depends_on") or []
    return [f for f in features if f.get("id", "") < fid or f.get("id") in dependencies]


def _awaiting_developer(scope):
    bugs = list(scope.get("_bugs") or []) + list(scope.get("_stage_bugs") or [])
    return [b for b in bugs if b.get("status") in ("open", "reopened")]


def _qa_turn(scope, verify_command, close_command):
    """The part of the loop that is the same for a stage and a feature in QA."""
    scope_id, qa_status = scope.get("id"), scope.get("qa_status")
    if qa_status == "pass":
        return ("lead", close_command)
    if qa_status == "blocked":
        return ("lead", f"unblock QA: {scope.get('blocked_reason') or 'no reason given'}")
    if qa_status == "failing" and _awaiting_developer(scope):
        return ("developer", f"bug-fix {scope_id}")
    # not-run, or failing with every bug fixed and waiting to be re-verified
    return ("qa", verify_command)


def stage_turn(stage):
    """Returns (role, next action) for one stage."""
    sid, status = stage.get("id"), stage.get("status")
    if status == "outlined":
        return ("lead", f"stage-plan {sid}")
    if status in ("planned", "in-progress"):
        return ("developer", f"stage-build {sid}")
    if status == "built":
        if stage.get("qa_status") == "not-run":
            # The normal path: no stage pass was asked for, so the lead closes on the
            # developer's gates and QA waits for the whole feature.
            return ("lead", f"stage-close {sid}")
        return _qa_turn(stage, f"stage-verify {sid}", f"stage-close {sid}")
    return NOBODY


def feature_turn(feature, stages, features=()):
    """Returns (role, next action) for one feature, given its own stages.

    features is every feature, used to see whether a backlog feature's dependencies are done.
    The role "waiting" means nobody can act yet.
    """
    fid, status = feature.get("id"), feature.get("status")
    if status == "backlog":
        done = {f.get("id") for f in features if merged(f)}
        unmet = [dep for dep in feature.get("depends_on") or [] if dep not in done]
        if unmet:
            return ("waiting", "after " + ", ".join(unmet))
        mine = str(feature.get("milestone") or "")
        earlier = sorted({str(f.get("milestone")) for f in features
                          if f.get("milestone") and str(f.get("milestone")) < mine and f.get("status") != "released"})
        if earlier and not feature.get("started_early"):
            return ("waiting", f"after {earlier[0]} is released")
        pending = [f.get("id") for f in predecessors(feature, features) if not merged(f)]
        if pending and not feature.get("started_early"):
            return ("waiting", "after GitHub merge of " + ", ".join(pending))
        return ("lead", f"feature-new {fid}")
    if status in S.CLAIMED:
        if not merged(feature):
            if (feature.get("integration") or {}).get("pull_request"):
                return ("lead", f"merge-sync {fid} (after owner merges the PR)")
            return ("lead", f"feature-submit {fid}")
        return NOBODY
    for stage in sorted(stages, key=lambda s: (s.get("sequence") or 0, s.get("id") or "")):
        if stage.get("status") not in S.CLAIMED:
            return stage_turn(stage)
    if not stages:
        return ("lead", f"feature-new {fid}")
    # The feature pass is where every bug is settled, including one carried from a stage.
    carried = [bug for stage in stages for bug in stage.get("_bugs") or []]
    return _qa_turn({**feature, "_stage_bugs": carried}, f"feature-verify {fid}", f"feature-close {fid}")
