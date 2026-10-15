"""Selects and runs QA automation by scope.

This is the only sanctioned way to run QA automation, because the scope rule is the
point: a stage run executes that stage's automation and nothing else, a feature run
executes that feature's, and a suite run (regression, smoke) happens only when the owner
asks for it. Selection is pure and tested; execution is a thin loop over subprocesses.
"""
import datetime
import json
import os
import signal
import subprocess
import time
import threading
import uuid

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
    if tool == "command":
        return ["bash", "-c", entry["cmd"]]
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


def _write_summary(report_dir, summary):
    """Atomic replacement leaves the last complete progress snapshot after interruption."""
    target = os.path.join(report_dir, "summary.json")
    temporary = target + ".tmp"
    with open(temporary, "w", encoding="utf-8") as handle:
        json.dump(summary, handle, indent=2)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, target)


def _process_start(pid):
    """An OS start mark helps distinguish an active process from a recycled PID."""
    try:
        found = subprocess.run(["ps", "-p", str(pid), "-o", "lstart="],
                               capture_output=True, text=True)
        return found.stdout.strip() if found.returncode == 0 else None
    except OSError:
        return None


def _cancel_process_tree(child, grace_seconds=2):
    """Cancellation only: never impose a deadline on ordinary test execution."""
    result = {"term_sent": False, "kill_sent": False, "reaped": False}
    group = child.pid if os.name == "posix" else None

    def send(sig, force_kill=False):
        try:
            if group is not None:
                os.killpg(group, sig)  # Every child was spawned in its own session.
            elif child.poll() is None:
                child.kill() if force_kill else child.terminate()
            return True
        except ProcessLookupError:
            return False

    result["term_sent"] = send(signal.SIGTERM)
    deadline = time.monotonic() + grace_seconds
    try:
        child.wait(timeout=grace_seconds)
        result["reaped"] = True
    except subprocess.TimeoutExpired:
        pass
    # The direct child may exit while a grandchild ignores TERM. Retain its own
    # group's grace period, then remove only that group with KILL.
    if group is not None:
        while time.monotonic() < deadline:
            try:
                os.killpg(group, 0)
            except ProcessLookupError:
                return result
            time.sleep(min(0.05, max(0, deadline - time.monotonic())))
    result["kill_sent"] = send(getattr(signal, "SIGKILL", signal.SIGTERM), force_kill=True)
    if not result["reaped"]:
        try:
            child.wait(timeout=grace_seconds)
            result["reaped"] = True
        except subprocess.TimeoutExpired:
            pass  # Keep interrupted evidence, never hang indefinitely on cancellation.
    return result


def run(entries, label, dry_run=False, reports_root="qa/reports", *, certification=None,
        certify_again=None, stop_first_failure=False, heartbeat_seconds=30):
    """Returns (exit code, report directory). Existing callers need no new arguments.

    A heartbeat is a subprocess wait interval, never a test timeout or retry. Raw
    child output stays in its log; progress prints only scope, counts and timings.
    """
    from . import evidence
    if not entries:
        print(f"refusing: {label} selects no automation; there is nothing to run", flush=True)
        return 2, None
    plan = [(entry, command(entry)) for entry in entries]
    print(f"{label}: {len(plan)} automation entr{'y' if len(plan) == 1 else 'ies'}", flush=True)
    for entry, argv in plan:
        # Commands retain the legacy plan output; heartbeat never repeats child logs.
        print(f"  {entry['scope']} {entry.get('id')}: {' '.join(argv)}", flush=True)
    if dry_run:
        return 0, None

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    run_id = uuid.uuid4().hex[:12]
    report_dir = os.path.join(reports_root, f"{stamp}-{label.replace(' ', '-')}-{run_id}")
    os.makedirs(report_dir, exist_ok=False)
    env = {**os.environ, "QA_REPORT_DIR": os.path.abspath(report_dir)}
    started = time.monotonic()
    try:
        source_revision = evidence.git("rev-parse", "HEAD")
    except evidence.Refusal:
        source_revision = None  # Legacy execution can work outside a Git checkout.
    summary = {"label": label, "run_id": run_id, "source_revision": source_revision,
               "state": "running", "result": "running",
               "selected_count": len(entries), "selection_sha256": evidence.selection_digest(entries),
               "entries": [], "started_at": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    if certification is not None:
        summary["certification"] = certification
        summary["source_revision"] = certification.get("source_revision")
    summary["runner"] = {"pid": os.getpid(), "process_start": _process_start(os.getpid()),
                         "started_at": summary["started_at"]}
    results = summary["entries"]

    def progress(index, entry, entry_started):
        elapsed = time.monotonic() - started
        passed = sum(r["outcome"] == "pass" for r in results)
        identity = (summary.get("current") or {}) if (summary.get("current") or {}).get("index") == index else {}
        summary.update(elapsed_seconds=round(elapsed, 3), completed=len(results), passed=passed,
                       failed=len(results) - passed,
                       current={**identity, "scope": entry["scope"], "id": entry.get("id"), "index": index,
                                "elapsed_seconds": round(time.monotonic() - entry_started, 3)})
        _write_summary(report_dir, summary)
        print(f"  PROGRESS {label} {index}/{len(plan)} {entry['scope']} {entry.get('id')} "
              f"completed={len(results)} pass={passed} fail={len(results)-passed} "
              f"elapsed={elapsed:.1f}s entry={time.monotonic()-entry_started:.1f}s", flush=True)

    previous_term = None
    active_child = None
    if threading.current_thread() is threading.main_thread():
        previous_term = signal.getsignal(signal.SIGTERM)
        def terminated(signum, _frame):
            summary["interrupted_signal"] = signum
            raise KeyboardInterrupt
        signal.signal(signal.SIGTERM, terminated)
    try:
        for index, (entry, argv) in enumerate(plan, 1):
            entry_started = time.monotonic()
            progress(index, entry, entry_started)
            log_path = os.path.join(report_dir, f"{index:03d}-{entry['scope']}-{entry.get('id')}.log")
            with open(log_path, "w", encoding="utf-8") as log:
                child = None
                try:
                    child = subprocess.Popen(argv, env=env, stdout=log, stderr=subprocess.STDOUT,
                                             start_new_session=os.name == "posix")
                    active_child = child
                    summary["current"].update(child_pid=child.pid,
                        child_pgid=child.pid if os.name == "posix" else None,
                        child_started_at=datetime.datetime.now(datetime.timezone.utc).isoformat(), log=log_path)
                    _write_summary(report_dir, summary)  # Persist identity immediately after spawn.
                    summary["current"]["process_start"] = _process_start(child.pid)
                    _write_summary(report_dir, summary)
                    while True:
                        try:
                            code = child.wait(timeout=heartbeat_seconds)
                            break
                        except subprocess.TimeoutExpired:
                            progress(index, entry, entry_started)
                except FileNotFoundError:
                    log.write(f"tool not found on PATH: {argv[0]}\n")
                    code = 127
                except KeyboardInterrupt:
                    if child is not None:
                        summary["cancellation"] = _cancel_process_tree(child)
                        active_child = None
                    raise
            outcome = "pass" if code == 0 else ("tool-missing" if code == 127 else "fail")
            print(f"  {outcome.upper():12} {entry['scope']} {entry.get('id')}", flush=True)
            results.append({**entry, "argv": argv, "exit_code": code, "outcome": outcome,
                            "log": log_path, "log_sha256": evidence.file_digest(log_path),
                            "elapsed_seconds": round(time.monotonic() - entry_started, 3)})
            progress(index, entry, entry_started)
            active_child = None
            if stop_first_failure and code != 0:
                break
        passed = len(results) == len(entries) and all(r["outcome"] == "pass" for r in results)
        summary.update(state="complete", result="pass" if passed else "fail", current=None,
                       finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
        if certification is not None:
            if certify_again is None:
                raise evidence.Refusal("reusable evidence requires a final source/context check")
            if not evidence.unchanged(certification, certify_again()):
                raise evidence.Refusal("certified source or context changed during the run")
        _write_summary(report_dir, summary)
        if certification is not None and passed:
            evidence.seal(report_dir)
    except (KeyboardInterrupt, evidence.Refusal, OSError):
        if active_child is not None:
            summary["cancellation"] = _cancel_process_tree(active_child)
        summary.update(state="interrupted", result="interrupted",
                       elapsed_seconds=round(time.monotonic() - started, 3))
        # An interrupted or invalidated run has no reusable certification receipt.
        _write_summary(report_dir, summary)
        raise
    finally:
        if previous_term is not None:
            signal.signal(signal.SIGTERM, previous_term)
    print(f"{'PASS' if passed else 'FAIL'}: report in {report_dir}", flush=True)
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
