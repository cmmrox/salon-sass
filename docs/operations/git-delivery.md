# GitHub delivery and feature integration

The owner approved this workflow on 2026-10-13. The reusable procedure is in `salon-sdlc`'s
`references/git-workflow.md`; this document holds this project's repository, authorisation,
evidence storage and transition from its earlier local workflow.

## Repository and authorisation

- Repository: `https://github.com/cmmrox/salon-sass.git`; remote `origin`; trunk `main`.
- Branch prefix: `charithm/`, keeping the existing convention.
- The owner authorises pushing reviewed feature and process changes and opening the corresponding
  GitHub PRs. Feature PRs are created after independent full feature QA and closure gates pass.
- The owner reviews and merges PRs into `main`. Agents may integrate stage/QA branches into the
  feature branch locally, but do not merge the owner's GitHub PRs or push directly to `main`.
- Post-merge cleanup of related local/remote branches and worktrees is authorised when the
  lifecycle command proves they contain no work outside `origin/main`. It requires no renewed
  permission, but an unsafe target is retained with the reason reported.
- Tags, staging/production deployments, real messages and payments need their separate requests.
  A push, a passing QA run, or a PR merge does not authorise deployment.

## Feature and merge records

`feature.yaml` keeps `status` and `qa_status`, and an `integration` mapping:

| Field | Written by | Means |
| --- | --- | --- |
| `branch` | Lead | This feature's integration branch, e.g. `charithm/feature/F07-online-booking` |
| `pull_request` | Lead | The single feature PR into GitHub `main` |
| `qa_revision` | QA | Full SHA whose product, tests and pipeline were checked |
| `merged_commit`, `merged_at` | `merge-sync`, run by lead | Merge commit and timestamp verified from GitHub and freshly fetched `origin/main` |

`complete` means QA-passed and awaiting submission/merge, not merged or deployed. The portfolio
shows GitHub merge separately. Features start in portfolio order after earlier features are
confirmed merged; explicit dependencies also need a confirmed merge. An owner's instruction in
`started_early` may change portfolio/milestone order, but does not waive a dependency's merge.

Use `pr-check` before submission, `merge-sync --check` to verify without editing, and
`start-check` to recheck preceding merges live. A manually typed merge SHA never substitutes for
the live start check. The merge commit is included in `origin/main`, and the PR head contains the
QA revision without changes to the certified paths listed in `docs/architecture/repository-structure.md`.
The live check also requires successful `Verification commands` CI on the PR's head revision.

A merge happens after the PR's own metadata commit. Record its resulting SHA on a bookkeeping,
release or next feature branch, while the next feature is still in the backlog. That record is
included in the next PR; it is not pushed directly to `main` and does not need its own PR solely
to record a prior merge. The fresh `start-check` runs before feature planning/building.

## GitHub settings

When the repository/account supports protection, require a PR and successful `Verification commands`
CI on `main`, include administrators in the protection, and disallow force pushes/deletion of
`main`. Keep merge commits enabled. Do not require a successful staging deployment before merge.

The agent may authenticate as the owner, so a mandatory separate approving reviewer is configured
only if another eligible reviewer exists. The owner remains the manual merger. Protection is
separate from these files: report whether it was actually applied, never assume documentation
configured GitHub. The lifecycle's merge gates still apply when the GitHub plan lacks protection.

## Evidence and cleanup

`qa/reports/`, `qa/test-results/` and `qa/playwright-report/` remain uncommitted. Before removing a
worktree, `qa/scripts/sdlc cleanup --branch <exact-name> --apply` copies its evidence to
`<git-common-dir>/sdlc-evidence/qa-<unique>/`, verifies the copied contents, and writes
`<git-common-dir>/sdlc-evidence/last-cleanup.json` with source worktrees and archive paths.
For the main checkout, `<git-common-dir>` is `.git`, which survives removal of linked worktrees.
Each archive also retains its own `receipt.json` with its source branch, revision and worktree.
The command prints the receipt, including branches and archive paths; retain it with the close report.

Cleanup previews by default and preflights every explicitly named target before removing any.
It never uses forced local branch deletion or forced worktree removal. Ignored credentials,
environment files or unknown local files block removal until preserved; local service pid files
block removal until the owning services stop. Unrelated branches/worktrees are untouched.

## Existing-history transition

Before this decision, F01-S01 through F01-S04 were integrated into local `main` under decision 019.
Preserve that history. The empty GitHub repository may be bootstrapped by pushing existing `main`
at `36e1111` once; this is an explicit exception to the PR-only push rule. It is a historical
baseline, not certification that F01 is complete, QA-passed, released or deployed.

The current S05 worktree and its commits stay intact. After the process-update PR is merged,
create `charithm/feature/F01-platform-foundation` from updated `origin/main`, integrate S05 when
its developer gates and stage closure pass, and run independent full F01 QA on the aggregate.
The F01 PR contains the remaining work and full feature QA, even though earlier stages are
already in the historical baseline. F02 still waits for that F01 PR's confirmed GitHub merge.

Legacy merged S02-S04 branches/worktrees may be cleaned only by the same guarded command once
their commits are in `origin/main`. Keep active S05 and any dirty worktree. Never reset, rewrite,
or force-move the existing branches to make the transition look like it happened earlier.
