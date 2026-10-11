# QA

QA automation, shared fixtures, release manifests and the lifecycle tooling. The method is in the
`salon-qa` skill; how this product is tested (tools, layout, test data, test types, targets) is in
`docs/qa/`. This file is a map.

| Path | Holds | Created |
| --- | --- | --- |
| `scripts/sdlc` | The lifecycle tooling: `validate`, `generate`, `status`, `run`, `selftest` | Present |
| `scripts/guard.py` | The path-ownership check for the developer and QA roles | Present |
| `coverage.md` | Requirement and test coverage. Generated; do not edit | Present |
| `web/`, `api/` | Playwright browser and API tests, by feature | By QA in F01-S01 |
| `mobile/` | Maestro flows for the customer app, by feature | From milestone `M2-mobile` |
| `load/` | k6 load, stress and concurrency scripts, by feature | By QA in F01-S02 |
| `checks/` | Shell checks for what a browser or API test cannot do, by feature | By QA in F01-S01 |
| `package.json` | QA's own package and lock file, separate from the product's workspace | By QA in F01-S01 |
| `shared/` | Fixtures, the three synthetic salons, accounts, helpers | By QA in F01-S01 |
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
