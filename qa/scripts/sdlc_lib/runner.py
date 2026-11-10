"""Selects and runs QA automation by scope.

This is the only sanctioned way to run QA automation, because the scope rule is the
point: a stage run executes that stage's automation and nothing else, a feature run
executes that feature's, and a suite run (smoke, regression, release) executes the closed
work tagged with it. Selection is pure and tested; execution is a thin loop over subprocesses.
"""
import datetime
import hashlib
import json
import os
import signal
import subprocess
import urllib.parse
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


def _reference(entry):
    return f"{entry.get('scope')}:{entry.get('id')}"


def failed_in(report_dir):
    """The 'scope:id' of every entry that did not pass in an earlier run's summary.json."""
    path = os.path.join(report_dir, "summary.json")
    try:
        with open(path, encoding="utf-8") as handle:
            summary = json.load(handle)
    except (OSError, ValueError) as error:
        raise SelectionError(f"cannot read {path}: {error}")
    return [_reference(r) for r in summary.get("entries") or [] if r.get("outcome") != "pass"]


def narrow(entries, names=(), rerun_report=None):
    """The entries of one scope's selection that were asked for by name or failed in an earlier run.

    A name is an entry id ('catalogue-api') or, where ids repeat across a feature's stages,
    'scope:id' ('F05-S01:catalogue-api'). Narrowing never reaches outside the chosen scope."""
    wanted = list(names or ())
    if rerun_report:
        failed = failed_in(rerun_report)
        if not failed:
            raise SelectionError(f"{rerun_report} records no failed entry; there is nothing to run again")
        wanted += failed
    chosen = []
    for name in wanted:
        match = [e for e in entries if name in (_reference(e), e.get("id"))]
        if not match:
            known = ", ".join(_reference(e) for e in entries) or "none"
            raise SelectionError(f"'{name}' is not an automation entry of this scope (entries: {known})")
        if len(match) > 1 and ":" not in name:
            raise SelectionError(f"'{name}' names entries in {len(match)} scopes; use "
                                 + " or ".join(_reference(e) for e in match))
        chosen += [e for e in match if e not in chosen]
    return [e for e in entries if e in chosen]  # keep the scope's own order


def time_limit_seconds(entry):
    """How long an entry may run: its own timeout_minutes, or the runner's default."""
    minutes = entry.get("timeout_minutes")
    valid = isinstance(minutes, (int, float)) and not isinstance(minutes, bool) and minutes > 0
    return (minutes if valid else S.ENTRY_TIME_LIMIT_MINUTES) * 60


def budget_check(suite, elapsed_seconds, partial=False):
    """summary.json's 'budget' for a full run of a suite that has one, else None."""
    if partial or suite not in S.SUITE_BUDGET_MINUTES:
        return None
    minutes = S.SUITE_BUDGET_MINUTES[suite]
    elapsed = round(elapsed_seconds / 60, 1)
    return {"suite": suite, "minutes": minutes, "elapsed_minutes": elapsed,
            "exceeded": elapsed_seconds > minutes * 60}


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
    """Stops an entry's whole process group: on cancellation, or when it reaches its time limit."""
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


def _file_digest(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def _revision():
    """The tested commit, so a pass can be compared with later changes (feature-close)."""
    try:
        found = subprocess.run(["git", "rev-parse", "HEAD"], capture_output=True, text=True)
        return found.stdout.strip() if found.returncode == 0 else None
    except OSError:
        return None


SOURCE_CHANGES_LISTED = 200


def _source_state():
    """Whether the checkout differed from HEAD when the run started, and how.

    A pass is evidence for source_revision only when source_dirty is false; otherwise
    source_changes names what was uncommitted (git status --porcelain lines, untracked files
    included, ignored ones such as qa/reports not). None when Git cannot say."""
    try:
        found = subprocess.run(["git", "status", "--porcelain", "--untracked-files=all"],
                               capture_output=True, text=True)
    except OSError:
        return {"source_dirty": None, "source_changes": None}
    if found.returncode != 0:
        return {"source_dirty": None, "source_changes": None}
    changes = [line for line in found.stdout.splitlines() if line.strip()]
    state = {"source_dirty": bool(changes), "source_changes": changes[:SOURCE_CHANGES_LISTED]}
    if len(changes) > SOURCE_CHANGES_LISTED:
        state["source_changes_omitted"] = len(changes) - SOURCE_CHANGES_LISTED
    return state


# Decision 105: automated QA runs only against the QA instance; the default instance holds the
# owner's manual testing. qa/shared/env.ts and qa/shared/checks.sh refuse it too; this refusal
# stops a run before any child starts.
DEFAULT_INSTANCE_PORTS = (8090, 3100, 5440)
_PORT_SETTINGS = ("SALON_API_PORT", "SALON_WEB_PORT", "SALON_POSTGRES_PORT")
_URL_SETTINGS = ("QA_API_URL", "QA_WEB_URL")


def _env_file(path):
    """KEY=VALUE lines of a dotenv file (first value wins, as infra/scripts/lib/common.sh reads it)."""
    values = {}
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                values.setdefault(key.strip(), value.strip().strip('"'))
    except OSError:
        pass
    return values


def default_instance_ports(environ):
    """The ports the default instance uses: its documented defaults plus any the product's .env sets.

    The product checkout is QA_PRODUCT_ROOT (shell, then qa/.env.local), else this repository,
    the same rule qa/shared/env.ts follows."""
    product_root = (environ.get("QA_PRODUCT_ROOT") or _env_file("qa/.env.local").get("QA_PRODUCT_ROOT") or ".")
    configured = _env_file(os.path.join(product_root, ".env"))
    ports = list(DEFAULT_INSTANCE_PORTS)
    for name in _PORT_SETTINGS:
        value = configured.get(name, "")
        if value.isdigit() and int(value) not in ports:
            ports.append(int(value))
    return tuple(ports)


def default_instance_refusal(environ):
    """Why this environment would aim QA at the default instance, or None."""
    instance = environ.get("SALON_LOCAL_INSTANCE")
    if instance is not None and instance != "qa":
        return (f"automated QA runs only against the QA instance (SALON_LOCAL_INSTANCE=qa), "
                f"not '{instance or 'default'}'")
    refused_ports = default_instance_ports(environ)
    aimed = []
    for name in _PORT_SETTINGS:
        value = environ.get(name, "").strip()
        if value.isdigit() and int(value) in refused_ports:
            aimed.append(f"{name} is {value}")
    for name in _URL_SETTINGS:
        value = environ.get(name, "").strip()
        if value:
            parsed = urllib.parse.urlsplit(value)
            try:
                port = parsed.port or (443 if parsed.scheme == "https" else 80)
            except ValueError:
                continue
            if port in refused_ports:
                aimed.append(f"{name} is {value}")
    if aimed:
        return (f"{', '.join(aimed)}: a port of the default instance "
                f"({', '.join(map(str, refused_ports))}), which holds the owner's manual testing")
    return None


def run(entries, label, dry_run=False, reports_root="qa/reports", *, heartbeat_seconds=30,
        suite=None, selection=None):
    """Returns (exit code, report directory).

    A heartbeat is a subprocess wait interval, never a retry. Each entry has a time limit
    (time_limit_seconds); one that reaches it is stopped and recorded as 'timeout', a failure,
    and the run goes on to the next entry. Raw child output stays in its log; progress prints
    only scope, counts and timings. suite names the suite being run, whose budget is then
    checked; selection describes a narrowed run (--entry, --rerun-failed), which is recorded as
    partial and is not held to the suite's budget.
    """
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
    refusal = default_instance_refusal(os.environ)
    if refusal:
        print(f"refusing: {refusal} (decision 105). Nothing was run.", flush=True)
        return 2, None

    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    run_id = uuid.uuid4().hex[:12]
    report_dir = os.path.join(reports_root, f"{stamp}-{label.replace(' ', '-')}-{run_id}")
    os.makedirs(report_dir, exist_ok=False)
    env = {**os.environ, "QA_REPORT_DIR": os.path.abspath(report_dir), "SALON_LOCAL_INSTANCE": "qa"}
    started = time.monotonic()
    summary = {"label": label, "run_id": run_id, "source_revision": _revision(), **_source_state(),
               "state": "running", "result": "running", "selected_count": len(entries),
               "entries": [], "started_at": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    if summary["source_dirty"]:
        print(f"WARNING: the checkout has {len(summary['source_changes'])} uncommitted change(s); "
              "summary.json lists them, and this run is not evidence for source_revision alone", flush=True)
    summary["runner"] = {"pid": os.getpid(), "process_start": _process_start(os.getpid()),
                         "started_at": summary["started_at"]}
    # qa/shared/global-setup.ts refuses a build of another commit unless this is set.
    summary["build_mismatch_allowed"] = os.environ.get("QA_ALLOW_BUILD_MISMATCH") == "1"
    summary["partial"] = bool(selection)
    if selection:
        summary["selection"] = selection
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
            limit = time_limit_seconds(entry)
            timed_out = None
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
                        remaining = limit - (time.monotonic() - entry_started)
                        if remaining <= 0:
                            timed_out = _cancel_process_tree(child)
                            code = child.returncode if child.returncode is not None else -signal.SIGTERM
                            log.write(f"\nsdlc run: stopped after reaching the entry's time limit of "
                                      f"{limit / 60:g} minutes\n")
                            break
                        try:
                            code = child.wait(timeout=min(heartbeat_seconds, remaining))
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
            if timed_out is not None:
                outcome = "timeout"
            else:
                outcome = "pass" if code == 0 else ("tool-missing" if code == 127 else "fail")
            print(f"  {outcome.upper():12} {entry['scope']} {entry.get('id')}"
                  + (f" (stopped at its {limit / 60:g}-minute limit)" if timed_out is not None else ""), flush=True)
            result = {**entry, "argv": argv, "exit_code": code, "outcome": outcome,
                      "log": log_path, "log_sha256": _file_digest(log_path),
                      "elapsed_seconds": round(time.monotonic() - entry_started, 3),
                      "time_limit_seconds": limit}
            if timed_out is not None:
                result["cancellation"] = timed_out
            results.append(result)
            progress(index, entry, entry_started)
            active_child = None
        passed = len(results) == len(entries) and all(r["outcome"] == "pass" for r in results)
        summary.update(state="complete", result="pass" if passed else "fail", current=None,
                       elapsed_seconds=round(time.monotonic() - started, 3),
                       finished_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
        budget = budget_check(suite, summary["elapsed_seconds"], partial=bool(selection))
        if budget:
            summary["budget"] = budget
            if budget["exceeded"]:
                print(f"WARNING: {suite} took {budget['elapsed_minutes']:g} minutes, over its budget of "
                      f"{budget['minutes']} minutes (docs/qa/test-plan.md, \"Suites\"): going over budget "
                      "is a defect in the suite", flush=True)
        _write_summary(report_dir, summary)
    except (KeyboardInterrupt, OSError):
        if active_child is not None:
            summary["cancellation"] = _cancel_process_tree(active_child)
        summary.update(state="interrupted", result="interrupted",
                       elapsed_seconds=round(time.monotonic() - started, 3))
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
            code, report = run(select(bundle, suite=gate["suite"]), f"{version} {gate['suite']}", dry_run,
                               suite=gate["suite"])
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
