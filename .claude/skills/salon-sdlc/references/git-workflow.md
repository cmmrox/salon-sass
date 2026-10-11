# Git workflow

`main` is the trunk and is always releasable. The hosting service and the continuous integration
system are named in `docs/architecture/tech-stack.md`.

- If `git remote -v` shows no remote, the owner has not created it yet. Work locally; do not create
  a remote or a repository yourself.
- If `git log` shows no commits, stop. The first commit is the lead's, on `main`, at the owner's
  request; nothing can branch until it exists.

## Contents

- Branches
- Commits
- Before every commit
- Pull requests and merging
- Working in parallel
- Never

## Branches

One branch per stage. The developer and QA both commit to it, in turn.

```
{username}/{type}/{id}-{slug}
^[a-z]+/(feature|bugfix|hotfix|test|refactor|docs|chore)/[A-Za-z0-9]+(-[A-Za-z0-9]+)*$
```

| Type | Use | Example |
| --- | --- | --- |
| `feature` | A stage | `charithm/feature/F07-S02-slot-search-and-hold` |
| `bugfix` | A defect found after its stage was closed | `charithm/bugfix/F07-S02-hold-expiry-off-by-one` |
| `hotfix` | A defect in a released version | `charithm/hotfix/v1-2-1-payment-callback` |
| `test` | QA work done ahead of the developer's handoff | `charithm/test/F07-S02-cases` |
| `refactor`, `docs`, `chore` | Work that belongs to no stage (tooling, skills, documents, a change request, a release) | `charithm/docs/CR-004-per-salon-hold-time`, `charithm/chore/release-v0-1-0` |

A stage reopened by a change request gets a new branch that names both:
`charithm/feature/F07-S02-cr-004-per-salon-hold-time`.

- `{username}` is the short lowercase handle of the person running the session. Read it from the
  existing branches (`git branch -a`); if there are none, ask once. Never use `claude/`, `codex/` or
  an agent name.
- Branch from an up-to-date `main`.
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
| Lead | Planning | `docs(F07-S02): plan slot search and hold`, committed on the stage's branch, which the lead creates when planning. A feature's outline is committed on its first stage's branch |
| Developer | Building | `feat(F07-S02): hold a slot for ten minutes` |
| Developer | Fixing a bug | `fix(F07-S02): release expired holds at the exact expiry` with `Fixes BUG-F07-S02-003` in the body |
| Developer | Handing over | `chore(F07-S02): hand over to QA` |
| QA | Cases and automation | `test(F07-S02): cover simultaneous holds on one slot` |
| QA | Results and review | `test(F07-S02): record first QA pass, 3 bugs filed` |
| Lead | Closing | `chore(F07-S02): close stage - slot search and hold` |
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

- One pull request per stage, into `main`, opened when the stage is closed and the owner asks.
- Title: `F07-S02 Slot search and hold`.
- Body: what the stage delivers; the acceptance criteria; the QA result (cases, automated, manual,
  deferred); bugs found and fixed; suggestions awaiting the owner; anything deferred.
- Merge with a merge commit, not a squash, so developer and QA commits stay attributable. Delete the
  branch after the merge.
- After a merge, bring `main` into every stage branch still in progress.
- Until continuous integration exists for the repository, the local gates are the gate.

## Working in parallel

- Two sessions that share one checkout take turns: only the role whose turn it is writes.
- Different stages may be built at the same time, each in its own git worktree and branch.
- QA may draft cases from the spec while the developer is still building, on a
  `{username}/test/{id}-cases` branch in its own worktree, and merge it into the stage branch at the
  handoff.

## Never

- Commit to `main` directly. The only exception is the repository's first commit.
- Push, force-push, open or merge a pull request, or push a tag unless the owner asks.
- Rewrite the history of a pushed branch.
- Run `git reset --hard`, `git clean`, `git checkout -- <path>` or delete a branch to tidy up.
  Uncommitted changes you did not make are someone's work: report them.
