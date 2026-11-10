---
id: BUG-F07-003
title: A hold whose answer arrives after the New appointment sheet was closed, or after a field was changed, is never released and stays on the time until it runs out
severity: major
status: verified
found_by: "TC-F07-S03-020; PR #26 review (Codex, use-new-appointment.ts holdTime)"
breaks: AC-6
found_on: 2026-11-10
fixed_in: 15e9917b922834b5001d6a6efb53e1b6de93c3ea
fix_note: "A hold answered after the sheet closed or the form changed is released at once (kept-alive release, else booking-release) and never adopted or confirmed; the change also clears busy and the attempt, so the next time chosen gets a new Idempotency-Key."
developer_note: null
verified_on: 2026-11-10
approved_by: null
found_in_release: null
---

# BUG-F07-003 A hold whose answer arrives after the New appointment sheet was closed, or after a field was changed, is never released and stays on the time until it runs out

## Steps

Account: a salon's Owner (the QA fixture's provisioned salon; Manager and Receptionist use the same sheet). Data: one
professional performing "Cut and blow-dry", open 09:00 to 19:00 every day. QA instance (web 3200, API 8190).

A. Closing:

1. Open `/workspace/bookings`, **+ New appointment**; fill the form for a day two days ahead and **Find free times**.
2. Choose the first chip. The API places the hold at once; its answer to the page (`POST /identity-web/staff/booking-place`)
   is held back by the test until step 3 is done (on a slow connection the same window lasts as long as the request).
3. Close the sheet (**Close**, then leave on the unsaved-edits question).
4. Let the answer arrive. Read the salon's live holds.

B. Changing a field: steps 1 and 2 again, then change the customer's name, then let the answer arrive.

## Expected

F07-S03 AC-6: "Closing the sheet with a hold releases it." AC-5: changing any field clears the times, and the design notes
say the sheet owns its one hold and releases it on close; a changed field gives back the held time (as `change()` does for
a hold already shown). A hold that is answered after the sheet was closed, or after the form changed, is released, and the
sheet does not show "Holding this time" for a form that no longer matches it. In the test: within 10 seconds of the answer,
no new hold is left.

## Actual

A: after closing, the hold stays `held` on the professional's time (still there 10 seconds after the answer arrived, the
test's limit); nothing releases it, so the time is taken from every other desk and from availability until the hold runs
out (10 minutes by default). The person was told nothing was held.

B: after the name was changed, the late answer is stored as the sheet's hold: "Holding this time" is shown beside the
changed form and the hold stays `held` (observed). That hold was placed with the values from before the change; by the
code, **Confirm booking** confirms it by its id, so it would book the earlier values, not what the form now says
(confirming was not tried in the test).

Happens every time the answer lands after the close or change: 2 of 2 attempts of each part (the test alone, and the
recorded stage run).

## Evidence

Recorded run: qa/reports/20261110-050643-F07-S03-2411f94895b7, entry 002 new-appointment-web (002-F07-S03-new-appointment-web.log), TC-F07-S03-020 failed with three soft assertions:

```
the late hold is released, not left on the time            Expected: 0  Received: 1  (10 s)
the late hold is released, not kept for the changed form   Expected: 0  Received: 1  (10 s)
no "Holding this time" for a form that changed after the time was chosen
  getByTestId('new-appointment-hold')                      Expected: 0  Received: 1
```

Screenshot: qa/reports/20261110-050643-F07-S03-2411f94895b7/playwright/2026-11-09T23-37-23-844Z-14038/web-F07-S03-new-appointmen-e696e-m-that-no-longer-matches-it-web/test-failed-1.png.

Where: `apps/web/src/surfaces/workspace/use-new-appointment.ts`, `holdTime` (about line 269): `releaseHold()` (on close or
change) finds no hold id while `booking-place` is in flight, and the late success then sets `holdId` and `hold` without
checking that the sheet is still open and the form unchanged.

## Re-verification

2026-11-10, on a5a7d8fe (fix 15e9917b; only apps/web/src/surfaces/workspace/use-new-appointment.ts and its screen test).
QA instance rebuilt and restarted at a5a7d8fe (the fix merged; e0c1d4bc adds only QA test repairs): API PID 84142, /api/v1/meta commit a5a7d8fe; web PID 84363, apps/web/.next-qa built 06:00 (+05:30) from the clean tree. The old processes (web 67534, API 4997) were stopped by their exact PIDs.

1. The bug's own steps, both parts, with the hold's answer held back (qa/reports/20261110-060856-F07-S03-d5c497afa957/BUG-F07-002-003-004-steps.log): A, the sheet
   closed: 50 ms after the late answer the page sent `POST /identity-web/staff/booking-release` and the hold was no longer
   live. B, the name changed: the same release followed at once, no "Holding this time" was shown and no chips; a time
   chosen again then held it for the changed form (the card names the new customer). The symptom is gone.
2. `qa/scripts/sdlc run --stage F07-S03`: 21 of 21 cases passed, TC-F07-S03-020 included (all three of its assertions),
   with TC-F07-S03-007 to -009 and -018 (hold, confirm, choose another, close with a hold, double click, taken meanwhile,
   expiry), which the change to `holdTime`, `change` and `close` touches. Report: qa/reports/20261110-060856-F07-S03-d5c497afa957.
3. TC-F07-S03-020 is the test that would catch it again: it failed on aeab2c30's product and passes on a5a7d8fe.

Result: verified.
