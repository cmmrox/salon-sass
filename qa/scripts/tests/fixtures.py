"""Dict fixtures for the tooling tests: one feature with one closed stage that also passed a
stage QA pass the owner asked for, so every QA rule has something to break.

Each test copies this known-good bundle and breaks exactly one thing, so a finding can only
come from the rule under test.
"""
import copy

from sdlc_lib import schema as S

FEATURE_DIR = "features/F07-online-booking"
STAGE_DIR = FEATURE_DIR + "/stages/S01-slot-search"


def stage(**changes):
    data = {
        "id": "F07-S01", "feature": "F07", "title": "Slot search", "sequence": 1,
        "status": "complete", "qa_status": "pass", "surfaces": ["api"],
        "requirements": ["BKG-01"], "depends_on": [],
        "handoff": {"run": "infra/scripts/local-up.sh", "data": "three salons",
                    "notes": "Every acceptance criterion was checked on the running product."},
        "cases": [{"id": "TC-F07-S01-001", "title": "Free slots are listed", "type": "api",
                   "priority": "high", "covers": ["AC-1"]}],
        "verification": {
            "commands": ["cd apps/api && ./mvnw verify"],
            "automation": [{"id": "slots", "tool": "playwright", "file": "qa/api/F07/slots.spec.ts",
                            "project": "api", "covers": ["TC-F07-S01-001"], "suites": ["regression"]}],
        },
        "evidence": {"last_run": "qa/reports/20260101-F07-S01", "last_result": "pass"},
        "_path": STAGE_DIR + "/stage.yaml", "_dir": STAGE_DIR, "_feature_dir": FEATURE_DIR,
        "_spec": {"headings": list(S.SPEC_HEADINGS), "acs": ["AC-1"],
                  "read_first": ["docs/engineering/backend.md"], "missing_docs": [],
                  "commands": ["cd apps/api && ./mvnw verify"]},
        "_cases_md": ["TC-F07-S01-001"], "_bugs": [], "_has_review": True,
        "_has_readme": False, "_missing_files": [],
    }
    data.update(changes)
    return data


def unverified_stage(**changes):
    """The normal closed stage: built, handed over and closed, with QA waiting for the feature."""
    data = stage(qa_status="not-run", evidence={}, cases=[], _cases_md=None, _has_review=False,
                 verification={"commands": ["cd apps/api && ./mvnw verify"], "automation": []})
    data.update(changes)
    return data


def feature(**changes):
    data = {
        "id": "F07", "title": "Online booking", "status": "in-progress", "release": "R1", "milestone": "M1-web",
        "qa_status": "not-run", "requirements": ["BKG-01"], "depends_on": [],
        "integration": {"branch": "charithm/feature/F07-online-booking", "qa_revision": "a" * 40,
                        "pull_request": None, "merged_commit": None, "merged_at": None},
        "_path": FEATURE_DIR + "/feature.yaml", "_dir": FEATURE_DIR, "_spec": None,
        "_cases_md": None, "_bugs": [], "_has_review": False, "_has_readme": True,
        "_missing_files": [],
    }
    data.update(changes)
    if "integration" not in changes:
        data["integration"]["branch"] = f"charithm/feature/{data['id']}-online-booking"
    return data


def bug(**changes):
    data = {"id": "BUG-F07-S01-001", "title": "Slot shown twice", "severity": "major",
            "status": "open", "_path": STAGE_DIR + "/bugs/BUG-F07-S01-001-slot-shown-twice.md"}
    data.update(changes)
    return data


def skill(**changes):
    data = {"dir": "salon-qa", "has_skill_md": True,
            "frontmatter": {"name": "salon-qa", "description": "Tests the product. Use when testing."},
            "body_lines": 80, "mirror": "ok",
            "references": [{"name": "bug-loop.md", "lines": 60, "has_contents": False, "linked": True}]}
    data.update(changes)
    return data


def bundle(stages=None, features=None, **changes):
    data = {
        "requirements": [
            {"id": "BKG-01", "module": "BKG", "release": "R1", "priority": "must"},
            {"id": "NFR-01", "module": "NFR", "release": "R1", "priority": "must"},
        ],
        "features": [feature()] if features is None else features,
        "stages": [stage()] if stages is None else stages,
        "change_requests": [], "releases": [], "skills": [skill()], "generated": {},
        "docs": {"index": True, "unlisted": [], "dangling": [], "pointers": []},
    }
    data.update(changes)
    return copy.deepcopy(data)
