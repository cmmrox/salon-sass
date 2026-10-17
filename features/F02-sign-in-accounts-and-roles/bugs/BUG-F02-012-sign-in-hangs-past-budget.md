---
id: BUG-F02-012
title: Staff sign-in can hang until an unresponsive API resumes instead of answering 503 within the three-second budget
severity: major
status: open
found_by: TC-F02-S04-020
breaks: AC-8
found_on: 2026-10-17
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F02-012 Staff sign-in can hang until an unresponsive API resumes instead of answering 503 within the three-second budget

## Steps

Account: a synthetic alpha staff account seeded through the local hook. Build: running API/web from `f62146f` (application identical to `3b8df96`). QA revision `0ad8fd9`.

1. Open `http://alpha.localhost:3100/workspace/sign-in`, enter the identifier.
2. Freeze the running API process (`kill -STOP`), so it accepts connections and answers nothing.
3. Enter the password and press "Sign in".

## Expected

AC-8 and the S04 design notes: API loss gives a safe, translated retry within the existing three-second shared cancellation budget; TC-F02-S04-020 requires the BFF to answer 503 in under five seconds end to end, keeping the identifier and clearing the password.

## Actual

In the recorded run the BFF `POST /identity-web/staff/sign-in` never answered while the API was frozen: the web log shows it completed 200 after 164,325 ms, only when the test's teardown resumed the API (`kill -CONT`). The browser waited until the 180 s test limit. No cancellation fired on any leg.

Intermittent: 1 of 3 observed runs of this step. It answered 503 in about 3 s in the 2026-10-17 `be7e550` run and in an isolated rerun at 03:51 UTC (503 in 3,002 ms). The bug-fix diff (`salon.ts`, `route-request.ts`) changes no deadline, so this is probably pre-existing rather than introduced by BUG-F02-010/011's fix. Not established: nothing else hit the web server during the 164 s, so a blocked web event loop cannot be told apart from an upstream call outside the deadline.

## Evidence

- Recorded run `qa/reports/20261017-091035-F02-delta-c0fa96a63f32`, group `web-resilience-address`: TC-F02-S04-020 "Test timeout of 180000ms exceeded" waiting for the sign-in response at the silent-API step (`qa/reports/20261017-091035-F02-delta-c0fa96a63f32/004-F02-S04-web-resilience-address.log`).
- Web log (main checkout `.local/logs/web.log`): `2026-10-17T03:44:07.535Z POST /identity-web/staff/sign-in 200 201 ms` (the preceding re-sign-in), then `2026-10-17T03:46:52.329Z POST /identity-web/staff/sign-in 200 164325 ms`. Isolated rerun: `2026-10-17T03:51:56.743Z POST /identity-web/staff/sign-in 503 3002 ms`.

## Re-verification

<QA, after a fix.>
