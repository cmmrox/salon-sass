---
id: BUG-F02-012
title: Staff sign-in can hang until an unresponsive API resumes instead of answering 503 within the three-second budget
severity: major
status: verified
found_by: TC-F02-S04-020
breaks: AC-8
found_on: 2026-10-17
fixed_in: 881ca87
fix_note: 'Root cause: the API deadline reached the socket only through copied Requests whose abort signals follow their parent weakly; a GC while the API was silent dropped the abort. withTraceparent now enforces the request budget with a strongly held timer and hands its signal to the transport directly (fetchForHostname uses it as given). Evidence .local/logs/bug-f02-012-before.log (11/11 red with forced GC), bug-f02-012-repro-prefix.log (frozen API: no answer in 30 s, 3/3), bug-f02-012-after-runtime.log (503 in about 3.0 s, 5/5).'
developer_note: null
verified_on: 2026-10-17
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

Verified 2026-10-17 on the handoff: running API/web from `881ca87` (merged as `c78b909`, identical source; the API restarted during the run reports `c78b909`), QA revision `c78b909`. Recorded F02-delta `qa/reports/20261017-094849-F02-delta-584884e9bba2`: all 6 web groups, 28 tests pass, including TC-F02-S04-020 (down and frozen API, shared-budget seam). Because the defect was intermittent (1 of 4), the frozen-API step was also repeated 10 times directly: freeze the API (`kill -STOP`), POST `/identity-web/staff/sign-in` with same-origin headers, resume. All 10 answered 503 in 3.002–3.007 s; the same request with the API live answered 400 in 0.08 s, so it passes CSRF and the 503 comes from the upstream deadline. Log `qa/reports/20261017-BUG-F02-012-frozen-api-probe/probe.txt`.
