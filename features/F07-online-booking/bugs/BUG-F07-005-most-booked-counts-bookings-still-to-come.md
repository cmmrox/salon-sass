---
id: BUG-F07-005
title: '"Most booked" counts confirmed bookings still to come, so a service booked only for the future outranks one booked in the last 90 days'
severity: minor
status: open
found_by: "TC-F07-S01-039; PR #26 review (Codex, BookingRepository.countByService)"
breaks: AC-13
found_on: 2026-11-10
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
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

<QA, after a fix: what was rerun, the run folder, and the result.>
