# Git workflow

`main` is the trunk and is always releasable. The hosting service and CI are named in
`docs/architecture/tech-stack.md`; remotes and push authorisation in `docs/operations/git-delivery.md`
(never create an external repository yourself). If `git log` shows no commits, stop: the first
commit is the lead's, on `main`, at the owner's request.

## Contents

- Branches
- Commits
- Before every commit
- Pull requests and merging
- Confirming a merge and cleanup
- Working in parallel
- Never

## Branches

One branch per feature, one per stage, and one per refactor. QA works on the feature branch itself,
in its own worktree: path ownership (`guard.py`) keeps the roles apart, so there is no QA branch to
keep in sync. `main` receives a feature only after its QA pass, through the owner's PR merge.

```
{username}/{type}/{id}-{slug}
^[a-z]+/(feature|bugfix|hotfix|refactor|docs|chore)/[A-Za-z0-9]+(-[A-Za-z0-9]+)*$
```

| Type | Use | Example |
| --- | --- | --- |
| `feature` | A feature branch, or one of its stages | `charithm/feature/F07-online-booking`, `charithm/feature/F07-S02-slot-search-and-hold` |
| `refactor` | A refactor or upgrade; a sub-branch for parallel parts of one | `charithm/refactor/modular-architecture`, `charithm/refactor/service-interfaces` |
| `bugfix` | Bugs found after a stage closed or after a merge | `charithm/bugfix/F07-S02-hold-expiry-off-by-one` |
| `hotfix` | A defect in a released version | `charithm/hotfix/v1-2-1-payment-callback` |
| `docs`, `chore` | Documents, skills, tooling, a change request, a release | `charithm/docs/CR-004-per-salon-hold-time`, `charithm/chore/release-v0-1-0` |

- `{username}` is the short lowercase handle used by the existing branches (`git branch -a`); ask
  once if there are none. Never `claude/`, `codex/` or an agent name.
- A feature branch starts from the latest `origin/main` after `start-check`, and is recorded in
  `integration.branch`. Stages and fixes branch from it, and the lead merges them back with merge
  commits; no stage PR targets `main`. A stage reopened by a change request gets a branch naming
  both (`charithm/feature/F07-S02-cr-004-per-salon-hold-time`).
- `release/X-Y-Z` exists only to maintain a shipped version, when the owner asks.

## Commits

Conventional Commits, scoped to the stage, feature, refactor area or `CR-nnn`:

```
<type>(<scope>): <imperative summary, under 72 characters>
^(feat|fix|test|docs|refactor|perf|build|ci|chore|revert|merge)(\([A-Za-z0-9-]+\))?!?: .+
```

| Who | Example |
| --- | --- |
| Lead plans | `docs(F07-S02): plan slot search and hold` |
| Developer builds, hands over in the last commit | `feat(F07-S02): hold a slot for ten minutes` |
| Lead closes (the merge commit carries `status: complete`) | `merge(F07-S02): slot search and hold` |
| QA writes and records | `test(F07): cover simultaneous holds on one slot`, `test(F07): record QA pass, 3 bugs filed` |
| Developer fixes | `fix(F07): release expired holds at expiry` with `Fixes BUG-F07-003` in the body |
| Refactor | `refactor(identity): services as interface and implementation` |
| Lead closes a feature, a CR, a release | `chore(F07): close feature - online booking`, `docs(CR-004): ...`, `chore(release): cut v1.2.0` |

Keep commits by role separate: one that mixes application code and QA automation hides who decided what.

## Before every commit

```bash
git status --short
python3 qa/scripts/guard.py --role dev --diff     # or --role qa; the lead skips this
qa/scripts/sdlc validate                           # when features/, qa/, docs/ or a skill changed
```

Stage files by name. Never commit `.env` files, `qa/reports/`, build output or credentials. Review
the staged diff.

## Pull requests and merging

- One PR into `main` per feature (after `feature-close`, title `F07 Online booking`) or per
  refactor (after the lead's review of its diff and its smoke and regression runs).
- Run `qa/scripts/sdlc pr-check F07` before the final push. Record the PR URL in
  `integration.pull_request` in one metadata commit; later progress goes in the PR body.
- The body says what customers can do (or, for a refactor, what changed in the codebase and that
  nothing visible changed), the developer gates, the QA or suite results with the tested revision,
  verified bugs, manual and deferred cases, open minor issues and deployment status.
- Required CI must pass on the final head. The owner merges with a merge commit; never merge it
  yourself. Staging is not a merge prerequisite.
- A product change after QA's revision is verified for its affected scope before the PR is merged;
  a documentation, skill or status change is not.

## Confirming a merge and cleanup

1. `qa/scripts/sdlc merge-sync F07 --check` verifies the owner's GitHub merge live; a local merge or
   a `complete` status is not proof.
2. Fast-forward a clean checkout of `main`; run smoke on it.
3. `qa/scripts/sdlc cleanup --branch <exact>` (repeat per branch) previews; the same with `--apply`
   archives QA reports and removes only verified merged targets. Include only this feature's or
   refactor's branches; the owner authorised this cleanup. It refuses `main`, the current branch,
   dirty or locked worktrees and unmerged commits; keep any refused target and report why.
4. On the next feature's branch (or a bookkeeping branch), `qa/scripts/sdlc merge-sync F07` records
   the merge; commit it there, never on `main`. Then `start-check F08`.

## Working in parallel

- Roles share a branch through separate worktrees; only the role whose turn it is writes a given path.
- Different stages may be built at once in separate worktrees and branches. Features proceed in
  portfolio order unless the owner records `started_early`; a dependency still needs its merge.
- Keep runtime changes (clock, services, databases) out of an environment another agent is using.

## Never

- Commit to `main` directly (except the first commit), push or open a PR outside the owner's
  authorisation, merge a PR, tag or deploy without the request.
- Rewrite the history of a pushed branch.
- Run `git reset --hard`, `git clean`, `git checkout -- <path>`, forced worktree removal or forced
  branch deletion to tidy up. Uncommitted changes you did not make are someone's work.
