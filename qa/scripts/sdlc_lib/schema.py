"""The vocabulary of the work spine. One place, so the validator, the generator and the
runner cannot disagree about what a status or a test type is called.

The skills document these values for people; this file is what is enforced.
"""
import re

FEATURE_STATUSES = ("backlog", "planned", "in-progress", "in-qa", "complete", "released")
# built: the developer has handed the stage over and the lead closes it on the developer's
# gates. QA happens once, on the whole feature, so a stage has no QA state of its own unless
# the owner asked for a stage pass.
STAGE_STATUSES = ("outlined", "planned", "in-progress", "built", "complete", "released")
# blocked: QA cannot run because of something it cannot repair itself (a missing emulator,
# a tool that needs installing). It is the lead's turn; blocked_reason says why.
QA_STATUSES = ("not-run", "failing", "blocked", "pass")
# A stage pass the owner asked for that has not finished. A stage cannot be closed over it.
UNFINISHED_QA = ("failing", "blocked")
RELEASES = ("R1", "R2", "R3")
SURFACES = ("api", "web", "mobile", "infra", "docs")

# Closed work. A feature that claims it must have passing QA behind the claim; a stage must
# have the developer's gates and handoff behind it, and no stage pass left unfinished.
CLAIMED = ("complete", "released")
# spec.md is required from here on.
SPECIFIED = ("planned", "in-progress", "built", "complete", "released")
# The developer's handoff is required from here on.
HANDED_OVER = ("built", "complete", "released")
# A dependency must have reached one of these before a stage is built on it.
BUILDABLE_ON = ("complete", "released")

TOOLS = ("playwright", "maestro", "k6", "script")
SUITES = ("smoke", "regression")
CASE_TYPES = (
    "functional", "api", "tenant-isolation", "permissions", "concurrency", "load",
    "payment-integrity", "accessibility", "responsive", "branding", "security",
    "mobile", "data-integrity", "resilience", "build",
)
CASE_PRIORITIES = ("critical", "high", "medium", "low")

BUG_SEVERITIES = ("blocker", "major", "minor", "trivial")
BUG_STATUSES = ("open", "fixed", "verified", "reopened", "not-a-bug", "deferred", "wont-fix")
# Severities that stop a stage or feature from passing QA while unresolved.
BLOCKING_SEVERITIES = ("blocker", "major")
# A blocking bug stops blocking once QA has verified the fix, QA has withdrawn it as a
# test defect (not-a-bug), or the owner has decided the behaviour stays (wont-fix, with
# approved_by). It can never be deferred: only minor and trivial bugs can.
RESOLVED_BUG_STATUSES = ("verified", "not-a-bug", "wont-fix")
DEFERRABLE_SEVERITIES = ("minor", "trivial")

CR_STATUSES = ("proposed", "approved", "rejected", "implemented")

FEATURE_ID_RE = re.compile(r"^F\d{2}$")
COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
PR_URL_RE = re.compile(r"^https://github\.com/([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)/pull/([1-9][0-9]*)$")
# Metadata commits after QA may change feature records, but never the tested product,
# automation or delivery pipeline. Keep this list aligned with repository-structure.md.
FEATURE_QA_PATHS = (
    "apps", "packages", "infra", ".github", "package.json", "pnpm-workspace.yaml",
    "pnpm-lock.yaml", ".nvmrc", ".sdkmanrc", "docs/api", "qa/web", "qa/api", "qa/mobile", "qa/load",
    "qa/checks", "qa/shared", "qa/scripts", "qa/package.json", "qa/pnpm-lock.yaml",
    "qa/playwright.config.ts", "qa/tsconfig.json",
)
# Milestones sort by their number: M1-web is delivered and released before M2-mobile starts.
MILESTONE_RE = re.compile(r"^M\d+-[a-z][a-z0-9-]*$")
STAGE_ID_RE = re.compile(r"^(F\d{2})-(S\d{2}[a-z]?)$")
CASE_ID_RE = re.compile(r"^TC-(F\d{2}(?:-S\d{2}[a-z]?)?)-\d{3}$")
BUG_ID_RE = re.compile(r"^BUG-(F\d{2}(?:-S\d{2}[a-z]?)?)-\d{3}$")
CR_ID_RE = re.compile(r"^CR-\d{3}$")
RELEASE_VERSION_RE = re.compile(r"^v\d+\.\d+\.\d+$")

# Headings every stage spec.md must carry, in this order. A section that does not apply
# keeps its heading and says "Not applicable for this stage".
SPEC_HEADINGS = (
    "Objective",
    "Read first",
    "Requirements covered",
    "Scope",
    "Out of scope",
    "Acceptance criteria",
    "Design notes",
    "API and data changes",
    "UI and UX",
    "Risks and test focus",
    "Verification commands",
    "Done criteria",
)

SKILL_BODY_MAX_LINES = 500
SKILL_NAME_MAX = 64
SKILL_DESCRIPTION_MAX = 1024
REFERENCE_TOC_THRESHOLD = 100
