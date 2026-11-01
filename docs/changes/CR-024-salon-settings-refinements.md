---
id: CR-024
title: Salon settings refinements from the F04 review
status: proposed
requested_by: QA review of F04
requested_on: 2026-11-01
decided_on: null
requirements: [BKG-09]
features: [F04]
stages: [F04-S04, F04-S01]
---

# CR-024 Salon settings refinements from the F04 review

## Request

QA's F04 review (`features/F04-salon-setup/review.md`) classed four suggestions as "should":

1. **SUG-F04-01.** Show each booking-rules version's values (before and after) in History, not only
   the names of the rules it changed.
2. **SUG-F04-02.** Keep the selected Settings tab in view on phones, and show that the tab rail
   scrolls.
3. **SUG-F04-03.** When there is no deposit, stop offering "The deposit" as what a late cancellation
   or a no-show keeps.
4. **SUG-F04-04.** Make "Free cancellation until N hours" read sensibly when a late cancellation
   keeps nothing.

## Why

1. A customer who disputes a kept deposit ("it said 10% when I booked") can only be answered from
   the version the booking was made under. Bookings will keep that version (F07), but no screen
   shows a past version's terms. Fresha shows the policy on each appointment.
2. At 360 px "Booking rules" is cut by the screen edge and "Branding" is off-screen with no hint
   that the rail scrolls. The prototype does the same; it gets worse when F18 adds two more tabs.
3. With no deposit, the owner can choose "The deposit" for no-shows and believe no-shows are
   covered when nothing is kept. Fewer no-shows is why salons pay for this product.
4. "Free cancellation until 48 hours before. After that, nothing is kept." suggests a charge that
   does not exist, at the moment the customer pays.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None added. BKG-09's text already covers 1, 3 and 4; 2 is presentation |
| Features and stages | F04-S04 (complete): History rows expand to a version's summary; the retention choices and summary sentence change. F04-S01 (complete): the Settings tab rail scrolls the selected tab into view and marks overflow. F04 would reopen after its merge, or the work rides with F07 |
| Test cases and automation | TC-F04-S04 history and summary cases gain cases; the S01 width cases change their tab-rail expectation |
| Data, API and released apps | 1 needs a settings read of one version by id (`BookingPolicies.version` exists inside the API). 3 may refuse a saved `deposit` retention when the deposit is none, so existing saved rules need a rule for that case. Nothing is released yet |
| Size and delay | Small: about a day of building and a QA re-check. Delays nothing if scheduled with F07 |

## Recommendation

Approve 1, 3 and 4 and build them with F07, which first stores a booking's rules version and needs
the version read anyway. Approve 2 now or with F18, which adds the fifth and sixth tabs.

## Decision

