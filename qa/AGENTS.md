# qa

QA's territory: automation, shared fixtures and release manifests. `qa/scripts/` is the lifecycle
tooling and belongs to the lead. Before writing or running anything here, load the `salon-qa` skill
and read in full:

- `docs/qa/test-plan.md`: tools, layout of this folder, the synthetic salons, accounts, targets.
- `docs/qa/test-types.md`: which kinds of test a stage needs, and what each must assert.
- `docs/qa/automation.md`: conventions for Playwright, k6, shell checks and Maestro.

Run automation through `qa/scripts/sdlc run`, for exactly the scope you were asked for.
