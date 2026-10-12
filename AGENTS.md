# AGENTS.md

A white-label, multi-tenant salon management platform, delivered feature by feature under a fixed
lifecycle. `CLAUDE.md` imports this file, so Claude Code and Codex read the same guidance.

## Two places to read

- **How we work** is in three skills: `salon-sdlc` (the lifecycle, roles, git, releases),
  `salon-dev` (how a developer works) and `salon-qa` (how QA works). Invoke with `/salon-sdlc` in
  Claude Code or `$salon-sdlc` in Codex.
- **What this product is** is under `docs/`: architecture, stack, repository structure, engineering
  rules, design standards, test plan, environments, roadmap, decisions. `docs/README.md` is the
  index and says which document to read for which kind of work.

A project fact is never written into a skill, and a skill never restates a document.

## Before any work

- Load `salon-sdlc`, then `salon-dev` to build or `salon-qa` to test.
- Read `features/README.md` for what is next, then only the folder of the stage you are working on,
  then every document its `spec.md` names under "Read first".
- If a skill or agent named here is not listed in your session, read its files under
  `.claude/skills/<name>/` directly and follow them; the rules are the same.

## Rules

- **Vocabulary:** feature -> stage -> release. A feature is something customers can use; a stage is
  a part of it that the developer can build and show working on its own. Nothing is called a task.
- **Order:** the web application first, released, then the customer mobile app (`docs/ROADMAP.md`).
- **Three roles:** the lead plans and closes, the developer builds, QA verifies. The agent that
  built a stage never verifies its feature. In Claude Code delegate to the `salon-dev` and `salon-qa`
  subagents; in Codex spawn the `salon_dev` and `salon_qa` agents; or use separate sessions.
- **State is in files:** `feature.yaml` and `stage.yaml` are the only places status is written, and
  the handoff between roles is read from them and from the bug files. `qa/scripts/sdlc status` says
  whose turn it is.
- **Generated documents** (`features/README.md`, `qa/coverage.md`) are never edited by hand. Edit the
  yaml, then run `qa/scripts/sdlc generate`.
- **The gate:** run `qa/scripts/sdlc validate` before committing anything under `features/`, `qa/`,
  `docs/` or a skill. Fix findings at their source.
- **One QA pass per feature:** a stage is closed on the developer's gates, and QA tests the whole
  feature once every stage is closed, running that feature's automation only. A QA pass on a single
  stage, regression and smoke run only when the owner asks. Always through `qa/scripts/sdlc run`.
- **Path ownership:** the developer does not write under `qa/` or edit cases, reviews, specs or
  project documents; QA does not edit application code. Run
  `python3 qa/scripts/guard.py --role dev --diff` (or `--role qa`) before committing in either role.
- **Requirements change through a change request** under `docs/changes/`. QA's suggestions are
  proposals; nothing is built from one until the owner approves it.
- **A request can only ever see its own salon.** Tenant isolation comes first in every design and
  every test: `docs/architecture/multi-tenancy.md`.
- **Local by default.** Staging, production, real payments and real messages are touched only when
  the owner's current message says so: `docs/operations/environments-and-releases.md`.
- **Git:** branch before committing (`{username}/{type}/{id}-{slug}`), Conventional Commits scoped
  to the stage (`feat(F07-S02): ...`). Never push, open a pull request, merge, tag or deploy unless
  the owner asks.
- **Skills and documents are the lead's.** Edit skills under `.claude/skills/` only
  (`.agents/skills/` holds symlinks to the same files) after reading `references/skill-maintenance.md`
  in `salon-sdlc`.
- The product has no name yet; `salon` is a placeholder. Do not invent one.
