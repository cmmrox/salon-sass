"""Live GitHub merge gates and conservative cleanup of explicitly named Git branches.

No command merges a PR or deploys. Cleanup previews by default, preflights every target,
archives ignored QA evidence outside worktrees, and never discards unique work.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

import yaml

from . import flow, schema as S


class Refusal(Exception):
    pass


def run(*args, cwd=None, check=True):
    result = subprocess.run(args, cwd=cwd, text=True, capture_output=True)
    if check and result.returncode:
        raise Refusal(f"{args[0]} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result


def repository():
    url = run("git", "remote", "get-url", "origin").stdout.strip()
    match = re.fullmatch(r"(?:https://github\.com/|git@github\.com:)([\w.-]+/[\w.-]+?)(?:\.git)?", url)
    if not match:
        raise Refusal("origin must identify the owner's GitHub repository")
    return match.group(1)


def remote_main():
    repository()
    run("git", "fetch", "origin", "+refs/heads/main:refs/remotes/origin/main")
    return run("git", "rev-parse", "refs/remotes/origin/main").stdout.strip()


def ancestor(commit, target):
    result = run("git", "merge-base", "--is-ancestor", commit, target, check=False)
    if result.returncode not in (0, 1):
        raise Refusal("cannot prove commit ancestry")
    return result.returncode == 0


def feature(bundle, fid):
    matches = [f for f in bundle["features"] if f.get("id") == fid]
    if len(matches) != 1:
        raise Refusal(f"no unique feature.yaml declares id: {fid}")
    return matches[0]


def qa_unchanged(scope, target):
    revision = (scope.get("integration") or {}).get("qa_revision")
    if not S.COMMIT_RE.fullmatch(str(revision or "")) or not ancestor(revision, target):
        raise Refusal("QA revision is missing or is not an ancestor of the proposed revision")
    directory = scope["_dir"]
    criteria = (f"{directory}/cases.md", f":(glob){directory}/stages/*/spec.md",
                f":(glob){directory}/stages/*/cases.md")
    changed = run("git", "diff", "--name-only", revision, target, "--", *S.FEATURE_QA_PATHS, *criteria).stdout
    if changed.strip():
        raise Refusal("product, automation or pipeline changed since feature QA; re-verify the feature")


def pr_check(bundle, fid):
    scope = feature(bundle, fid)
    if scope.get("status") != "complete" or scope.get("qa_status") != "pass":
        raise Refusal("a feature PR requires a closed feature with passing independent QA")
    branch = run("git", "branch", "--show-current").stdout.strip()
    if branch == "main" or branch != (scope.get("integration") or {}).get("branch"):
        raise Refusal("submit from the feature's recorded integration branch")
    if run("git", "status", "--porcelain").stdout.strip():
        raise Refusal("commit the reviewed changes before submitting the feature PR")
    main = remote_main()
    if not ancestor(main, "HEAD"):
        raise Refusal("update the feature branch from origin/main and re-verify before submitting")
    qa_unchanged(scope, "HEAD")
    return scope


def ci_passed(repo, head):
    checks = json.loads(run("gh", "api", f"repos/{repo}/commits/{head}/check-runs?per_page=100").stdout)
    matches = [c for c in checks.get("check_runs", [])
               if c.get("name") == "Verification commands" and c.get("head_sha") == head]
    latest = max(matches, key=lambda c: c.get("id", 0), default={})
    if latest.get("status") != "completed" or latest.get("conclusion") != "success":
        raise Refusal("the feature PR head must have successful Verification commands CI")


def merged_pr(scope, main):
    integration = scope.get("integration") or {}
    if scope.get("status") not in S.CLAIMED or scope.get("qa_status") != "pass":
        raise Refusal(f"{scope.get('id')}: finish independent QA and feature closure before its owner PR merge")
    match = S.PR_URL_RE.fullmatch(str(integration.get("pull_request") or ""))
    repo = repository()
    if not match or match.group(1) != repo:
        raise Refusal("feature PR must belong to origin's GitHub repository")
    snapshot = json.loads(run("gh", "api", f"repos/{repo}/pulls/{match.group(2)}").stdout)
    if (snapshot.get("base", {}).get("ref") != "main" or
            snapshot.get("base", {}).get("repo", {}).get("full_name") != repo or
            snapshot.get("head", {}).get("ref") != integration.get("branch")):
        raise Refusal("PR repository or branches do not match the recorded feature")
    commit = snapshot.get("merge_commit_sha")
    if (not snapshot.get("merged") or not snapshot.get("merged_at") or
            not S.COMMIT_RE.fullmatch(str(commit or ""))):
        raise Refusal("waiting for the owner to merge the feature PR")
    if not ancestor(commit, main):
        raise Refusal("GitHub merge commit is not included in origin/main")
    head = snapshot.get("head", {}).get("sha")
    if not S.COMMIT_RE.fullmatch(str(head or "")) or not ancestor(head, commit):
        raise Refusal("use a merge commit so the tested feature history stays attributable")
    qa_unchanged(scope, head)
    ci_passed(repo, head)
    return {**integration, "merged_commit": commit, "merged_at": snapshot["merged_at"]}


def merge_sync(bundle, fid, check=False):
    scope = feature(bundle, fid)
    record = merged_pr(scope, remote_main())
    if not check:
        if run("git", "branch", "--show-current").stdout.strip() in ("", "main"):
            raise Refusal("record merge bookkeeping on a branch; do not edit main directly")
        path = scope["_path"]
        if run("git", "status", "--porcelain", "--", path).stdout.strip():
            raise Refusal("feature.yaml has uncommitted work; preserve it before recording the merge")
        data = yaml.safe_load(Path(path).read_text())
        data["integration"] = record
        Path(path).write_text(yaml.safe_dump(data, sort_keys=False, allow_unicode=True))
    return record


def start_check(bundle, fid):
    scope = feature(bundle, fid)
    main = remote_main()
    for previous in flow.predecessors(scope, bundle["features"]):
        # A recorded owner exception may waive portfolio order, never a dependency's merge.
        if scope.get("started_early") and previous.get("id") not in (scope.get("depends_on") or []):
            continue
        record = merged_pr(previous, main)
        if any((previous.get("integration") or {}).get(k) != record[k]
               for k in ("merged_commit", "merged_at")):
            raise Refusal(f"run merge-sync {previous['id']} to record the confirmed merge first")
    earlier = [f for f in bundle["features"] if f.get("milestone", "") < scope.get("milestone", "")
               and f.get("status") != "released"]
    if earlier and not scope.get("started_early"):
        raise Refusal("the earlier milestone must be released before this feature starts")
    if not ancestor(main, "HEAD"):
        raise Refusal("start from the latest origin/main")
    return scope


def worktrees():
    records = []
    for block in run("git", "worktree", "list", "--porcelain").stdout.strip().split("\n\n"):
        record = dict(line.split(" ", 1) if " " in line else (line, True) for line in block.splitlines())
        if "worktree" in record:
            records.append(record)
    return records


def tree_digest(path):
    digest = hashlib.sha256()
    for child in sorted(Path(path).rglob("*")):
        if child.is_symlink():
            value = os.readlink(child).encode()
        elif child.is_file():
            value = child.read_bytes()
        else:
            continue
        digest.update(str(child.relative_to(path)).encode() + b"\0" + value)
    return digest.hexdigest()


def check_worktree(record):
    path = record["worktree"]
    if record.get("locked") or record.get("prunable"):
        raise Refusal(f"worktree is locked or unavailable: {path}")
    if Path(path).resolve() == Path.cwd().resolve():
        raise Refusal("switch to a retained checkout before removing this worktree")
    if run("git", "status", "--porcelain", "--untracked-files=all", cwd=path).stdout.strip():
        raise Refusal(f"worktree has uncommitted or untracked work: {path}")
    if list(Path(path).glob(".local/run/*.pid")):
        raise Refusal(f"stop the worktree's local services before cleanup: {path}")
    # Build caches can be regenerated; QA evidence is archived. Other ignored files
    # (credentials, local notes, environments) must be preserved by their owner first.
    ignored = run("git", "ls-files", "--others", "--ignored", "--exclude-standard", "-z", cwd=path).stdout
    cache_parts = {"node_modules", "target", ".next", "__pycache__", ".venv", ".pnpm-store"}
    evidence_parts = ("qa/reports/", "qa/test-results/", "qa/playwright-report/")
    for name in filter(None, ignored.split("\0")):
        if (name.startswith(evidence_parts) or cache_parts.intersection(Path(name).parts) or
                name.endswith((".pyc", ".tsbuildinfo")) or name == ".DS_Store"):
            continue
        raise Refusal(f"preserve ignored file before cleanup: {path}/{name}")


def cleanup_plan(branches, main):
    if not branches or len(branches) != len(set(branches)):
        raise Refusal("name each related branch explicitly, once")
    trees = worktrees()
    current = run("git", "branch", "--show-current").stdout.strip()
    plan = []
    for branch in branches:
        if branch == "main" or branch == current or branch.startswith("-"):
            raise Refusal("cannot delete main or the current branch")
        run("git", "check-ref-format", "--branch", branch)
        local = run("git", "rev-parse", "--verify", f"refs/heads/{branch}", check=False)
        local_sha = local.stdout.strip() if local.returncode == 0 else None
        remote = run("git", "ls-remote", "--heads", "origin", f"refs/heads/{branch}").stdout.strip()
        remote_sha = remote.split()[0] if remote else None
        if not local_sha and not remote_sha:
            raise Refusal(f"branch does not exist: {branch}")
        for commit in filter(None, (local_sha, remote_sha)):
            if not ancestor(commit, main):
                raise Refusal(f"branch contains commits not merged into origin/main: {branch}")
        related = [t for t in trees if t.get("branch") == f"refs/heads/{branch}"]
        for tree in related:
            check_worktree(tree)
        plan.append({"branch": branch, "local_sha": local_sha, "remote_sha": remote_sha, "worktrees": related})
    return plan


def archive_evidence(record, archive):
    source = Path(record["worktree"])
    evidence = [source / "qa" / name for name in ("reports", "test-results", "playwright-report")
                if (source / "qa" / name).exists()]
    if not evidence:
        return None
    destination = Path(tempfile.mkdtemp(prefix="qa-", dir=archive))
    for path in evidence:
        copied = destination / path.name
        shutil.copytree(path, copied, symlinks=True)
        if tree_digest(path) != tree_digest(copied):
            raise Refusal("QA evidence archive did not verify; nothing may be removed")
    (destination / "receipt.json").write_text(json.dumps({
        "source_worktree": record["worktree"], "branch": record.get("branch"),
        "revision": record.get("HEAD"), "evidence": [p.name for p in evidence],
    }, indent=2))
    return str(destination)


def check_archived_evidence(record, archive):
    source = Path(record["worktree"]) / "qa"
    for name in ("reports", "test-results", "playwright-report"):
        original = source / name
        copied = Path(archive) / name if archive else None
        if original.exists() != bool(copied and copied.exists()):
            raise Refusal("QA evidence changed after archival; keep the worktree and rerun cleanup")
        if original.exists() and tree_digest(original) != tree_digest(copied):
            raise Refusal("QA evidence changed after archival; keep the worktree and rerun cleanup")


def cleanup(branches, apply=False):
    main = remote_main()
    if not ancestor(main, "HEAD"):
        raise Refusal("update the retained checkout from origin/main before cleanup")
    plan = cleanup_plan(branches, main)
    if not apply:
        return plan
    common = Path(run("git", "rev-parse", "--git-common-dir").stdout.strip()).resolve()
    archive = common / "sdlc-evidence"
    archive.mkdir(exist_ok=True)
    # Archive all evidence before the first deletion; failures leave every target intact.
    for item in plan:
        item["archives"] = [archive_evidence(tree, archive) for tree in item["worktrees"]]
    (archive / "last-cleanup.json").write_text(json.dumps(plan, indent=2))
    # Recheck the whole plan to catch new work or changed remote branches before deletion.
    if cleanup_plan(branches, main) != [{k: v for k, v in item.items() if k != "archives"} for item in plan]:
        raise Refusal("cleanup targets changed during preflight; nothing was removed")
    for item in plan:
        for tree, saved in zip(item["worktrees"], item["archives"]):
            check_worktree(tree)
            check_archived_evidence(tree, saved)
            run("git", "worktree", "remove", tree["worktree"])
        if item["local_sha"]:
            # Verify against main, then delete without forcing or relying on an upstream ref.
            if run("git", "rev-parse", f"refs/heads/{item['branch']}").stdout.strip() != item["local_sha"]:
                raise Refusal("local branch changed during cleanup")
            # A stale upstream can lag a closed stage even though main includes it.
            # Override it for this command only, so -d checks the verified main ref.
            run("git", "-c", f"branch.{item['branch']}.remote=.",
                "-c", f"branch.{item['branch']}.merge=refs/remotes/origin/main",
                "branch", "-d", item["branch"])
        if item["remote_sha"]:
            # A lease protects concurrent pushes. This deletes one ref; no history is rewritten.
            run("git", "push", "origin", f"--force-with-lease=refs/heads/{item['branch']}:{item['remote_sha']}",
                f":refs/heads/{item['branch']}")
        if run("git", "rev-parse", "--verify", f"refs/heads/{item['branch']}", check=False).returncode == 0:
            raise Refusal("local branch still exists after cleanup")
        if run("git", "ls-remote", "--heads", "origin", f"refs/heads/{item['branch']}").stdout.strip():
            raise Refusal("remote branch still exists or was recreated; no further deletion attempted")
        if any(t.get("branch") == f"refs/heads/{item['branch']}" for t in worktrees()):
            raise Refusal("worktree still exists after cleanup")
    return plan
