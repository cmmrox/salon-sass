---
name: salon-sdlc
description: Runs the delivery lifecycle of this repository - feature and stage planning, the developer and QA handoff, stage and feature closure, requirement changes, releases and the Git workflow. Use before any work in this repository, and whenever a request names a feature or stage id (F07, F07-S02), a lifecycle command (feature-new, stage-plan, stage-build, stage-verify, stage-close, feature-verify, feature-close, change-request, release-prepare, release-verify, release-cut), a branch, commit, pull request, requirement change, regression, smoke test or release, or asks to change these skills.
---

# Delivery lifecycle

This skill is the process. `salon-dev` is how a developer works and `salon-qa` is how QA works; load
the one that matches your role after this one.

The skills hold how work is done. Everything about this particular product (architecture, stack,
repository structure, engineering rules, design standards, test plan, environments, roadmap) is
under `docs/`, and `docs/README.md` is its index. Read from there; never restate it in a skill.

Invoke a lifecycle command as `/salon-sdlc <command> <id>` in Claude Code or
`$salon-sdlc <command> <id>` in Codex. When a command is given, open
[references/lifecycle.md](references/lifecycle.md), go to that command's section and follow it
exactly, including every refusal. When no command is given, do "Start every session" below and then
work out which command the request maps to.

## Vocabulary

Use these words and no others. "Task", "ticket", "slice", "phase" and "sprint" do not name anything here.

| Term | Id | Meaning |
| --- | --- | --- |
| Feature | `F07` | Something the product's customers can use, and that can be released to them. It cuts across the layers of the product. |
| Stage | `F07-S02` | A part of a feature that the developer can hand over and QA can test on its own. Never a small change. |
| Release | `v1.2.0` | A set of complete features shipped together. |
| Release band | `R1` | The BRD's planning horizon for a requirement or feature (`R1`, `R2`, `R3`). Not a version. |
| Milestone | `M1-web` | A group of features delivered and released before the next group starts. The order is in `docs/ROADMAP.md`. |
| Requirement | `BKG-04` | One line of `docs/brd/requirements.yaml`, the living requirement register. |
| Acceptance criterion | `AC-3` | A testable statement in a stage's `spec.md`. |
| Test case | `TC-F07-S02-001` | Declared in `stage.yaml`, described in `cases.md`. |
| Bug | `BUG-F07-S02-001` | One file under the stage's `bugs/`. |
| Change request | `CR-004` | One file under `docs/changes/`. |

## Roles

| Role | Does | Writes | Never |
| --- | --- | --- | --- |
| Lead | Plans features and stages, owns scope and requirement changes, keeps the project documents true, closes stages and features, cuts releases, talks to the owner | `spec.md`, feature `README.md`, `docs/`, change requests, releases, skills | Closes anything QA has not passed |
| Developer | Builds a stage and fixes the bugs QA files | Application code, infrastructure, the build pipeline, generated files, unit and integration tests, `status` and `handoff` in `stage.yaml`, the fix fields of a bug | Writes QA automation, cases, reviews, specs, project documents or skills; edits a test to make it pass |
| QA | Decides how a stage must be tested, writes cases and automation, runs them, files bugs, reviews usability, prepares regression and smoke | QA automation, `cases.md`, `review.md`, `bugs/`, QA fields in `stage.yaml` and `feature.yaml` | Edits application code; weakens an assertion; passes work it has not run |

The exact paths each role may write are enforced by `qa/scripts/guard.py` and described in
`docs/architecture/repository-structure.md`. The lead may write anywhere, and fills in the
Disposition column of a `review.md`.

The lead is the session the owner is talking to. The developer and QA are separate agents: the
`salon-dev` and `salon-qa` subagents in Claude Code, the `salon_dev` and `salon_qa` agents in Codex,
or separate terminal sessions in either tool. Any mix works, because the handoff is in files.

**The agent that built a stage never verifies it.** A check written from the same assumptions as the
code agrees with the code. QA starts from a fresh context holding the spec, the requirements and the
running product. If no second agent is available, tell the owner and ask how to proceed; do not
verify your own build in the same context.

A session takes the role of the command it is given. When the owner runs a developer or QA
command directly (`stage-build`, `bug-fix`, `stage-verify`, `feature-verify`), that session is the
developer or QA for that command, and says so. When the owner asks the lead for the outcome ("build
F07-S02", "QA F07-S02"), the lead delegates to the matching agent. If the agent or a skill is not
available in the session (they are picked up when a session starts), read the files under
`.claude/skills/` directly and act in the role yourself, under the same rules.

State the role you are acting in at the start of your work, and act in one role at a time. Before
committing as developer or QA, run `python3 qa/scripts/guard.py --role dev --diff` (or `--role qa`).

## Where state lives

- `features/<feature>/feature.yaml` and `features/<feature>/stages/<stage>/stage.yaml` are the only
  places status is written. Everything else is derived from them.
- `features/README.md` and `qa/coverage.md` are generated. Never edit them; edit the yaml and run
  `qa/scripts/sdlc generate`.
- The handoff is `status`, `qa_status` and the bug files. `qa/scripts/sdlc status` prints whose turn
  it is for every stage in flight. Nothing is handed over in conversation.

| Tool command | Use |
| --- | --- |
| `qa/scripts/sdlc status` | Whose turn it is and which command comes next |
| `qa/scripts/sdlc validate` | The gate over features, stages, cases, bugs, releases and these skills. Run before every commit that touches `features/`, `qa/`, `docs/` or a skill |
| `qa/scripts/sdlc generate` | Rewrite the generated documents |
| `qa/scripts/sdlc run --stage F07-S02` | Run QA automation for exactly one scope (`--feature`, `--suite`, `--release` likewise; `--dry-run` to preview) |

## Start every session

1. Run `git status --short` and note the branch. Changes you did not make belong to someone else:
   leave them alone.
2. Read `features/README.md`. It is the portfolio and says what is next.
3. Read the folder of the one stage you are working on: `spec.md` and `stage.yaml`, then every
   project document its "Read first" section names. Do not read other stages' folders; a stage is self-contained so that a session stays small. (Feature-wide QA,
   change requests and releases read across stages; their commands say so.)
4. Load the role skill: `salon-dev` to build or fix, `salon-qa` to test.
5. Run the lifecycle command for the work, from [references/lifecycle.md](references/lifecycle.md).

## Commands

| Command | Role | Status afterwards |
| --- | --- | --- |
| `feature-new <F>` | Lead | Feature `planned`, its stages `outlined` |
| `stage-plan <F-S>` | Lead | Stage `planned` |
| `stage-build <F-S>` | Developer | Stage `in-progress`, then `in-qa` at handoff |
| `bug-fix <F-S>` or `<F>` | Developer | Bugs `fixed`; back to QA |
| `stage-verify <F-S>` | QA | `qa_status` `failing`, `blocked` or `pass` |
| `stage-close <F-S>` | Lead | Stage `complete` |
| `feature-verify <F>` | QA | Feature `in-qa`, `qa_status` `failing` or `pass` |
| `feature-close <F>` | Lead | Feature `complete` |
| `change-request <title>` | Lead | A `CR-nnn` file; affected stages reopened once approved |
| `release-prepare <vX.Y.Z>` | Lead, with QA | A release manifest; nothing is run |
| `release-verify <vX.Y.Z>` | QA | `RESULTS.md` for the release |
| `release-cut <vX.Y.Z>` | Lead | Stages and features `released` |

## What QA runs, and when

This is a rule, not an optimisation. Running more than the scope asked for is a process violation,
the same as running less.

| Trigger | Runs | Does not run |
| --- | --- | --- |
| `stage-verify`, `stage-close` | That stage's automation | Earlier stages of the feature, other features |
| `feature-verify`, `feature-close` | That feature's stage automation and its feature journeys | Other features |
| `release-verify`, or the owner asks for regression or smoke | The regression and smoke suites | - |

- Regression and smoke run only when the owner asks, in so many words. Finishing a stage, fixing a
  bug or changing shared code is not a request for regression. If you believe a change puts earlier
  work at risk, say so and let the owner decide.
- When the owner does ask for a regression in the middle of a feature, run
  `qa/scripts/sdlc run --feature <F>` for the feature in flight (its stages so far), and
  `qa/scripts/sdlc run --suite regression` for the features already delivered.
- The developer's own gates (compile, lint, unit and integration tests of the modules touched) are
  not QA runs. They run every time, through `verification.commands`.
- Always go through `qa/scripts/sdlc run`. It selects by scope, refuses an empty selection and
  writes the evidence.

## Rules that hold everywhere

- **Requirements change through a change request.** Never edit a spec, a case or the register to
  match what was built. See [references/change-requests.md](references/change-requests.md).
- **A suggestion is not a requirement.** QA proposes improvements; nothing is built from one until
  the owner approves it.
- **Refusals are the point.** When a command says refuse, print the reason and stop. Do not work
  around a gate, mark something "probably fine", or lower a severity to pass.
- **Local by default.** Staging or production is touched only when the owner's current message names
  it. Earlier permission does not carry forward.
- **Never without being asked:** push, open or merge a pull request, push a tag, deploy, publish an
  app build, send a real message to a real person, or charge a real payment.
- **No secrets** in code, logs, test evidence, bug files or skills.
- **Research before calling something a blocker.** Check the official documentation and the issue
  tracker of the dependency first, and record what was searched.
- **Ask the owner** when a business rule is ambiguous, when two requirements conflict, or when a
  decision the project lists as open blocks the stage. Otherwise the lead decides, records the
  decision in `docs/decisions/DECISION_LOG.md`, and continues.
- **Project facts live in `docs/`.** When a session teaches you something about this product that
  the next session will need, it goes into the right document under `docs/` (through the lead), not
  into a skill and not into a spec.

## Reference map

- [references/lifecycle.md](references/lifecycle.md): the contract of every command. Read the
  section for the command you are running.
- [references/git-workflow.md](references/git-workflow.md): read before creating a branch,
  committing, opening a pull request or merging.
- [references/change-requests.md](references/change-requests.md): read when a requirement is added
  or changed, when QA proposes an improvement, or when finished work must be reopened.
- [references/releases.md](references/releases.md): read before choosing a version or preparing,
  verifying or cutting a release. The environments and the product's own release rules are in
  `docs/operations/environments-and-releases.md`.
- [references/skill-maintenance.md](references/skill-maintenance.md): read before editing any skill,
  agent file, `AGENTS.md` or `CLAUDE.md`.
- `assets/`: the templates the commands copy (`feature.yaml`, `feature-README.md`, `stage.yaml`,
  `spec.md`, `change-request.md`, `release.yaml`).
