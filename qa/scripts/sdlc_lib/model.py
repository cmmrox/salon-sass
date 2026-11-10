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
# A case is declared by the test that proves it (decision 068): a Playwright title, or a header
# comment in a k6 or shell script, that starts with the case id and names what it covers before
# a colon, for example test('TC-F04-S01-003 AC-2: an owner saves the salon's hours', ...).
TITLE_CASE_RE = re.compile(
    r"""(?:\btest(?:\.describe)?(?:\.\w+)?\(\s*['"`]|^[ \t]*(?:#|//)[ \t]*)"""
    r"""(TC-F\d{2}(?:-S\d{2}[a-z]?)?-\d{3})\b([^'"`\n]*)""", re.M)
COVER_RE = re.compile(r"\b(?!TC-|BUG-|CR-)[A-Z]{2,5}-\d{1,3}\b")
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
        "cr_criteria": _cr_criteria(text),
        "read_first": named,
        "missing_docs": [path for path in named if not os.path.exists(path)],
        "commands": _commands(text),
    }


# A section a change request added to a merged stage's spec (decision 098): "## CR-028 ...".
CR_SECTION_RE = re.compile(r"^##\s+(CR-\d{3})\b[^\n]*$(.*?)(?=^## |\Z)", re.M | re.S)


def _cr_criteria(text):
    """The criteria declared under a '## CR-nnn' heading, each with the change request that added it.

    Whether such a criterion must already be covered depends on that request's status, which
    validate.py decides; the reader only records where each criterion was declared."""
    found = {}
    for request, body in CR_SECTION_RE.findall(text):
        for criterion in AC_RE.findall(body):
            found.setdefault(criterion, request)
    return found


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


CASE_TITLE_RE = re.compile(r"""(TC-F\d{2}(?:-S\d{2}[a-z]?)?-\d{3})\b([^'"`\n]*)""")
# The Playwright calls that declare a test that runs. test.skip, test.fixme, test.only and
# describe.skip/fixme declare nothing that counts; neither does a comment.
_RUNNING_CALL_RE = re.compile(r"(?<![\w$.])test(?:\.describe(?:\.(?:serial|parallel))?)?\s*\(")
_SKIPPED_CALL_RE = re.compile(r"(?<![\w$.])test(?:\.describe)?\.(?:skip|fixme)\s*\(")
_REGEX_AFTER = set("(,=:[!&|?{};+-*%<>~^") | {""}


def _ts_skeleton(text):
    """The source with comments blanked and string contents blanked, positions unchanged.

    Returns (skeleton, strings): strings maps each literal's opening offset to its closing
    offset, so a caller can read a literal from the original text. A small lexer rather than a
    regex, so that '//' in a URL or '/*' in a glob inside a string is not taken for a comment."""
    out, strings, i, n = list(text), {}, 0, len(text)
    previous = ""  # the last significant character outside comments and strings

    def blank(start, end):
        for k in range(start, end):
            if out[k] != "\n":
                out[k] = " "

    while i < n:
        c = text[i]
        if c == "/" and text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end == -1 else end
            blank(i, end)
            i = end
        elif c == "/" and text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end == -1 else end + 2
            blank(i, end)
            i = end
        elif c in "'\"`":
            j, depth = i + 1, 0
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if c == "`" and text.startswith("${", j):
                    depth += 1
                    j += 2
                    continue
                if c == "`" and depth and text[j] == "}":
                    depth -= 1
                elif text[j] == c and not depth:
                    break
                elif text[j] == "\n" and c != "`":
                    break
                j += 1
            strings[i] = min(j, n)
            blank(i + 1, min(j, n))
            i, previous = j + 1, c
        elif c == "/" and previous in _REGEX_AFTER:
            j, in_class = i + 1, False  # a regular expression literal: skip it whole
            while j < n and text[j] != "\n":
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == "[":
                    in_class = True
                elif text[j] == "]":
                    in_class = False
                elif text[j] == "/" and not in_class:
                    break
                j += 1
            blank(i + 1, min(j, n))
            i, previous = j + 1, "/"
        else:
            if not c.isspace():
                previous = c
            i += 1
    return "".join(out), strings


def _closing(skeleton, open_paren):
    """The offset of the parenthesis that closes the one at open_paren (end of file if none)."""
    depth = 0
    for k in range(open_paren, len(skeleton)):
        if skeleton[k] == "(":
            depth += 1
        elif skeleton[k] == ")":
            depth -= 1
            if not depth:
                return k
    return len(skeleton)


def _first_string(text, skeleton, strings, open_paren):
    """The literal that is a call's first argument, or None when the first argument is not one."""
    k = open_paren + 1
    while k < len(skeleton) and skeleton[k].isspace():
        k += 1
    return text[k + 1:strings[k]] if k in strings else None


def playwright_titles(text):
    """The titles of the tests and describe blocks in a Playwright file that actually run.

    A comment, a commented-out test, test.skip(...), test.fixme(...), a test inside a skipped
    describe, and a test or describe whose body calls test.skip() or test.fixme() all count for
    nothing: none of them is a test that proves a case."""
    skeleton, strings = _ts_skeleton(text)
    calls = []
    for match in _RUNNING_CALL_RE.finditer(skeleton):
        paren = match.end() - 1
        title = _first_string(text, skeleton, strings, paren)
        if title is not None:
            calls.append((paren, _closing(skeleton, paren), title))
    excluded = []
    for match in _SKIPPED_CALL_RE.finditer(skeleton):
        paren = match.end() - 1
        if _first_string(text, skeleton, strings, paren) is not None:
            excluded.append((paren, _closing(skeleton, paren)))  # a skipped test or block
        else:
            # A skip from inside a body skips the innermost test or block around it.
            around = [(start, end) for start, end, _ in calls if start < paren < end]
            if around:
                excluded.append(max(around))
    return [title for start, _end, title in calls
            if not any(lo <= start <= hi for lo, hi in excluded)]


def _case_titles(entry, text):
    """(case id, rest of title) for each case an automation entry's file declares.

    Playwright declares a case by the title of a test that runs. k6, shell and Maestro have no
    test call, so they title a case in a header comment ('# TC-F07-S01-004 AC-5: ...')."""
    if entry.get("tool") == "playwright":
        found = []
        for title in playwright_titles(text):
            match = CASE_TITLE_RE.match(title)
            if match:
                found.append(match.groups())
        return found
    return TITLE_CASE_RE.findall(text)


def _title_cases(data):
    """Cases named in the titles of a scope's registered tests; each entry learns what it covers."""
    found, prefix = [], f"TC-{data.get('id')}-"
    for entry in _automation(data):
        if not isinstance(entry, dict) or not entry.get("file"):
            continue
        text = _text(entry["file"]) or ""
        ids = []
        for cid, rest in _case_titles(entry, text):
            if cid.startswith(prefix) and cid[len(prefix):].isdigit():
                found.append({"id": cid, "covers": COVER_RE.findall(rest.split(":", 1)[0]), "file": entry["file"]})
                ids.append(cid)
        entry["_covers"] = sorted(set(ids))
    return found


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
    # F01 to F03 declare cases in yaml and cases.md; later work declares them in test titles.
    if not data.get("cases") and cases is None:
        data["_title_cases"] = _title_cases(data)
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
