---
name: salon-sdlc
description: Coordinates feature, stage and release delivery. Use before repository work and for planning, role handoffs, closure, refactors, requirement changes, QA scope, Git delivery, releases or changes to these skills.
---

# Delivery lifecycle

This skill is the process; `salon-dev` is how a developer works and `salon-qa` how QA works. Load
the one for your role after this one. Everything about this product (architecture, stack, rules,
design, test plan, environments, roadmap, decisions) is under `docs/`, indexed by `docs/README.md`.
Never restate it in a skill.

Invoke a command as `/salon-sdlc <command> <id>` (Claude Code) or `$salon-sdlc <command> <id>`
(Codex), then follow its section of [references/lifecycle.md](references/lifecycle.md) exactly,
including every refusal. With no command, do "Start every session" and map the request to one.

## Vocabulary

Use these words and no others; "task", "ticket", "slice", "phase" and "sprint" name nothing here.

| Term | Id | Meaning |
| --- | --- | --- |
| Feature | `F07` | Something customers can use and that can be released to them |
| Stage | `F07-S02` | A part of a feature the developer can build, show working and hand over on its own |
| Refactor | - | A change to how the product is built that customers cannot see: architecture, code organisation, an upgrade, tooling. Never a feature or a stage |
| Release / release band / milestone | `v1.2.0` / `R1` / `M1-web` | Shipped features / BRD horizon / delivery group (`docs/ROADMAP.md`) |
| Requirement / acceptance criterion | `BKG-04` / `AC-3` | A line of `docs/brd/requirements.yaml` / a testable statement in a stage's `spec.md` |
| Test case | `TC-F07-S02-001` | The test that proves it is titled with its id and criteria (`TC-F07-S02-001 AC-3: ...`) |
| Bug / change request | `BUG-F07-001` / `CR-004` | A file under `bugs/` / under `docs/changes/` |

## Roles

| Role | Does | Writes | Never |
| --- | --- | --- | --- |
| Lead | Plans, owns scope, keeps `docs/` true, closes stages and features, releases, talks to the owner | Specs, feature READMEs, `docs/`, change requests, releases, skills | Closes a stage the developer has not checked or a feature QA has not passed |
| Developer | Builds stages and refactors, checks criteria on the running product, fixes bugs | Application code, infrastructure, pipeline, unit/integration tests, `status` and `handoff`, bug fix fields | Writes QA automation, specs, docs or skills; edits a test to make it pass |
| QA | Decides how a feature is tested, writes and runs the tests, files bugs, reviews usability, keeps smoke, regression and release suites | QA automation, `review.md`, `bugs/`, QA fields of the yaml | Edits application code; weakens an assertion; passes work it has not run |

`qa/scripts/guard.py` enforces the paths (`docs/architecture/repository-structure.md`); run
`python3 qa/scripts/guard.py --role dev --diff` (or `--role qa`) before committing in a role.

The lead is the session the owner talks to. Developer and QA are separate agents (`salon-dev` and
`salon-qa` in Claude Code, `salon_dev` and `salon_qa` in Codex) or separate sessions; the handoff is
in files. **The agent that built any stage of a feature never verifies that feature.** If no second
agent is available, ask the owner. A session given a developer or QA command directly takes that
role and says so; when asked for an outcome, the lead delegates. State your role, act in one at a
time, and keep the owner informed as [references/progress.md](references/progress.md) describes.

## Where state lives

- `feature.yaml` and `stage.yaml` hold all status; `features/README.md` and `qa/coverage.md` are
  generated from them (`qa/scripts/sdlc generate`), never edited.
- The handoff is `status`, `qa_status` and the bug files; `qa/scripts/sdlc status` says whose turn it is.

| Tool command | Use |
| --- | --- |
| `qa/scripts/sdlc status` / `validate` / `generate` | Whose turn; the gate before committing under `features/`, `qa/`, `docs/` or a skill; rewrite generated documents |
| `qa/scripts/sdlc run --feature F07` | QA automation for one scope (`--stage`, `--suite smoke|regression|release`, `--release`; `--dry-run` previews) |
| `qa/scripts/sdlc pr-check F07` | Closed, clean, and unchanged since QA's revision, before a feature PR |
| `qa/scripts/sdlc start-check F08` / `merge-sync F07 [--check]` | Earlier merges verified live / record a verified owner merge |
| `qa/scripts/sdlc cleanup --branch <exact>` | Preview, then `--apply`, removal of merged branches and worktrees |

## Start every session

1. `git status --short`; changes you did not make are someone else's.
2. Read `features/README.md`. On resumed work, check saved evidence first (`references/progress.md`).
3. Read the one stage you work on (`spec.md`, `stage.yaml`) and the documents its spec names.
4. Load `salon-dev` or `salon-qa`, then run the command from `references/lifecycle.md`.

## Commands

| Command | Role | Status afterwards |
| --- | --- | --- |
| `feature-new <F>` | Lead | Feature `planned`, stages `outlined`, design confirmed |
| `stage-plan <F-S>` | Lead | Stage `planned` |
| `stage-build <F-S>` | Developer | `in-progress`, then `built` at handoff |
| `stage-close <F-S>` | Lead | `complete`, merged into the feature branch |
| `feature-verify <F>` | QA | Feature `in-qa`; `qa_status` `failing`, `blocked` or `pass` |
| `bug-fix <F>` | Developer | Bugs `fixed`; back to QA |
| `feature-close <F>` | Lead | `complete`, ready for its PR |
| `feature-submit <F>` / `feature-integrate <F>` | Lead | One PR for the owner / merge recorded, branches cleaned |
| `stage-verify <F-S>` | QA, only when the owner asks | The stage's `qa_status` |
| `change-request <title>` | Lead | A `CR-nnn`; affected stages reopened once approved |
| `release-prepare`, `release-verify`, `release-cut <v>` | Lead, QA, lead | Manifest; `RESULTS.md`; `released` |

A refactor or upgrade has no command: "Refactors and upgrades" in `references/lifecycle.md`.

## What QA runs, and when

| Kind | What | When |
| --- | --- | --- |
| Feature QA | The feature's acceptance criteria, tenant isolation, a hands-on check of its new screens | Once per feature, after its stages close (`feature-verify`). QA dry-runs its tests against each closed stage during the build; a dry run certifies nothing |
| Smoke (`suites: [smoke]`) | The few critical checks that prove each delivered feature is alive | Every PR, after every merge, before and after any deploy |
| Regression (`suites: [regression]`) | Everything that must keep working, in parallel | Before a release, and for a refactor or upgrade that touches delivered features (the lead runs it unasked then); otherwise when the owner asks |
| Release (`suites: [release]`) | The slow checks: load, the production-like stack, image scans | Before any deploy, and at `release-verify` |
| Stage pass | One stage's tests | Only when the owner asks |

Time budgets per kind are in `docs/qa/test-plan.md`. The developer's gates are not QA runs; they
are reused while their source is unchanged (`docs/operations/verification-evidence.md`). Always run
QA through `qa/scripts/sdlc run`; running more than the scope asked for is as wrong as running less.

## Rules that hold everywhere

- **Requirements change through a change request** ([references/change-requests.md](references/change-requests.md)); never edit a spec or the register to match what was built. A QA suggestion is not a requirement until the owner approves it.
- **Refusals are the point.** When a command refuses, print why and stop.
- **Local by default.** Staging, production, real payments and real messages only when the owner's current message says so.
- **One PR per feature or refactor, merged by the owner** ([references/git-workflow.md](references/git-workflow.md), `docs/operations/git-delivery.md`). Never merge it yourself, push a tag, deploy or publish without the request. Merge and deployment are separate facts.
- **No secrets** in code, logs, evidence, bug files or skills.
- **Research before calling something a blocker:** the dependency's documentation and issue tracker, recorded.
- **Ask the owner** when a business rule is ambiguous, requirements conflict or an open decision blocks; otherwise the lead decides and records it in `docs/decisions/DECISION_LOG.md`.
- **Project facts live in `docs/`,** written there by the lead, never in a skill or a spec.

## Reference map

- [references/lifecycle.md](references/lifecycle.md): every command's contract, refactors, where bugs are filed.
- [references/git-workflow.md](references/git-workflow.md): before branching, committing, a PR or a merge.
- [references/change-requests.md](references/change-requests.md): a requirement changes, QA suggests, finished work reopens.
- [references/releases.md](references/releases.md): versions and releases (with `docs/operations/environments-and-releases.md`).
- [references/skill-maintenance.md](references/skill-maintenance.md): before editing a skill, agent file, `AGENTS.md` or `CLAUDE.md`.
- [references/progress.md](references/progress.md): at session start; updates, long commands, resuming, completion.
- `assets/`: templates for `feature.yaml`, `feature-README.md`, `stage.yaml`, `spec.md`, `change-request.md`, `release.yaml`.
