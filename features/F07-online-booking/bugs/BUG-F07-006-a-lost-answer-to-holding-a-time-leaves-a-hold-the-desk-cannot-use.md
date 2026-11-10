---
id: BUG-F07-006
title: "When the answer to holding a time is lost, the retry uses a new key, meets its own hold and says the time was just taken, and that time stays blocked until the hold runs out"
severity: major
status: open
found_after_merge: 2026-11-10
found_by: "exploratory: the foundation review of 2026-11-10, by reading the code; not yet reproduced on the running product"
breaks: "F07-S01 AC-1 (repeating the request with the same key returns the same booking); F07-S03 AC-6; BKG-04"
found_on: 2026-11-10
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
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

Not yet verified.
