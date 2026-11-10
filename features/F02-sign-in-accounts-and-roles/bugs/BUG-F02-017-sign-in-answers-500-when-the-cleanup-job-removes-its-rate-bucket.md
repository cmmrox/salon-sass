---
id: BUG-F02-017
title: "A staff sign-in with the right password answers 500 when the security cleanup job removes its rate bucket while the sign-in is counting the attempt"
severity: minor
status: verified
found_after_merge: 2026-11-10
found_by: "TC-F04-S03-032 (one HTTP 500 on a staff sign-in after a clock move, 2026-11-08, qa/reports/20261108-042854-F04-48ddffe0465d); root cause reproduced on 2026-11-10 by holding the job's own DELETE open"
breaks: "F02-S01 AC-1 (valid staff credentials issue a session); IAM-01"
found_on: 2026-11-10
fixed_in: 0b4562c70d9f59f915c399698dc6907d93f7114a
fix_note: "The staff rate bucket is read for update, so the cleanup job's removal is waited for and the attempt then opens a new window instead of answering 500; the operator counter (atomic upsert) has no such gap."
developer_note: "Reproduced by StaffRateBucketCleanupIntegrationTest holding the job's DELETE open; the fix is independent of the identity lock, so BUG-F02-016's new counting step keeps it. Recorded by the lead from the developer's report: the bug file was on QA's branch when the fix was made."
verified_on: 2026-11-10
approved_by: null
found_in_release: null
---

# BUG-F02-017 A staff sign-in with the right password answers 500 when the security cleanup job removes its rate bucket while the sign-in is counting the attempt

## Steps

Account: alpha's roster Manager (`kasun.jayawardena@alphasalon.lk`, the roster password). Role: Manager. Data: none
beyond the roster. QA instance, build `7cef6f7d`.

1. Sign in once (`POST /api/v1/identity/staff/sign-in` on `alpha.localhost`). This creates the salon's
   `staff-identifier` and `staff-address` rows in `identity_rate_bucket`.
2. In another database session, run the statement the `identity-security-cleanup` job runs for alpha
   (`ExpiredSecurityState.removeStaffRateBucketsStartedBy`): `DELETE FROM identity_rate_bucket WHERE tenant_id = <alpha>
   AND dimension LIKE 'staff-%'`, and keep its transaction open for two seconds before committing.
3. While it is open, sign in again with the right password.

In normal running the job does step 2 by itself: it runs every 15 minutes for every salon and removes every staff
bucket whose window started 15 minutes or more ago, which is the bucket of anyone who has not tried to sign in from
that account or that address in the last quarter of an hour. A test clock moved forward makes the job due at once,
which is why a sign-in just after a clock move is where it was seen.

## Expected

The sign-in succeeds (200, `authenticated` for a Manager): F02-S01 AC-1. A bucket removed under it is a bucket whose
window had ended, so the attempt starts a new window, as it does when no bucket exists.

## Actual

`500 internal_error` ("Something went wrong on our side."). The sign-in after it succeeds.
Reproduced 1 time in 1 with the interleaving held open. It did not appear in 20 plain attempts of "sign in, move the
clock one hour, sign in at once" (the window is a few milliseconds), nor in a rerun of F04-S03 (3/3 passed,
`qa/reports/20261110-102137-F04-S03-ca798fab06e8`): it needs the job's delete to land between the sign-in reading the
bucket and writing it back, which is what happened once in TC-F04-S03-032.

`StaffSignInServiceImpl.attempt` reads the bucket with `findFirstByDimensionAndKeyHash` (no lock) and changes it in
memory with `countAttempt`; Hibernate's update at the next flush (the second dimension's query) finds no row, and the
`ObjectOptimisticLockingFailureException` reaches the default handler as a 500.

## Evidence

- Response: `{"detail":"Something went wrong on our side.","instance":"/api/v1/identity/staff/sign-in","status":500,"title":"Internal Server Error","code":"internal_error","traceId":"51ec41757783c5e6386451f858dd3b89"}`.
- API log, `.local/qa/logs/api.log`, trace `51ec41757783c5e6386451f858dd3b89` at 2026-11-10T04:54:00Z:
  `org.springframework.orm.ObjectOptimisticLockingFailureException: Unexpected row count (expected row count 1 but was 0)
  [update identity_rate_bucket set attempts=?,created_at=?,dimension=?,key_hash=?,updated_at=?,window_start=? where id=?]`
  at `StaffSignInServiceImpl.attempt(StaffSignInServiceImpl.java:121)` from `signIn(StaffSignInServiceImpl.java:54)`;
  caused by `org.hibernate.StaleStateException`. Request line: `POST /api/v1/identity/staff/sign-in 500 1642 ms`.
- The 2026-11-08 failure: `qa/reports/20261108-042854-F04-48ddffe0465d/008-F04-S03-hours-screens.log`, `SignIn: HTTP status
  expected 200, received 500` at `qa/web/F04/S03-hours-screens.spec.ts:308`, the Manager's sign-in just after the clock moved
  26 days.
- Code: `apps/api/src/main/java/com/salonplatform/identity/service/impl/StaffSignInServiceImpl.java:117-124`,
  `apps/api/src/main/java/com/salonplatform/identity/job/IdentityCleanupJob.java:41-46`,
  `apps/api/src/main/java/com/salonplatform/identity/repository/ExpiredSecurityState.java:36-43`. `RateBucket` has no
  version and the read takes no lock.
- The reproduction script (scratch, not committed): one sign-in, then a `psql` session holding the job's DELETE open
  for two seconds, then the sign-in that answers 500.

`StaffSignInServiceImpl.signIn` is the method BUG-F02-016 is changing (the password check moves out of the
transaction); the fix for this one is in the same lines, so one change can close both.

## Re-verification

Verified on 2026-11-10 on `148bf6af` (fix `0b4562c7`), QA instance.

- **The bug's own reproduction, as a case:** TC-F02-S01-032 (new, `qa/api/F02/S01-sign-in-pressure.spec.ts`, regression). The cleanup job's DELETE of alpha's staff buckets is held open for two seconds in a database session of its own, and a sign-in with the right password is sent 400 ms in. The held DELETE removed both buckets, and the sign-in answered 200 `authenticated`. The next sign-in succeeds and a wrong password is a plain 401. Passed in `qa/reports/20261110-115357-F02-S01-d6efa279ebc8` and `qa/reports/20261110-121103-F02-S01-399b5c500c42`.
- The case fails without the fix, with 500 `internal_error`, on a temporary local build with the eight fix commits reverted (commit d99b85b5 on a throwaway branch, deleted afterwards; the QA database was still at V33) (`qa/reports/20261110-113755-F02-S01-3fd27b0eaacf`, `qa/reports/20261110-114836-F02-S01-0559e0156b39`).
- **The case that found it:** TC-F04-S03-032, in F04-S03 `hours-screens`, passed in `qa/reports/20261110-120021-F04-S03-d073195efc33`. The neighbours of the sign-in path passed as for BUG-F02-016.
