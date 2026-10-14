"""Local, fail-closed evidence for reusing gates without rerunning product checks.

Context is supplied by the operator; this module neither prepares environments nor
claims that a context description proves runtime readiness. Hashes are integrity
checks, not a signature or a substitute for independent QA.
"""
import datetime
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess

import yaml

from . import runner, schema as S


class Refusal(ValueError):
    pass


VERSION = 1
METADATA = {"status", "qa_status", "blocked_reason", "evidence", "integration",
            "previous_integration", "released_in", "reopened_by"}


def _json_default(value):
    if isinstance(value, (datetime.date, datetime.datetime)):
        return {"yaml_type": type(value).__name__, "iso": value.isoformat()}
    raise Refusal("certified definitions contain an unsupported value type")


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                    default=_json_default).encode()).hexdigest()


def file_digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def git(*args):
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    if result.returncode:
        raise Refusal("local Git source identity is unavailable")
    return result.stdout.strip()


def local_file(name):
    root = Path(git("rev-parse", "--show-toplevel")).resolve()
    path = Path(name)
    resolved = path.resolve()
    if not resolved.is_relative_to(root) or not resolved.is_file():
        raise Refusal("required workspace-local context or certified file is missing")
    return resolved.relative_to(root).as_posix()


def context_fingerprint(name):
    name = local_file(name)
    try:
        data = json.loads(Path(name).read_text())
    except (ValueError, OSError):
        raise Refusal("context must be a workspace-local JSON object") from None
    def nonempty_string(value):
        return isinstance(value, str) and bool(value.strip())

    def string_list(value):
        return isinstance(value, list) and bool(value) and all(nonempty_string(v) for v in value)

    if not isinstance(data, dict):
        raise Refusal("context must be a JSON object")
    targets = data.get("targets")
    valid_targets = string_list(targets) or (isinstance(targets, dict) and bool(targets) and
                    all(nonempty_string(k) and nonempty_string(v) for k, v in targets.items()))
    if not (nonempty_string(data.get("environment")) and nonempty_string(data.get("fixture_identity")) and
            string_list(data.get("profiles")) and valid_targets):
        raise Refusal("context requires environment and fixture_identity strings, profiles string list "
                      "and targets string list or string mapping")
    configs, variables = data.get("config_files", []), data.get("environment_variables", [])
    if (not isinstance(configs, list) or not isinstance(variables, list) or
            any(not nonempty_string(n) for n in configs + variables)):
        raise Refusal("context config_files and environment_variables must be lists of names")
    files = {}
    for item in configs:
        if Path(item).is_absolute():
            raise Refusal("context config_files must use workspace-relative paths")
        validated = local_file(item)
        declared = Path(item).lstat()
        target = Path(validated).stat()
        files[validated] = {"sha256": file_digest(validated), "type": stat.S_IFMT(declared.st_mode),
                            "permissions": stat.S_IMODE(declared.st_mode),
                            "target_permissions": stat.S_IMODE(target.st_mode),
                            "symlink_target": os.readlink(item) if stat.S_ISLNK(declared.st_mode) else None}
    values = {}
    for item in variables:
        values[item] = {"present": item in os.environ, "value": os.environ.get(item)}
    # Never persist the JSON contents, config hashes or individual environment hashes.
    return {"path": name, "config_files": sorted(files), "environment_variables": sorted(values),
            "sha256": digest({"bytes": file_digest(name), "configs": files, "variables": values})}


def feature_scopes(bundle, fid):
    matches = [f for f in bundle["features"] if f.get("id") == fid]
    if len(matches) != 1:
        raise Refusal(f"no unique feature.yaml declares id: {fid}")
    feature = matches[0]
    return feature, runner._stages_of(bundle, feature)


def gates(bundle, fid):
    _, stages = feature_scopes(bundle, fid)
    found = []
    for scope in stages:
        for cmd in (scope.get("verification") or {}).get("commands") or []:
            if not isinstance(cmd, str) or not cmd.strip():
                raise Refusal("verification.commands must contain nonempty command strings")
            if cmd not in found:
                found.append(cmd)
    if not found:
        raise Refusal("feature selects no developer verification commands")
    return [{"scope": fid, "id": f"command-{index}", "tool": "command", "cmd": cmd}
            for index, cmd in enumerate(found, 1)]


def definition(entry):
    return {k: v for k, v in entry.items() if k not in {"exit_code", "outcome", "log", "log_sha256",
            "elapsed_seconds", "started_at", "finished_at", "argv"}}


def selection_digest(entries):
    return digest([{"entry": definition(e), "argv": runner.command(e)} for e in entries])


def _substance(raw):
    try:
        data = yaml.safe_load(raw)
    except yaml.YAMLError:
        raise Refusal("certified feature/stage YAML is invalid") from None
    if not isinstance(data, dict):
        raise Refusal("certified feature/stage YAML must be a mapping")
    substance = {key: value for key, value in data.items() if key not in METADATA}
    if isinstance(substance.get("handoff"), dict):
        substance["handoff"] = {key: value for key, value in substance["handoff"].items()
                               if key not in {"timestamp", "timestamps", "checked_at", "verified_at",
                                              "built_at", "completed_at"}}
    return substance


def file_identity(name, committed_mode):
    """Bind Git's mode/type and the actual file, even with core.filemode=false."""
    local_file(name)
    path = Path(name)
    actual = path.lstat()
    kind = "symlink" if stat.S_ISLNK(actual.st_mode) else "regular" if stat.S_ISREG(actual.st_mode) else "other"
    expected = "symlink" if committed_mode == "120000" else "regular"
    executable = bool(actual.st_mode & stat.S_IXUSR)
    if kind != expected or (kind == "regular" and executable != (committed_mode == "100755")):
        raise Refusal("certified file type or executable mode has uncommitted changes")
    return {"git_mode": committed_mode, "type": kind, "permissions": stat.S_IMODE(actual.st_mode),
            "symlink_target": os.readlink(path) if kind == "symlink" else None,
            "sha256": file_digest(path)}


def source_fingerprint(bundle, fid):
    feature, stages = feature_scopes(bundle, fid)
    directory = feature["_dir"]
    criteria = [f"{directory}/README.md", f"{directory}/cases.md", f"{directory}/feature.yaml",
                f":(glob){directory}/stages/*/spec.md", f":(glob){directory}/stages/*/cases.md",
                f":(glob){directory}/stages/*/stage.yaml", "docs/brd/requirements.yaml"]
    required_all = ["docs/brd/requirements.yaml"]
    local_file(required_all[0])
    for scope in [feature] + stages:
        criteria += (scope.get("_spec") or {}).get("read_first") or []
        required = [scope["_path"]]
        if scope is feature:
            required.append(f"{scope['_dir']}/README.md")
        else:
            required.append(f"{scope['_dir']}/spec.md")
        if scope.get("cases"):
            required.append(f"{scope['_dir']}/cases.md")
        required += [e.get("file") for e in runner._entries(scope)]
        required += (scope.get("_spec") or {}).get("read_first") or []
        required_all += required
        criteria += required
        for name in required:
            if not name:
                raise Refusal("certified automation file is missing")
            local_file(name)
    paths = git("ls-files", "--", *S.FEATURE_QA_PATHS, *criteria).splitlines()
    if not paths or any(name not in paths for name in required_all):
        raise Refusal("certified files must exist in committed source")
    metadata_paths = {scope["_path"] for scope in [feature] + stages}
    static_paths = [name for name in paths if name not in metadata_paths]
    # A dirty YAML is allowed only when
    # its normalized substance still equals its committed version.
    if git("status", "--porcelain", "--untracked-files=all", "--", *S.FEATURE_QA_PATHS,
           *criteria, *[f":(exclude){p}" for p in metadata_paths]):
        raise Refusal("certified source has uncommitted or untracked changes; commit before certification")
    tree = git("ls-tree", "-r", "HEAD", "--", *paths)
    modes = {}
    for row in tree.splitlines():
        header, name = row.split("\t", 1)
        modes[name] = header.split()[0]
    if any(name not in modes for name in paths):
        raise Refusal("certified files must exist in committed source")
    values = {name: file_identity(name, modes[name]) for name in static_paths}
    for name in metadata_paths:
        current = _substance(Path(name).read_text())
        committed = _substance(git("show", f"HEAD:{name}"))
        if current != committed:
            raise Refusal("certified cases or commands have uncommitted changes; commit before certification")
        values[name] = {**file_identity(name, modes[name]), "sha256": digest(current)}
    return {"sha256": digest(values), "paths": sorted(values)}


def certification(bundle, fid, context, kind):
    _, stages = feature_scopes(bundle, fid)
    if kind == "full-feature-qa" and (not stages or any(s.get("status") not in S.CLAIMED for s in stages)):
        raise Refusal("reusable full-feature QA requires all stages already closed")
    return {"version": VERSION, "kind": kind, "feature": fid,
            "source_revision": git("rev-parse", "HEAD"), "source": source_fingerprint(bundle, fid),
            "context": context_fingerprint(context)}


def unchanged(before, after):
    return all(before.get(k) == after.get(k) for k in ("version", "kind", "feature", "source", "context"))


def seal(report):
    """Local receipt detects edited summaries as well as edited logs."""
    summary = Path(report) / "summary.json"
    common = Path(git("rev-parse", "--git-common-dir"))
    receipts = common / "sdlc-certifications"
    receipts.mkdir(exist_ok=True)
    key = digest(str(summary.resolve()))
    (receipts / f"{key}.json").write_text(json.dumps({"sha256": file_digest(summary)}))


def proof(report, expected, entries):
    path = Path(local_file(str(Path(report) / "summary.json")))
    try:
        summary = json.loads(path.read_text())
        receipt = Path(git("rev-parse", "--git-common-dir")) / "sdlc-certifications" / f"{digest(str(path.resolve()))}.json"
        if json.loads(receipt.read_text()).get("sha256") != file_digest(path):
            raise Refusal("evidence summary was modified; run the relevant scope again")
        saved = summary.get("certification") or {}
        revision = saved.get("source_revision") or ""
        if (not S.COMMIT_RE.fullmatch(revision) or subprocess.run(
                ["git", "merge-base", "--is-ancestor", revision, "HEAD"], capture_output=True).returncode):
            raise Refusal("evidence source revision is not an ancestor of current source")
        if summary.get("source_revision") != revision:
            raise Refusal("evidence source identity is inconsistent; run the relevant scope again")
        if not unchanged(saved, expected):
            raise Refusal("source, cases, commands or context changed; run the relevant scope again")
        if (summary.get("result") != "pass" or summary.get("state") != "complete" or
                summary.get("selection_sha256") != selection_digest(entries) or
                summary.get("selected_count") != len(entries) or len(summary.get("entries") or []) != len(entries)):
            raise Refusal("evidence is partial, nonpassing or does not match the exact scope")
        for row, entry in zip(summary["entries"], entries):
            if (definition(row) != definition(entry) or row.get("argv") != runner.command(entry) or
                    row.get("outcome") != "pass" or row.get("exit_code") != 0):
                raise Refusal("evidence entries do not match the exact passing selection")
            log = local_file(row["log"])
            if Path(log).parent.resolve() != path.parent.resolve() or row.get("log_sha256") != file_digest(log):
                raise Refusal("evidence log is missing or changed; run the relevant scope again")
        return saved
    except (OSError, ValueError, KeyError, TypeError) as error:
        if isinstance(error, Refusal):
            raise
        raise Refusal("reusable evidence is missing or invalid; run the relevant scope again") from None


def closure_check(bundle, fid, context, commands_report):
    feature, stages = feature_scopes(bundle, fid)
    if not stages or any(s.get("status") not in S.CLAIMED for s in stages):
        raise Refusal("all feature stages must be closed")
    if feature.get("qa_status") != "pass" or any(s.get("qa_status") in S.UNFINISHED_QA for s in stages):
        raise Refusal("feature requires passing independent QA with no unfinished stage pass")
    for scope in [feature] + stages:
        if any(b.get("severity") in S.BLOCKING_SEVERITIES and b.get("status") not in S.RESOLVED_BUG_STATUSES
               for b in scope.get("_bugs") or []):
            raise Refusal("an unresolved blocking bug prevents feature closure")
    if (feature.get("evidence") or {}).get("last_result") != "pass":
        raise Refusal("feature evidence must record pass")
    report = (feature.get("evidence") or {}).get("last_run")
    if not isinstance(report, str) or not report:
        raise Refusal("feature QA report is missing")
    entries = runner.select(bundle, feature=fid)
    if not entries:
        raise Refusal("feature selects no QA automation")
    expected = certification(bundle, fid, context, "full-feature-qa")
    saved = proof(report, expected, entries)
    if (feature.get("integration") or {}).get("qa_revision") != saved.get("source_revision"):
        raise Refusal("integration.qa_revision does not match the independently tested revision")
    proof(commands_report, {**expected, "kind": "developer-commands"}, gates(bundle, fid))
    return {"feature": fid, "qa_report": report, "commands_report": commands_report,
            "source_sha256": expected["source"]["sha256"], "context_sha256": expected["context"]["sha256"]}
