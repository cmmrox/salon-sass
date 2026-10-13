# Git workflow

`main` is the trunk and is always releasable. The hosting service and the continuous integration
system are named in `docs/architecture/tech-stack.md`.

- If no remote is configured, consult `docs/operations/git-delivery.md`. Configure only the
  repository the owner supplied and authorised; never create an external repository yourself.
- If `git log` shows no commits, stop. The first commit is the lead's, on `main`, at the owner's
  request; nothing can branch until it exists.

## Contents

- Branches
- Commits
- Before every commit
- Pull requests and merging
- Confirming a merge and cleanup
- Working in parallel
- Never

## Branches

One integration branch per feature, one branch per stage, and one for the feature's QA pass.
`main` receives the complete feature only after independent QA, through the owner's PR merge.

```
{username}/{type}/{id}-{slug}
^[a-z]+/(feature|bugfix|hotfix|test|refactor|docs|chore)/[A-Za-z0-9]+(-[A-Za-z0-9]+)*$
```

| Type | Use | Example |
| --- | --- | --- |
| `feature` | A feature integration branch, or a stage | `charithm/feature/F07-online-booking`, `charithm/feature/F07-S02-slot-search-and-hold` |
| `bugfix` | A defect found after its stage was closed | `charithm/bugfix/F07-S02-hold-expiry-off-by-one` |
| `hotfix` | A defect in a released version | `charithm/hotfix/v1-2-1-payment-callback` |
| `test` | The feature's QA pass, or cases drafted ahead of it | `charithm/test/F07-feature-qa` |
| `refactor`, `docs`, `chore` | Work that belongs to no stage (tooling, skills, documents, a change request, a release) | `charithm/docs/CR-004-per-salon-hold-time`, `charithm/chore/release-v0-1-0` |

A stage reopened by a change request gets a new branch that names both:
`charithm/feature/F07-S02-cr-004-per-salon-hold-time`.

- `{username}` is the short lowercase handle of the person running the session. Read it from the
  existing branches (`git branch -a`); if there are none, ask once. Never use `claude/`, `codex/` or
  an agent name.
- Start a feature's integration branch from the latest `origin/main`, after `start-check` verifies
  the preceding feature merges. Record it in `feature.yaml` under `integration.branch`.
- Branch its stages, QA and pre-release fixes from that integration branch. The lead merges their
  commits back there with merge commits after the relevant role's gates; no stage PR targets `main`.
- `release/X-Y-Z` exists only to maintain a version that is already shipped. Do not create one
  unless the owner asks.

## Commits

Conventional Commits. The scope is the stage or feature id when the work belongs to one.

```
<type>(<scope>): <imperative summary, under 72 characters>
^(feat|fix|test|docs|refactor|perf|build|ci|chore|revert)(\([A-Za-z0-9-]+\))?!?: .+
```

| Who | Commit | Example |
| --- | --- | --- |
| Lead | Planning | `docs(F07-S02): plan slot search and hold`, on its stage branch. A feature's outline is committed on its integration branch |
| Developer | Building | `feat(F07-S02): hold a slot for ten minutes` |
| Developer | Fixing a bug | `fix(F07-S02): release expired holds at the exact expiry` with `Fixes BUG-F07-S02-003` in the body |
| Developer | Handing over | `chore(F07-S02): hand over` |
| Lead | Closing a stage | `chore(F07-S02): close stage - slot search and hold` |
| QA | Cases and automation | `test(F07-S02): cover simultaneous holds on one slot`, or `test(F07): ...` for a journey |
| QA | Results and review | `test(F07): record first QA pass, 3 bugs filed` |
| Developer | Fixing a bug from the feature pass | `fix(F07): ...` with `Fixes BUG-F07-002` in the body |
| Lead | Closing a feature | `chore(F07): close feature - online booking` |
| Lead | A change request | `docs(CR-004): propose per-salon hold time`, later `docs(CR-004): apply to register and specs` |
| Lead | Release | `chore(release): cut v1.2.0` |

Scopes outside a stage: a change request id (`CR-004`), `sdlc`, `skills`, `infra`, `docs`, `release`. Work on a
stage reopened by a change request keeps the stage id as its scope and names the request in the body.

Keep commits by role separate. A commit that mixes application code and QA automation hides who
decided what; the guard check below will flag it.

## Before every commit

```bash
git status --short
python3 qa/scripts/guard.py --role dev --diff     # or --role qa; the lead skips this
qa/scripts/sdlc validate                           # when features/, qa/, docs/ or a skill changed
```

Stage files by name. Never commit `.env` files, anything under `qa/reports/`, build output, native
folders a tool generates, or credentials. Review the staged diff before committing.

## Pull requests and merging

- One feature PR into `main`, opened after independent full feature QA and `feature-close`, using
  the feature integration branch. Title: `F07 Online booking`.
- Run `qa/scripts/sdlc pr-check F07` before pushing the final branch and creating the PR. Record
  its URL in `integration.pull_request`, regenerate/validate and push the metadata commit. That
  metadata commit does not change the product or automation tested at `integration.qa_revision`.
- Include what customers can do, the developer gates, the QA result and tested revision, verified
  bugs, manual/deferred cases, unresolved minor issues, and the deployment status. Attach the PR
  to the current chat when the tool supports it. Staging is not a merge prerequisite.
- The owner reviews and merges with a merge commit. Do not merge the feature PR yourself. Required
  CI checks must pass; configure the repository's protection according to
  `docs/operations/git-delivery.md`. Do not require an unavailable separate human reviewer.
- Any subsequent product, automation or delivery change requires feature QA again. Update the
  QA revision before presenting the final PR for merge.
- Process, documentation, release and hotfix changes outside feature development use separate,
  scoped PRs with their appropriate checks; a skill update does not run product feature QA.

## Confirming a merge and cleanup

1. Verify live: `qa/scripts/sdlc merge-sync F07 --check`. It checks the PR's repository and branches,
   merged state, tested history, successful head CI and inclusion in freshly fetched `origin/main`. A local merge or
   a `complete` status is not proof of the owner's GitHub merge.
2. Update a clean retained checkout of `main` with a fast-forward from `origin/main`.
3. Preview cleanup with repeated `--branch <exact-related-branch>` arguments, then run the same
   `qa/scripts/sdlc cleanup` command with `--apply`. Include only this feature's integration,
   stage, QA and fix branches. The owner has authorised this post-merge cleanup in the project
   policy; do not ask again for targets that pass the checks.
4. Cleanup refuses `main`, the current branch, dirty or locked worktrees, local service pid files,
   unknown ignored files, and local or remote commits outside `origin/main`. It archives QA
   reports before removal, deletes without forcing local branches/worktrees, uses an expected-SHA
   lease for remote deletion, and verifies absence. Archive/receipt paths are printed. If a target
   is unsafe, preserve it and report the concrete reason; never discard work to make cleanup pass.
5. Create the next feature integration branch from updated `origin/main` while its feature remains
   in the backlog. Run `qa/scripts/sdlc merge-sync F07` there to record the preceding merge, then
   generate/validate and commit the bookkeeping. This metadata is included in the next feature
   PR; it avoids an extra PR or a direct write to `main` solely to record the previous PR's merge.
6. Run `qa/scripts/sdlc start-check F08` before planning or building. It verifies the recorded
   merges against GitHub again. The next feature can start without a VPS deployment.

## Working in parallel

- Two sessions that share one checkout take turns: only the role whose turn it is writes.
- Different stages of the same feature may be built in separate git worktrees and branches.
  Different features proceed in portfolio order; only an explicit owner exception recorded in
  `started_early` changes that order, and a dependency still needs its confirmed merge.
- QA may draft cases from the specs while the stages are still being built, on the feature's
  `test` branch in its own worktree. Drafting is not a pass: nothing is run or recorded until
  `feature-verify`.

## Never

- Commit to `main` directly. The only exception is the repository's first commit.
- Push or open a PR outside the owner's recorded authorisation. The authorisation to push or
  create a PR is not authorisation to merge it, tag, or deploy.
- Rewrite the history of a pushed branch.
- Run `git reset --hard`, `git clean`, `git checkout -- <path>`, forced worktree removal, or
  forced local branch deletion to tidy up. Use only the checked cleanup procedure above;
  uncommitted changes you did not make are someone's work.
