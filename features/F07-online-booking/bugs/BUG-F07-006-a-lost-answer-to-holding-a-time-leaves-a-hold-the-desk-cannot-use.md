---
id: BUG-F07-006
title: "When the answer to holding a time is lost, the retry uses a new key, meets its own hold and says the time was just taken, and that time stays blocked until the hold runs out"
severity: major
status: verified
found_after_merge: 2026-11-10
found_by: "exploratory: the foundation review of 2026-11-10, by reading the code; not yet reproduced on the running product"
breaks: "F07-S01 AC-1 (repeating the request with the same key returns the same booking); F07-S03 AC-6; BKG-04"
found_on: 2026-11-10
fixed_in: e80c1a9d8cc405fa9f258f06f9b2da7ffade0e58
fix_note: "New appointment ends a hold attempt, and its Idempotency-Key, only on an answer from the API; on service_unavailable or browser_unsupported (a dropped connection, the BFF's time limit) the same time chosen again sends the same key and receives the hold already placed. A definite refusal, another time or a changed form still gets a new key."
developer_note: "Reproduced by BookingsScreen.test.tsx ('when the connection drops' / 'when the BFF gives up on the API'): the second attempt sent a new key. After a lost answer the screen still says the service is unavailable and does not search again; the time stays offered."
verified_on: 2026-11-10
approved_by: null
found_in_release: null
---

# BUG-F07-006 When the answer to holding a time is lost, the retry uses a new key, meets its own hold and says the time was just taken, and that time stays blocked until the hold runs out

Filed by the lead from `docs/reviews/2026-11-10-foundation-review/README.md` (decision 113). The developer reproduces it first, as `bug-fix` requires; if it
does not reproduce, say so in `developer_note` and leave it for QA.

## Steps

Account: a Receptionist. Data: a service with free times today.

1. In New appointment, find free times and choose one, with the network cut after the request leaves and before the answer arrives (Playwright: abort the response of the place request once).
2. Restore the network and choose the same time again.

## Expected

The second attempt sends the same Idempotency-Key, receives the hold the first attempt placed, and the desk continues to Confirm.

## Actual

By reading: on any failure, a lost connection included, the hook discards the attempt's key (`attempt.current = null`). The retry sends a new key, the API refuses it 409 `slot_taken` because of the first attempt's own hold, the times are searched again without that time, and the orphan hold blocks it for the hold length (10 minutes by default).

## Evidence

- `apps/web/src/surfaces/workspace/use-new-appointment.ts:303-329` (the key cleared at `:323`).
- Review: `docs/reviews/2026-11-10-foundation-review/web.md`, M1. Keep the key when the failure is not an answer from the API.

## Re-verification

Verified on 2026-11-10 on `148bf6af` (fix `e80c1a9d`), QA instance.

- **The bug's own steps in the browser, as a case:** TC-F07-S03-022 (new, `qa/web/F07/S03-new-appointment.spec.ts`, regression).
  - The place request reaches the API, which places the hold, and its answer is aborted once.
  - Choosing the same time again sends the same `idempotencyKey`, gets the hold already placed, and Confirm makes it a confirmed booking. No hold is left.
  - After a definite refusal (`slot_taken`, a time taken meanwhile), trying that time again sends a new key.
  - Passed with the rest of `new-appointment-web` (9/9) in `qa/reports/20261110-120402-F07-S03-2f75bd31f05c`.
- The case fails without the fix (a new key, and no hold for the desk) on a temporary local build with the eight fix commits reverted (commit d99b85b5 on a throwaway branch, deleted afterwards; the QA database was still at V33): `qa/reports/20261110-114221-F07-S03-dc3a914dad44`.
