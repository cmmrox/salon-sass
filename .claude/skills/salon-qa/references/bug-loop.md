# Failures, bugs and the fix loop

## Contents

- Triage: three outcomes
- Filing a bug
- Severity
- Statuses and who sets them
- The loop
- Re-verifying
- Disagreements
- Reporting a pass

## Triage: three outcomes

Every failure is exactly one of these. Decide which before changing anything.

| Outcome | How to tell | What to do |
| --- | --- | --- |
| **Product defect** | The product did something the spec, the requirement or plain sense says it should not. It happens again when repeated by hand | File a bug. Do not rerun hoping, do not adjust the test |
| **Environment fault** | The product was never really exercised: a service was down, the wrong build was installed, a fixture was missing or stale, a tool crashed, the network dropped | Repair it, rerun what it affected, and record what was repaired. The rerun's result is the result |
| **Test defect** | The test is wrong: a bad selector, a race in the test, a wrong expectation, a step for a dialog that did not appear | Fix the test, rerun it, and say so. If the expectation was wrong, correct the case in `cases.md` too |

Rules that follow:

- Never rerun a real assertion failure to see if it passes. A result that changes between runs is a
  defect in the product or the test, and both are worth finding.
- A repaired environment fault counts as a pass on the repaired environment. Say what was repaired;
  never hide it.
- An environment fault you cannot repair yourself is not a failure of the product and not a reason
  to defer cases. Set `qa_status: blocked` with `blocked_reason`; the lead removes the blocker.
- Rerun only what changed: a repaired fixture reruns the tests that used it; changed application
  code reruns the stage.
- Do not edit a test while a run is in progress.

## Filing a bug

One file per defect: `<stage folder>/bugs/BUG-F07-S02-003-<slug>.md`, from `assets/bug.md`. Use the
next free number in that stage. A defect found in feature QA goes in `features/<feature>/bugs/`
with an id like `BUG-F07-003`.

A bug the developer can act on without asking has:

- a title that states the wrong behaviour ("A second customer can reserve a slot that is already reserved");
- the case that found it and the acceptance criterion or requirement it breaks;
- exact steps, with the account, the role and the data;
- what was expected and what happened, with the evidence (response body, screenshot, log line);
- how often it happens.

A bug found by exploring has no case yet. Write the case when you file the bug. If the bug is fixed
in this pass, automate the case at re-verification. If it is `minor` or `trivial` and is being left
open, declare the case and list it under `verification.deferred`, unblocked by the bug's id, so the
stage run stays honest and the case is not forgotten.

One defect per bug. Two symptoms with one cause are one bug; one screen with two unrelated faults
is two.

A missing test hook (no stable test id, no way to control the clock, an error with no stable code)
is a bug too: `major` when it blocks a case or an acceptance criterion asks for the hook, `minor`
otherwise.

## Severity

| Severity | Meaning |
| --- | --- |
| `blocker` | Someone's data reaches a person who must not see it; money is wrong; something a user did is lost or duplicated; the stage's main path cannot be completed |
| `major` | An acceptance criterion fails, a role can do what it must not, or a common path is broken with no reasonable workaround |
| `minor` | The requirement is met but something is wrong at the edges, or there is an easy workaround |
| `trivial` | Cosmetic |

Examples for this product are in `docs/qa/test-plan.md`, under "Severity examples".

Severity describes the effect on the people who use the product. It is never lowered to let a stage pass.
`blocker` and `major` bugs stop `qa_status: pass`.

## Statuses and who sets them

| Status | Set by | Meaning |
| --- | --- | --- |
| `open` | QA | Found, waiting for the developer |
| `fixed` | Developer | A fix is committed; `fixed_in` and `fix_note` are filled in |
| `verified` | QA | The fix was re-tested and holds |
| `reopened` | QA | The fix did not hold, or broke something in the same stage |
| `not-a-bug` | QA | The product was right; the test or the expectation was wrong and has been corrected |
| `deferred` | Lead, with `approved_by` | The owner accepted leaving it for later. Minor or trivial only; the validator refuses it on a blocker or major |
| `wont-fix` | Lead, with `approved_by` | The owner decided the behaviour is acceptable as it is. Any severity, and only ever the owner's decision |

## The loop

```
QA runs the stage  ->  files bugs, sets qa_status: failing
Developer fixes each open or reopened bug, sets it fixed
QA re-verifies  ->  verified or reopened
... until no blocker or major bug is unresolved and the stage run passes  ->  qa_status: pass
```

There is no limit on rounds. The loop ends when the product is right, not when it has been tried
enough times. If the same bug is reopened twice, tell the lead: the spec may be unclear.

## Re-verifying

For each `fixed` bug:

1. Repeat the bug's own steps by hand or with its case. The original symptom is gone.
2. Run the case that found it, then the whole stage: `qa/scripts/sdlc run --stage <F-S>`. A fix that
   breaks a neighbour in the same stage is `reopened`.
3. Check that the case would catch it again. If the bug was found by exploring and no case covers
   it, add one.
4. Set `verified` with the run folder as evidence, or `reopened` with what still happens.

Re-verification stays inside the stage. It does not run earlier stages or other features.

A defect found after its stage was closed (in feature QA, in a release run, or in a shipped
version) is filed against the feature; the table is in `lifecycle.md` in `salon-sdlc`, under "Bugs
found after a stage is closed".

## Disagreements

If the developer writes a `developer_note` saying the product is right and the test is wrong, read
the requirement again. If they are right, fix the case and the test and set the bug to `not-a-bug`,
saying what was wrong with the test. If you still think the product is wrong, or the requirement is ambiguous, take
it to the lead; the owner decides what the requirement means. Neither side edits the other's work
to end the argument.

## Reporting a pass

Say what was run and its result, the bugs found and their final state, what is manual or deferred
and why, the environment faults repaired along the way, and the minor bugs left open. Leave nothing
for the reader to assume.
