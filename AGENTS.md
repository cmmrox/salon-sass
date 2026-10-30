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
  a part of it that the developer can build and show working on its own. A change customers cannot
  see (architecture, code organisation, an upgrade, tooling) is a **refactor**: a decision entry,
  updated docs, a `refactor/` branch proved by the developer's gates, smoke (plus regression when
  delivered features are touched) and one PR. Never a feature, a stage or QA cases (decision 066).
- **Order and audience:** salon owner/admin/staff web first, released, then the customer React Native mobile app. No customer website (`docs/ROADMAP.md`).
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
- **Three kinds of QA run** (decision 068): feature QA once per feature after its stages close
  (QA dry-runs its tests as each stage closes); smoke on every PR, merge and deploy; regression
  before a release and for refactors or upgrades touching delivered features. Slow checks (load, the
  production-like stack, image scans) form the `release` suite, run only when the owner asks. After
  a fix, only the failed checks and those whose code it touched run again (decision 069). A single-stage pass runs only when the owner asks. Always
  through `qa/scripts/sdlc run`; a case is the test titled with its id (`TC-F07-S02-001 AC-2: ...`).
- **Path ownership:** the developer does not write under `qa/` or edit cases, reviews, specs or
  project documents; QA does not edit application code. Run
  `python3 qa/scripts/guard.py --role dev --diff` (or `--role qa`) before committing in either role.
- **Requirements change through a change request** under `docs/changes/`. QA's suggestions are
  proposals; nothing is built from one until the owner approves it.
- **Web UI is Tailwind CSS and shadcn/ui, always** (CR-016, CR-017). Every Next.js control, menu,
  list row, status label, message, pager and overlay is a shadcn/ui component, and every style a
  Tailwind utility on the prototype's theme: no native control dressed as a component (Native
  Select), no hand-built look-alike, no CSS Modules or hand-written CSS. Look the job up in the
  component map in `docs/engineering/web.md`, "Styling", before building any screen.
- **A request can only ever see its own salon.** Tenant isolation comes first in every design and
  every test: `docs/architecture/multi-tenancy.md`.
- **Local by default.** Staging, production, real payments and real messages are touched only when
  the owner's current message says so: `docs/operations/environments-and-releases.md`.
- **Git:** branch before committing (`{username}/{type}/{id}-{slug}`), Conventional Commits scoped
  to the stage (`feat(F07-S02): ...`). Stages and QA integrate into a feature branch. After full
  independent feature QA, open one GitHub PR for the owner to merge into `main`; verify that merge
  before starting the next feature. Clean up related merged branches/worktrees with the guarded
  lifecycle command. Push/PR authorisation and the existing-history transition are in
  `docs/operations/git-delivery.md`. Tags and deployments require separate requests.
- **Optional staging:** closing or merging a feature does not deploy to the VPS. The owner may
  deploy several merged features together; local QA and explicitly deferred staging cases are
  reported separately.
- **Skills and documents are the lead's.** Edit skills under `.claude/skills/` only
  (`.agents/skills/` holds symlinks to the same files) after reading `references/skill-maintenance.md`
  in `salon-sdlc`.
- The product has no name yet; `salon` is a placeholder. Do not invent one.
