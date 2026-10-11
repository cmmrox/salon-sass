"""Reads the work spine from disk into one plain dict, the bundle.

All file access lives here. validate.py, generate.py and runner.py are pure functions over
the bundle, which is what lets their rules be tested with dict fixtures instead of a
directory tree.
"""
import glob
import hashlib
import os
import re

import yaml

AC_RE = re.compile(r"^\s*[-*]\s+\**(AC-\d+)\**\s*[:.]", re.M)
CASE_HEADING_RE = re.compile(r"^#{2,4}\s+(TC-[A-Za-z0-9-]+)", re.M)
H2_RE = re.compile(r"^##\s+(.+?)\s*$", re.M)

# A path to a project document, wherever it is mentioned. Placeholders such as
# docs/<area>/<name>.md do not match, because "<" is not a path character here.
DOC_PATH_RE = re.compile(r"docs/[A-Za-z0-9_./-]+\.(?:md|yaml|html|pdf)")
INDEX_ENTRY_RE = re.compile(r"`([A-Za-z0-9_./-]+\.(?:md|yaml|html|pdf))`")
DOCS_INDEX = "docs/README.md"
# Not project documents in their own right: the BRD's build sources, individual change
# requests (indexed by their folder's README), and generated API files.
DOCS_NOT_INDEXED = ("docs/brd/source/", "docs/changes/CR-", "docs/api/")
# Files whose pointers into docs/ must resolve.
POINTER_SOURCES = (
    "AGENTS.md", "*/AGENTS.md", "*/*/AGENTS.md", "README.md", "qa/README.md",
    ".claude/skills/*/SKILL.md", ".claude/skills/*/references/*.md", ".claude/skills/*/assets/*",
    ".claude/skills/*/evals/*.md", ".claude/agents/*.md", ".codex/agents/*.toml",
    "features/*/README.md", "docs/*.md", "docs/*/*.md",
)

GENERATED_DOCS = ("features/README.md", "qa/coverage.md")
SKILLS_DIR = ".claude/skills"
MIRROR_DIR = ".agents/skills"


def _text(path):
    if not os.path.isfile(path):
        return None
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def _yaml(path):
    text = _text(path)
    if text is None:
        return None
    data = yaml.safe_load(text)
    # An empty file must not read as a valid empty object: the validator reports it.
    return data if isinstance(data, dict) else {"_unreadable": True}


def frontmatter(text):
    """Returns (metadata dict, body) for a markdown file with a leading YAML block."""
    if not text or not text.startswith("---"):
        return {}, text or ""
    end = text.find("\n---", 3)
    if end == -1:
        return {}, text
    meta = yaml.safe_load(text[3:end]) or {}
    body = text[end + 4:].lstrip("\n")
    return (meta if isinstance(meta, dict) else {}), body


def _spec(text):
    read_first = re.search(r"^## Read first\s*$(.*?)(?=^## |\Z)", text, re.M | re.S)
    named = sorted(set(DOC_PATH_RE.findall(read_first.group(1)))) if read_first else []
    return {
        "headings": H2_RE.findall(text),
        "acs": AC_RE.findall(text),
        "read_first": named,
        "missing_docs": [path for path in named if not os.path.exists(path)],
        "commands": _commands(text),
    }


def _commands(text):
    """The lines of the first bash block under '## Verification commands', or None if there is none."""
    section = re.search(r"^## Verification commands\s*$(.*?)(?=^## |\Z)", text, re.M | re.S)
    block = re.search(r"```bash\n(.*?)```", section.group(1), re.S) if section else None
    if not block:
        return None
    return [line.strip() for line in block.group(1).splitlines() if line.strip() and not line.strip().startswith("#")]


SKILL_REFERENCE_RE = re.compile(r"references/([a-z0-9-]+\.md)")


def _docs():
    """The state of the project documents: the index, and every pointer into docs/."""
    index = _text(DOCS_INDEX)
    # Only the index's tables list documents; prose around them may mention other file names.
    rows = "\n".join(line for line in (index or "").splitlines() if line.startswith("|"))
    listed = {os.path.normpath(os.path.join("docs", entry)) for entry in INDEX_ENTRY_RE.findall(rows)}
    present = []
    for root, _dirs, files in os.walk("docs"):
        for name in files:
            path = os.path.join(root, name)
            if name.endswith(".md") and path != DOCS_INDEX and not path.startswith(DOCS_NOT_INDEXED):
                present.append(path)
    pointers = []
    for pattern in POINTER_SOURCES:
        for source in sorted(glob.glob(pattern)):
            if source.startswith(DOCS_NOT_INDEXED) or not os.path.isfile(source):
                continue
            text = _text(source) or ""
            for target in sorted(set(DOC_PATH_RE.findall(text))):
                if not os.path.exists(target):
                    pointers.append({"source": source, "target": target})
            # A skill reference is named without its skill ("references/x.md in salon-qa"), so
            # it resolves if any skill has it.
            for name in sorted(set(SKILL_REFERENCE_RE.findall(text))):
                if not glob.glob(os.path.join(SKILLS_DIR, "*", "references", name)):
                    pointers.append({"source": source, "target": f"references/{name}"})
    return {
        "index": index is not None,
        "unlisted": sorted(path for path in present if path not in listed),
        "dangling": sorted(path for path in listed if not os.path.exists(path)),
        "pointers": pointers,
    }


def _bugs(directory):
    bugs = []
    for path in sorted(glob.glob(os.path.join(directory, "bugs", "BUG-*.md"))):
        meta, _ = frontmatter(_text(path))
        meta["_path"] = path
        bugs.append(meta)
    return bugs


def _automation(scope):
    return ((scope.get("verification") or {}).get("automation")) or []


def _scope(directory, yaml_name):
    """Loads a feature or a stage: its yaml plus the facts about its sibling files."""
    path = os.path.join(directory, yaml_name)
    data = _yaml(path) or {"_unreadable": True}
    data["_path"] = path
    data["_dir"] = directory
    spec = _text(os.path.join(directory, "spec.md"))
    data["_spec"] = None if spec is None else _spec(spec)
    cases = _text(os.path.join(directory, "cases.md"))
    data["_cases_md"] = None if cases is None else CASE_HEADING_RE.findall(cases)
    data["_bugs"] = _bugs(directory)
    data["_has_review"] = os.path.isfile(os.path.join(directory, "review.md"))
    data["_has_readme"] = os.path.isfile(os.path.join(directory, "README.md"))
    data["_missing_files"] = sorted(
        entry["file"] for entry in _automation(data)
        if isinstance(entry, dict) and entry.get("file") and not os.path.exists(entry["file"])
    )
    return data


def _tree_digest(directory):
    """A content hash of every file under a directory, following symlinks."""
    digest = hashlib.sha256()
    for root, dirs, files in os.walk(directory, followlinks=True):
        dirs.sort()
        for name in sorted(files):
            if name == ".DS_Store":
                continue
            full = os.path.join(root, name)
            digest.update(os.path.relpath(full, directory).encode())
            with open(full, "rb") as handle:
                digest.update(handle.read())
    return digest.hexdigest()


def _skills():
    skills = []
    for directory in sorted(glob.glob(os.path.join(SKILLS_DIR, "*"))):
        if not os.path.isdir(directory):
            continue
        name = os.path.basename(directory)
        text = _text(os.path.join(directory, "SKILL.md"))
        meta, body = frontmatter(text)
        references = []
        for ref in sorted(glob.glob(os.path.join(directory, "references", "*.md"))):
            ref_text = _text(ref)
            ref_name = os.path.basename(ref)
            references.append({
                "name": ref_name,
                "lines": ref_text.count("\n") + 1,
                "has_contents": "\n## Contents" in ref_text,
                "linked": f"references/{ref_name}" in (text or ""),
            })
        mirror = os.path.join(MIRROR_DIR, name)
        if not os.path.isdir(mirror):
            mirror_state = "missing"
        elif _tree_digest(mirror) != _tree_digest(directory):
            mirror_state = "diverged"
        else:
            mirror_state = "ok"
        skills.append({
            "dir": name,
            "has_skill_md": text is not None,
            "frontmatter": meta,
            "body_lines": body.count("\n") + 1 if body else 0,
            "references": references,
            "mirror": mirror_state,
        })
    return skills


def _change_requests():
    requests = []
    for path in sorted(glob.glob("docs/changes/CR-*.md")):
        meta, _ = frontmatter(_text(path))
        meta["_path"] = path
        requests.append(meta)
    return requests


def _releases():
    releases = []
    for path in sorted(glob.glob("qa/releases/*/release.yaml")):
        data = _yaml(path) or {}
        data["_path"] = path
        releases.append(data)
    return releases


def load(root="."):
    """Builds the bundle. Paths inside it are relative to the repository root."""
    previous = os.getcwd()
    os.chdir(root)
    try:
        register = _yaml("docs/brd/requirements.yaml") or {}
        features, stages = [], []
        for feature_dir in sorted(glob.glob("features/F[0-9][0-9]-*")):
            if not os.path.isdir(feature_dir):
                continue
            features.append(_scope(feature_dir, "feature.yaml"))
            for stage_dir in sorted(glob.glob(os.path.join(feature_dir, "stages", "*"))):
                if os.path.isdir(stage_dir):
                    stage = _scope(stage_dir, "stage.yaml")
                    stage["_feature_dir"] = feature_dir
                    stages.append(stage)
        return {
            "requirements": register.get("requirements") or [],
            "features": features,
            "stages": stages,
            "change_requests": _change_requests(),
            "releases": _releases(),
            "skills": _skills(),
            "docs": _docs(),
            "generated": {path: _text(path) for path in GENERATED_DOCS},
        }
    finally:
        os.chdir(previous)
