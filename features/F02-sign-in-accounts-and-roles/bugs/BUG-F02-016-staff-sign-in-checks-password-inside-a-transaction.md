---
id: BUG-F02-016
title: "Staff sign-in checks the password while holding a pooled database connection and a salon-wide lock, so about ten sign-ins in flight stall every salon"
severity: major
status: open
found_after_merge: 2026-11-10
found_by: "exploratory: the foundation review of 2026-11-10, by reading the code; not yet reproduced on the running product"
breaks: "NFR-02 (booking, calendar and checkout stay available); docs/engineering/backend.md, Transactions, outbox and external calls"
found_on: 2026-11-10
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
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

Not yet verified.
