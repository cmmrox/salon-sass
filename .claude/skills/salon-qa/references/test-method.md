# Test method

How testing is organised: who tests what, how tests are registered, the three kinds of QA run, what
counts as evidence and what a pass means. The product's tools, data, targets and time budgets are
in `docs/qa/test-plan.md`.

## Contents

- Who tests what
- Registering tests
- Independent tests
- Smoke, regression and release
- Evidence
- What a pass means
- When a requirement changes

## Who tests what

| Level | Owner | Proves | Runs |
| --- | --- | --- | --- |
| Unit, integration, architecture, contract, accessibility | Developer | The code does what was intended | Gates before handoff, reused while unchanged |
| Acceptance on the running product | Developer | Each criterion of the stage holds from outside | Before handoff, recorded in `handoff.notes` |
| Feature QA | QA | Every criterion and requirement from outside, tenant isolation, and the stages working together | Once per feature (`feature-verify`), dry-runs during the build |
| Smoke, regression, release | QA | What was delivered still works | See below |
| Stage QA | QA | One stage ahead of its feature | Only when the owner asks |

QA tests through the doors a user or client application uses, never by editing the build, and does
not repeat or rely on the developer's unit tests. A refactor or upgrade gets no feature QA: its proof
is the developer's gates plus smoke and, where delivered features are touched, regression.

## Registering tests

- A test file belongs to one stage or feature and is registered once, under `verification.automation`
  of that `stage.yaml` or `feature.yaml`, with its `suites`. An unregistered file never runs.
- Each test is titled with its case id and the criteria it covers (`references/test-design.md`).
- Where files live and how they are named: `docs/qa/test-plan.md`. Shared helpers go in the shared
  folder; do not copy sign-in, setup or data builders into a test.

```yaml
verification:
  automation:
    - {id: holds, tool: playwright, file: qa/api/F07/holds.spec.ts, project: api, suites: [regression]}
  manual:
    - {case: TC-F07-S02-007, covers: [AC-4], reason: "...", performed_on: 2026-10-12}
  deferred:
    - {case: TC-F07-S02-009, covers: [AC-6], reason: "...", unblocked_by: "F07-S03"}
qa_status: failing            # feature, or a stage pass the owner asked for
evidence: {last_run: qa/reports/<folder>, last_result: fail}
```

## Independent tests

A test creates its own data and does not depend on another test, on file order or on an earlier
run, so tests can run in any order and in parallel. Use unique names and times unless a collision is
the point. Control the clock; never write a test that only passes at certain times.

## Smoke, regression and release

Membership is the `suites` field of an automation entry, decided at `feature-verify` and reviewed at
`release-prepare`. A suite draws only on closed work.

| Suite | Contains | When it runs |
| --- | --- | --- |
| `smoke` | The few critical checks that prove each delivered feature is alive: the apps start, sign-in, one cross-salon refusal, one key journey per feature | Every PR, after every merge, before and after a deploy |
| `regression` | Everything that must keep working, run in parallel | Before a release; for refactors and upgrades that touch delivered features; when the owner asks |
| `release` | The slow checks: load, the production-like stack, image scans | Before any deploy and at `release-verify` |

Keep each suite inside its budget in `docs/qa/test-plan.md`: a slow test moves to `release` or is
made faster; it is not left to stretch smoke or regression. A suite that grows past its budget is a
defect in the suite.

## Evidence

- `qa/scripts/sdlc run` writes `qa/reports/<folder>/` with a log per entry and `summary.json`,
  including the tested commit. Screenshots and traces go there too. Reports are not committed;
  `evidence.last_run`, `evidence.last_result`, `integration.qa_revision`, bugs and `review.md` are.
- A pass holds while the product is unchanged since `integration.qa_revision`
  (`docs/operations/verification-evidence.md`); a later change is verified for its affected scope.
- A manual case is evidence only with `performed_on`, and is performed again when its code changes.
- No secrets, tokens or real personal data in evidence: turn traces and recordings off around sign-in.

## What a pass means

The conditions are the `qa_status` table in `salon-sdlc`'s `references/lifecycle.md`. Every manual case
was performed on the code as it is now, and a fix counts only once QA verified it. A `minor` or
`trivial` bug may stay open and is listed in the close report.

## When a requirement changes

At the feature's next pass, reread the changed specs, change the tests whose expectation changed,
delete what no longer applies and add what is new. Do not run other features because of it.
