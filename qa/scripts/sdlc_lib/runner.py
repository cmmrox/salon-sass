"""Selects and runs QA automation by scope.

This is the only sanctioned way to run QA automation, because the scope rule is the
point: a stage run executes that stage's automation and nothing else, a feature run
executes that feature's, and a suite run (regression, smoke) happens only when the owner
asks for it. Selection is pure and tested; execution is a thin loop over subprocesses.
"""
import datetime
import json
import os
import subprocess

from . import schema as S


class SelectionError(Exception):
    pass


def _entries(scope):
    verification = scope.get("verification") if isinstance(scope.get("verification"), dict) else {}
    automation = verification.get("automation")
    found = []
    for entry in automation if isinstance(automation, list) else []:
        if isinstance(entry, dict):
            found.append({**entry, "scope": scope.get("id")})
    return found


def _stages_of(bundle, feature):
    stages = [s for s in bundle["stages"] if s.get("_feature_dir") == feature["_dir"]]
    return sorted(stages, key=lambda s: (s.get("sequence") or 0, s.get("id") or ""))


def select(bundle, stage=None, feature=None, suite=None):
    """Returns the automation entries for exactly one scope."""
    if sum(x is not None for x in (stage, feature, suite)) != 1:
        raise SelectionError("choose exactly one of --stage, --feature or --suite")
    if stage:
        match = [s for s in bundle["stages"] if s.get("id") == stage]
        if not match:
            raise SelectionError(f"no stage.yaml declares id: {stage}")
        return _entries(match[0])
    if feature:
        match = [f for f in bundle["features"] if f.get("id") == feature]
        if not match:
            raise SelectionError(f"no feature.yaml declares id: {feature}")
        entries = []
        for item in _stages_of(bundle, match[0]):
            entries += _entries(item)
        return entries + _entries(match[0])
    if suite not in S.SUITES:
        raise SelectionError(f"suite must be one of {S.SUITES}")
    entries = []
    for scope in list(bundle["stages"]) + list(bundle["features"]):
        # A suite certifies what has been delivered, so it only draws on closed work.
        if scope.get("status") in S.CLAIMED:
            entries += [e for e in _entries(scope) if suite in (e.get("suites") or [])]
    return sorted(entries, key=lambda e: (e.get("scope") or "", e.get("id") or ""))


def command(entry):
    """The argv that runs one automation entry, from the repository root."""
    tool, path = entry.get("tool"), entry.get("file")
    if tool == "playwright":
        argv = ["pnpm", "--dir", "qa", "exec", "playwright", "test", os.path.relpath(path, "qa")]
        return argv + (["--project", entry["project"]] if entry.get("project") else [])
    if tool == "maestro":
        return ["maestro", "test", path]
    if tool == "k6":
        return ["k6", "run", path]
    if tool == "script":
        return ["bash", path]
    raise SelectionError(f"automation {entry.get('id')} has unknown tool '{tool}'")


def run(entries, label, dry_run=False, reports_root="qa/reports"):
    """Runs the entries in order. Returns (exit code, report directory or None)."""
    if not entries:
        # A run that selects nothing has verified nothing. It must never look like a pass.
        print(f"refusing: {label} selects no automation; there is nothing to run")
        return 2, None
    plan = [(entry, command(entry)) for entry in entries]
    print(f"{label}: {len(plan)} automation entr{'y' if len(plan) == 1 else 'ies'}")
    for entry, argv in plan:
        print(f"  {entry['scope']} {entry.get('id')}: {' '.join(argv)}")
    if dry_run:
        return 0, None

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    report_dir = os.path.join(reports_root, f"{stamp}-{label.replace(' ', '-')}")
    os.makedirs(report_dir, exist_ok=True)
    env = {**os.environ, "QA_REPORT_DIR": os.path.abspath(report_dir)}
    results = []
    for entry, argv in plan:
        log_path = os.path.join(report_dir, f"{entry['scope']}-{entry.get('id')}.log")
        with open(log_path, "w", encoding="utf-8") as log:
            try:
                code = subprocess.run(argv, env=env, stdout=log, stderr=subprocess.STDOUT).returncode
            except FileNotFoundError:
                # A missing tool is an environment fault, reported as such rather than as a test failure.
                log.write(f"tool not found on PATH: {argv[0]}\n")
                code = 127
        outcome = "pass" if code == 0 else ("tool-missing" if code == 127 else "fail")
        print(f"  {outcome.upper():12} {entry['scope']} {entry.get('id')}")
        results.append({
            "scope": entry["scope"], "id": entry.get("id"), "tool": entry.get("tool"),
            "file": entry.get("file"), "covers": entry.get("covers") or [],
            "exit_code": code, "outcome": outcome, "log": log_path,
        })
    passed = all(r["outcome"] == "pass" for r in results)
    summary = {"label": label, "result": "pass" if passed else "fail", "entries": results}
    with open(os.path.join(report_dir, "summary.json"), "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2)
    print(f"{'PASS' if passed else 'FAIL'}: report in {report_dir}")
    return (0 if passed else 1), report_dir


def run_release(bundle, version, dry_run=False):
    """Runs every gate of a release manifest in order and writes RESULTS.md beside it."""
    match = [r for r in bundle["releases"] if r.get("release") == version]
    if not match:
        raise SelectionError(f"qa/releases/{version}/release.yaml does not exist")
    manifest, rows, failed = match[0], [], False
    for gate in manifest.get("gates") or []:
        gate_id = gate.get("id")
        if failed:
            rows.append((gate_id, "NOT RUN", "an earlier gate failed"))
            continue
        if gate.get("suite"):
            code, report = run(select(bundle, suite=gate["suite"]), f"{version} {gate['suite']}", dry_run)
            detail = report or gate["suite"]
        else:
            print(f"gate {gate_id}: {gate.get('cmd')}")
            # A gate is a compound shell command ("cd apps/api && ./mvnw verify") read from the
            # committed release manifest. It carries the same trust as a Makefile target; no
            # request or user input ever reaches it.
            code = 0 if dry_run else subprocess.run(gate.get("cmd"), shell=True).returncode
            detail = gate.get("cmd")
        failed = code != 0
        rows.append((gate_id, "PASS" if code == 0 else "FAIL", detail))
    if dry_run:
        return 0
    lines = [f"# {version} results", "", "Written by `qa/scripts/sdlc run --release`. Do not edit by hand.", "",
             "| Gate | Result | Detail |", "| --- | --- | --- |"]
    lines += [f"| {gate_id} | {result} | `{detail}` |" for gate_id, result, detail in rows]
    deferred = manifest.get("deferred") or []
    lines += ["", "## Deferred, not certified", ""] + ([f"- {item}" for item in deferred] or ["None."])
    with open(os.path.join(os.path.dirname(manifest["_path"]), "RESULTS.md"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    return 1 if failed else 0
