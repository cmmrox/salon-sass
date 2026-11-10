---
id: BUG-F07-005
title: '"Most booked" counts confirmed bookings still to come, so a service booked only for the future outranks one booked in the last 90 days'
severity: minor
status: verified
found_by: "TC-F07-S01-039; PR #26 review (Codex, BookingRepository.countByService)"
breaks: AC-13
found_on: 2026-11-10
fixed_in: 1f78391acec790b67a48316f045fd327a865c2b3
fix_note: "ServiceBookingCounts counts bookings that started from the 90 days' start up to now by the injected clock (the test clock applies), so bookings still to come no longer count; the contract and query Javadoc say so."
developer_note: null
verified_on: 2026-11-10
approved_by: null
found_in_release: null
---

# BUG-F07-005 "Most booked" counts confirmed bookings still to come, so a service booked only for the future outranks one booked in the last 90 days

## Steps

Account: the Owner of a salon the operator fixture provisions. Data: two team members; services "Blow-dry" and "Head
massage" that both perform. The API's test clock at 08:00 in Colombo on the fixture's day D. QA instance (API 8190).

1. Place and confirm one "Blow-dry" booking on D at 09:00 (`POST /api/v1/bookings`, then `/confirm`).
2. Place and confirm two "Head massage" bookings on D+5 at 10:00, one per member.
3. Move the test clock to D 10:30 (`PUT /local/clock`): the Blow-dry booking has taken place.
4. Set the Popular card to "Most booked" (`PUT /api/v1/catalogue/popular-setting` `{mode: "most_booked", version}`) and
   read `popular` in the answer.

## Expected

F07-S01 AC-13: "Most booked" ranks by real bookings, as `ServiceBookingCounts` is to answer it; decision 092: "Most booked"
popular services count **the last 90 days**; decision 095 (c): ranks the last 90 days. The owner ruled on 2026-11-10 (via
the lead, for PR #26) that the 90 days end at now, as the decision log says, over the S01 handoff's note 11 ("future
included"). So `popular` is ["Blow-dry"]: Head massage has no booking in the last 90 days.

## Actual

`popular` is ["Head massage", "Blow-dry"]: the two bookings still to come count, and outrank the one that took place.
Every confirmed booking up to a year ahead counts as popular.

Happens every time: 2 of 2 attempts (the test alone, and the recorded stage run).

Severity: minor. The ranking of a display list is wrong at its edge (future bookings counted); no booking, money or
other salon's data is affected, and the services shown are still ones the salon offers. The owner has asked for it to be
fixed before PR #26 merges, so it is not deferred.

## Evidence

Recorded run: qa/reports/20261110-050512-F07-S01-3f16319a6840, entry 007 guards-api
(007-F07-S01-guards-api.log): TC-F07-S01-032 to -035 passed; TC-F07-S01-039 failed:

```
Error: only Blow-dry was booked in the last 90 days; Head massage's bookings are still to come
- Expected  - 0
+ Received  + 1
  Array [
+   "Head massage",
    "Blow-dry",
  ]
```

Where: `apps/api/src/main/java/com/salonplatform/booking/repository/BookingRepository.java`, `countByService` (about
line 133) counts `startsAt >= :from` with no upper bound; `catalogue.ServiceBookingCounts.countsSince` and its Javadoc
("bookings starting at or after from") have the same gap, which the lead may want worded too.

## Re-verification

2026-11-10, on a5a7d8fe (fix 1f78391a, formatted in b3ba03ef: BookingRepository.countByService, ServiceBookingCountsImpl,
the ServiceBookingCounts Javadoc, and API tests; no migration or contract change).
QA instance rebuilt and restarted at a5a7d8fe (the fix merged; e0c1d4bc adds only QA test repairs): API PID 84142, /api/v1/meta commit a5a7d8fe; web PID 84363, apps/web/.next-qa built 06:00 (+05:30) from the clean tree. The old processes (web 67534, API 4997) were stopped by their exact PIDs.

1. The bug's own steps through the API (qa/reports/20261110-060558-F07-S01-dbcafc6434ad/BUG-F07-005-steps.log): at D 10:30, `popular` is ["Blow-dry"]; moving the
   clock to D+5 10:30, once the two Head massage bookings have started, it is ["Head massage", "Blow-dry"], so a booking
   counts from its start, up to now. The symptom is gone.
2. `qa/scripts/sdlc run --stage F07-S01` (the guards and "Most booked"): 8 of 8 entries, 37 of 37 cases passed,
   TC-F07-S01-039 and -032 to -035 included. Report: qa/reports/20261110-060558-F07-S01-dbcafc6434ad. And `qa/scripts/sdlc run --stage F05-S02`, the narrowest scope
   that holds F05's "Most booked" case TC-F05-S02-015: 3 of 3 entries, 21 of 21 cases passed. Report: qa/reports/20261110-060726-F05-S02-d335d8a44a60.
   Two cases had to be repaired first, in e0c1d4bc, with their expected rankings unchanged: TC-F07-S01-035 and
   TC-F05-S02-015 ranked bookings still to come, which the S01 handoff's note 11 counted and the owner's ruling no longer
   does; on a5a7d8fe both fell back to the chosen services (qa/reports/20261110-060558-F07-S01-dbcafc6434ad/most-booked-cases-before-repair.log). Each now reads
   the list once its counted bookings have taken place. A first S01 run (qa/reports/20261110-060246-F07-S01-c95384bb02a0)
   failed TC-F07-S01-026 on a race in that test, not on this fix: the hold-expiry job, run as the test clock moved, ended
   the test's run-out hold between the two table reads of its "nothing changed" snapshot
   (qa/reports/20261110-060558-F07-S01-dbcafc6434ad/TC-F07-S01-026-race-before-repair.log). e0c1d4bc settles the job first and reads both tables in one statement;
   the rerun above passed it.
3. TC-F07-S01-039 is the test that would catch it again: it failed on aeab2c30's product and passes on a5a7d8fe.

Result: verified.
