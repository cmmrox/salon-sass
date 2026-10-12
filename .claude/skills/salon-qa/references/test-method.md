# Test method

How testing is organised in this lifecycle: who tests what, how automation is registered, what
counts as evidence and what a pass means. The product's own tools, test data and targets are in
`docs/qa/test-plan.md`.

## Contents

- Who tests what
- Registering automation
- Independent tests
- Suites: regression and smoke
- Evidence
- What a pass means
- When a requirement changes

## Who tests what

| Level | Owner | Proves | Runs |
| --- | --- | --- | --- |
| Unit, integration, architecture, contract | Developer | The code does what the developer intended | Every build, through `verification.commands`; again at `stage-close` and `feature-close` |
| Acceptance, on the running product | Developer | Each acceptance criterion of the stage holds when used from outside | Before handing a stage over; recorded in `handoff.notes`, which the stage is closed on |
| Feature QA | QA | Every stage meets its acceptance criteria and requirements from outside, and the stages work together as something a person can use | `feature-verify`, `feature-close` |
| Stage QA | QA | One stage, ahead of its feature | Only when the owner asks: `stage-verify` |
| Regression and smoke | QA | What was delivered earlier still works | Only when the owner asks, and at `release-verify` |

QA tests a feature once, when all its stages are closed. Nothing is tested by QA stage by stage
unless the owner asks for it.

QA tests through the same doors a user or a client application uses. QA does not repeat the
developer's unit tests and does not rely on them. A criterion about the build itself (every string
in a translation file, a check that fails on a stale file) is tested with a script that inspects the
build, never by editing it.

## Registering automation

- An automation file belongs to exactly one stage or feature and is registered once, under
  `verification` in that `stage.yaml` or `feature.yaml`. A file that is not registered is never run
  by the lifecycle.
- Where each kind of file lives, and how it is named, is in `docs/qa/test-plan.md`.
- Shared helpers go in the shared folder. Do not copy sign-in, setup or data builders into a test.

The fields QA writes. `cases` and `verification` are the same in `stage.yaml` (a stage's criteria)
and `feature.yaml` (the journeys); `qa_status` and `evidence` are set on the feature, and on a stage
only by a stage pass the owner asked for. The full templates are `assets/stage.yaml` and
`assets/feature.yaml` in `salon-sdlc`:

```yaml
qa_status: failing            # not-run | failing | blocked | pass
blocked_reason: null          # required when qa_status is blocked
cases:
  - {id: TC-F07-S02-001, title: "...", type: api, priority: high, covers: [AC-1]}
verification:
  automation:
    - id: holds                 # unique within the stage
      tool: playwright          # one of the tools in docs/qa/test-plan.md
      file: <path under qa/>
      project: api              # only for a tool that groups tests into projects
      covers: [TC-F07-S02-001]
      suites: [regression]      # regression | smoke; decided at feature-verify
  manual:
    - {case: TC-F07-S02-007, reason: "...", performed_on: 2026-10-12}
  deferred:
    - {case: TC-F07-S02-009, reason: "...", unblocked_by: "F07-S03"}
evidence:
  last_run: qa/reports/<folder>
  last_result: pass             # pass | fail
```

## Independent tests

A test creates the data it needs and does not depend on another test having run, on the order of
files, or on data left by an earlier run. Tests may run in any order and, where the tool allows, at
the same time. Use unique names and times so that parallel tests do not collide unless the
collision is the point of the test. Control the clock; never write a test that only passes at
certain times.

## Suites: regression and smoke

Suite membership is a field on an automation entry, `suites: [regression, smoke]`, decided at
`feature-verify` and reviewed at `release-prepare`. There is no separate list to keep in step.

| Suite | Contains | Size |
| --- | --- | --- |
| `regression` | Everything that must keep working once delivered | As large as it needs to be |
| `smoke` | The few checks that prove each delivered feature is alive | A few minutes in total |

A suite draws only on stages and features that are `complete` or `released`. What smoke means for
this product is in `docs/qa/test-plan.md`.

## Evidence

- `qa/scripts/sdlc run` writes a folder under `qa/reports/` with a log per automation entry and a
  `summary.json`. Screenshots, traces and recordings go in the same folder.
- `qa/reports/` is not committed. What is committed is `evidence.last_run` (the folder's path,
  `qa/reports/<folder>`) and `evidence.last_result` in the yaml, the bug files, and `review.md`.
- A manual case is evidence only with a `performed_on` date on its entry under
  `verification.manual`. Perform it again when the code it covers changes.
- Evidence must not contain secrets, tokens or real personal data. Turn off traces and recordings
  around sign-in.

## What a pass means

The conditions for `qa_status: pass`, `failing` and `blocked` are the table "Which `qa_status` to
set", in `lifecycle.md` in `salon-sdlc`. Two points that table does not spell out: every manual case
must have been performed on the code as it is now, and a fix counts only once QA has verified it.

A `minor` or `trivial` bug may stay open; it is listed in the feature's close report.

## When a requirement changes

A change request tells you which stages it touches. At the feature's next pass, for each one:
reread the changed spec, edit the cases whose expectation changed, delete the cases and automation
that no longer apply, and add what is new. Do not run other features because of it.
