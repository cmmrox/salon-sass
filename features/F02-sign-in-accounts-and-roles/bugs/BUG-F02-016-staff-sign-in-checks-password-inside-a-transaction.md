---
id: BUG-F02-016
title: "Staff sign-in checks the password while holding a pooled database connection and a salon-wide lock, so about ten sign-ins in flight stall every salon"
severity: major
status: verified
found_after_merge: 2026-11-10
found_by: "exploratory: the foundation review of 2026-11-10, by reading the code; not yet reproduced on the running product"
breaks: "NFR-02 (booking, calendar and checkout stay available); docs/engineering/backend.md, Transactions, outbox and external calls"
found_on: 2026-11-10
fixed_in: 92601fd513040074aee2a56cfd6b3d531c06d42b
fix_note: "Staff sign-in counts, reads the account and records the outcome in three short identity transactions with the password check between them, and a password change checks and hashes outside its two; PasswordChecks refuses a check inside a transaction and allows salon.identity.password-checks.concurrent (default: the processors) at once, then 503 after 2 s. The platform-wide per-address limit across salons is left to Package 4 (decision 120)."
developer_note: "Reproduced by StaffPasswordCheckIntegrationTest: while one sign-in's password check was held open, another sign-in of the same salon waited on the identity lock and was answered 503, and the password-change check ran with a transaction open. Both pass after the fix."
verified_on: 2026-11-10
approved_by: null
found_in_release: null
---

# BUG-F02-016 Staff sign-in checks the password while holding a pooled database connection and a salon-wide lock, so about ten sign-ins in flight stall every salon

Filed by the lead from `docs/reviews/2026-11-10-foundation-review/README.md` (decision 113). The developer reproduces it first, as `bug-fix` requires; if it
does not reproduce, say so in `developer_note` and leave it for QA.

## Steps

Account: any staff account of any salon; the password may be wrong. One API instance with its default pool of 10.

1. Send 12 or more staff sign-in requests at the same instant, to one salon or spread over several.
2. While they are in flight, call any signed-in endpoint of another salon (for example `GET /api/v1/bookings`).

## Expected

Sign-in is slow by design (PBKDF2, 600,000 iterations) but holds no database connection and no lock while it checks the password, as the operator sign-in already does. Other requests are unaffected.

## Actual

By reading: `StaffSignInServiceImpl.signIn` runs the whole attempt, password check included, inside `work.run(...)` (a tenant transaction that takes the salon's identity lock). Each attempt holds one of the 10 pooled connections for the length of the hash; requests beyond the pool wait 2 s and answer 503 for every salon on that instance. The address limit is a row per salon, so one address gets its 30 attempts at every salon.

## Evidence

- `apps/api/src/main/java/com/salonplatform/identity/service/impl/StaffSignInServiceImpl.java:51-61` (the check at `crypto.matchesPassword` is inside `work.run`).
- `apps/api/src/main/java/com/salonplatform/identity/service/impl/IdentityWorkImpl.java:31-34` (transaction and lock).
- `apps/api/src/main/resources/application.yaml:22` (`maximum-pool-size: 10`).
- The fix already written once: `apps/api/src/main/java/com/salonplatform/platform/service/impl/OperatorSignInServiceImpl.java:81-89`, whose comment describes this hazard.
- Review: `docs/reviews/2026-11-10-foundation-review/api-platform.md`, H1. Decision 120 gives the rule: check outside any transaction, cap the checks in flight, add one platform-wide limit per address.

## Re-verification

Verified on 2026-11-10 on `148bf6af` (`charithm/bugfix/foundation-review`: fix `92601fd5` with PR #28's tooling), QA instance, build check `api_current: true`.

- **The bug's own steps, as a case:** TC-F02-S01-031 (new, `qa/api/F02/S01-sign-in-pressure.spec.ts`, entry `sign-in-pressure`, regression). The API runs with `SALON_IDENTITY_PASSWORD_CHECKS_CONCURRENT=1`; a check takes about 70 ms here, so with the default of 10 processors no burst a test can send without k6 would reach the two-second wait. 53 sign-ins go out at once on alpha and gamma while beta's Manager reads `/me` every 100 ms.
  - Result: no 500. 41 or 43 answered 401, 1 answered 200, 3 answered 429 exactly beyond gamma's 30, and 6 or 8 answered 503 `service_unavailable` (problem details, no internals). Each named request that got a 503 was sent again and answered as its credential says, and so was each named request after the burst (right password 200, wrong password and removed account 401). All of beta's reads answered 200; the slowest took 188 ms (`qa/reports/20261110-115357-F02-S01-d6efa279ebc8`) and 120 ms (`qa/reports/20261110-121103-F02-S01-399b5c500c42`, after the bound was tightened).
  - The case fails without the fix, on a temporary local build with the eight fix commits reverted (commit d99b85b5 on a throwaway branch, deleted afterwards; the QA database was still at V33): no 503 at all, and beta's slowest read took 1,452 ms (`qa/reports/20261110-114836-F02-S01-0559e0156b39`). So the bound on beta's reads is 1 second, which the bug fails and the fix passes with room to spare.
- **Neighbours, for the changed sign-in and password-change paths** (`StaffSignInServiceImpl`, `PasswordChecks`, `application.yaml`): F02-S01 `staff-access` (26/26) and `staff-runtime` (3/3) in `qa/reports/20261110-115357-F02-S01-d6efa279ebc8`; F02-S04 `web-functional` and `web-resilience-address` (the browser's sign-in through the BFF) in `qa/reports/20261110-115523-F02-S04-d44d23963c74`. All passed.
- Not re-run: no k6 load (the owner's rule). The platform-wide per-address limit is left to Package 4, as the fix note says.
- An observation for the lead, not part of this bug: a sign-in answered 503 has already counted against the identifier's and the address's limits (the attempt is counted first, by design), so a client that keeps trying again during a long overload can lock itself out for 15 minutes.
