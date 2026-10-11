# Automation principles

The rules that make an automated test worth trusting, whatever the tool. This product's tools, file
layout and conventions are in `docs/qa/automation.md`.

## Contents

- One test, one case
- Reliability rules
- Selecting and waiting
- Tests of things that must happen once
- Load tests
- Tests on a device
- Running and recording

## One test, one case

- One test proves one case, and carries the case id in its title so a report line maps to `cases.md`.
- Arrange through the fastest honest route (usually the API), act through the surface under test,
  assert the outcome the acceptance criterion promises.
- Register the file under `verification.automation` with every case id it contains.
- No test depends on another test, on the order of files, or on data left by an earlier run.

## Reliability rules

- **A test that passes on retry has failed.** Retries are off; find the cause.
- **Assert there was something to count before asserting on the count.** A scrape that found nothing
  must fail, not report "0 of 0 correct".
- **See it fail.** When a new test goes green first time, break the behaviour once, or point the
  test at the wrong expectation, and watch it go red. Then restore it.
- **Scope an assertion to what it is about.** "No error text anywhere on the page" fires on correct
  pages and gets ignored.
- **Assert the outcome, not the arrival.** That a screen opened proves only that it opened.
- **Check twice where data changed:** where the user would see it, and once more through the API.
- **When your own test goes red, establish whether the product or the test is wrong before changing
  either.**
- **A missing credential or fixture fails loudly**, naming what is missing. Never skip, and never
  assume a sign-in worked because a credential exists.

## Selecting and waiting

- Select the way a user perceives the element (role and name) first, a stable test id second. Never
  by styling, by text a translation will change, or by position.
- An element with no stable handle is a testability bug for the developer. Do not work around it
  with a fragile selector.
- Wait for the thing you are about to assert, never for a fixed time.
- Run headless while iterating. Before calling a visual stage passed, watch the flow once at real
  speed: something that opens, closes and reopens is invisible to assertions and obvious to the eye.

## Tests of things that must happen once

When two actors can reach the same thing at the same instant, the question is whether the result is
still correct.

- Fire the attempts together, many at once. One attempt at a time, or staggered attempts, prove
  nothing.
- Assert the invariant, not the responses one by one: exactly one succeeded, every other was told
  why, and nothing was left half-done.
- Put the invariant where the tool will fail the run on it. A soft check that only prints is not a
  test.
- Verify the end state by reading it back afterwards, not only by counting responses.
- Correctness under a race is the same on any machine, so a local result is a real result.

## Load tests

- State the scenario with the result: users, ramp, duration, and the amount of data it ran on.
- Seed realistic volume first; everything is fast on empty tables.
- Put the target in the tool's pass or fail thresholds.
- A result from a developer's machine is a baseline to compare the next run with, not a
  certification of a target. Say so when reporting it.
- Never point a load test at anything but the local machine unless the owner's current message names
  the target.

## Tests on a device

A flow can only fail meaningfully if it could have passed. Before a run, prove in order that the
device is ready, the installed build is the one for this code, the test driver can reach the
device, the backend is healthy, and the data the flow needs exists and is fresh. The exact gates
for this product are in `docs/qa/automation.md`.

If a gate fails, repair it and do not start the run. If you cannot repair it yourself, set
`qa_status: blocked` with a `blocked_reason`; do not defer the cases to get the stage through. A
platform that was not run is recorded, never implied: declare a case for it and list it under
`verification.deferred` with the reason and what unblocks it.

## Running and recording

While writing a test, running that one file directly is fine. The recorded result of a QA pass
always comes from `qa/scripts/sdlc run`, which selects by scope, refuses an empty selection and
writes the evidence. A missing tool is reported as an environment fault, not as a pass and not as a
product failure.
