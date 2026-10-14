# QA

QA automation, shared fixtures, release manifests and the lifecycle tooling. The method is in the
`salon-qa` skill; how this product is tested (tools, layout, test data, test types, targets) is in
`docs/qa/`. This file is a map.

| Path | Holds | Created |
| --- | --- | --- |
| `scripts/sdlc` | Lifecycle tooling: `validate`, `generate`, `status`, `run`, `selftest`, `pr-check`, `merge-sync`, `start-check`, `cleanup` | Present |
| `scripts/guard.py` | The path-ownership check for the developer and QA roles | Present |
| `coverage.md` | Requirement and test coverage. Generated; do not edit | Present |
| `web/`, `api/` | Playwright browser and API tests, by feature | Present |
| `mobile/` | Maestro flows for the customer app, by feature | From milestone `M2-mobile` |
| `load/` | k6 load, stress and concurrency scripts, by feature | By QA in F01-S02 |
| `checks/` | Shell checks for what a browser or API test cannot do, by feature | Present |
| `package.json` | QA's own package and lock file, separate from the product's workspace | Present |
| `playwright.config.ts`, `tsconfig.json`, `.env.example` | The Playwright projects `api` and `web`, the path alias to the generated contract, and the keys `qa/.env.local` may provide | Present |
| `shared/` | Fixtures and helpers. The three synthetic salons arrive with F01-S02 and accounts with F02 | Present |
| `releases/<version>/` | Release manifests and results | At the first release |
| `reports/` | Run output. Never committed | By each run |

```bash
qa/scripts/sdlc status                      # whose turn it is
qa/scripts/sdlc validate                    # the gate
qa/scripts/sdlc run --stage F01-S01         # run one stage's automation, and nothing else
qa/scripts/sdlc run --stage F01-S01 --dry-run
```

Regression and smoke (`--suite regression`, `--suite smoke`, `--release <version>`) run only when
the owner asks.

Feature integration and post-merge cleanup follow `docs/operations/git-delivery.md`. `complete`
means QA-passed; it does not permit the next feature until its GitHub merge is confirmed. Cleanup
archives local QA evidence outside a worktree before removing it. It previews by default:

```bash
qa/scripts/sdlc pr-check F07
qa/scripts/sdlc merge-sync F07 --check
qa/scripts/sdlc start-check F08
qa/scripts/sdlc cleanup --branch charithm/feature/F07-online-booking
```

## Before a run

```bash
pnpm --dir qa install --ignore-workspace    # once, and after qa/package.json changes
pnpm --dir qa exec playwright install chromium
infra/scripts/local-up.sh                   # the product must be running
```

A run does not start the product. Every automation entry checks that it is there first and, if it
is not, stops with a message that begins `ENVIRONMENT:` (a shell check exits 3). That is a fault of
the environment, not a failure of the product: start the product and run again. A run stops and
starts the product's services and empties the local database through the scripts in
`infra/scripts/`, and leaves the product running when it ends.

F02 future-clock cases use a lead-approved local fixture cleanup in
`qa/shared/identity-clock-cleanup.ts`. Before setting test time it proves the local database has
exactly alpha/beta/gamma, snapshots the unlocked `identity-security-cleanup` and `outbox-cleanup`
rows, and records safe control marks. After restoring real time it changes only `next_run_at`
values attributable to that case's recorded future clock, under an exact whole-row and unlocked
transaction guard. It checks affected counts, preserves history/locks/all other records, and
observes due handlers resume or their original ordinary schedule remain. Unexpected state stops
cleanup with an environment fault. This is test-fixture cleanup, not a relaxed timing assertion;
no remote database or application hook is involved.

## F02 bounded local read baseline

`TC-F02-004` is registered once as `qa/checks/F02/F02-read-baseline.sh`. The wrapper uses Corepack Playwright for typed real API fixture preparation and runs the actual k6 thresholds in `qa/load/F02/identity-reads.js`. The lead approved an address ceiling of 200 only while creating the synthetic data. It restores the documented 3/10/20/1000 limits, proves the API restarted with those values and retained keys, and then measures ten concurrent users for twenty seconds. There are exactly five staff roles and fifty customers in each fixed salon. Existing immutable audit remains; each salon has at least one hundred entries, with actual totals recorded. Credential files have mode 0600 under ignored `qa/.auth/` and are removed in finally. The ordinary fixture cleanup removes mutable identities. Preparation, authentication, hashing, delivery and mutation time are excluded from the measured per-read p95 below 300 ms. k6 also observes the connection pool. This is a local baseline, with machine and data volume recorded, and does not certify a production target. Draft automation has not been executed.
