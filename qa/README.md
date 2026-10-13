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
