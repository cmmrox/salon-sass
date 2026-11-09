---
id: CR-027
title: Bookings page refinements from the F07 review
status: proposed
requested_by: QA review of F07
requested_on: 2026-11-09
decided_on: null
requirements: [BKG-06, BKG-11]
features: [F07]
stages: [F07-S01, F07-S03]
---

# CR-027 Bookings page refinements from the F07 review

## Request

QA's F07 review (`features/F07-online-booking/review.md`) classed three suggestions as "should":

1. **SUG-F07-01.** On the wide Bookings list, show each booking's time on one line and leave the day to the heading.
2. **SUG-F07-02.** Name the staff member in a booking's History, not their sign-in email.
3. **SUG-F07-03.** When a day has no free time for the service, offer the next day that has one.

## Why

1. The list shows one day, already named in its heading, yet every row repeats the full date and breaks the time over
   two more lines, so each row is four lines tall and the desk sees fewer bookings at a glance.
2. "pradeep.ekanayake@salonstaff.lk" is how the system knows a person, not how the salon does. Decision 111 (b) accepted
   the label because the history's actor carries nothing else.
3. On a full day the desk must open the calendar and try day after day while the customer waits. Fresha offers the next
   available date.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None added. Presentation of BKG-06 and BKG-11 |
| Features and stages | F07-S03 (the list's When cell; the history's names; New appointment's empty answer). SUG-F07-02 also needs the history's actor to carry the team member's name, an F07-S01 API change through identity's or staff's provided interfaces. SUG-F07-03 needs a "next day with free time" read from F07-S02's calculation |
| Test cases and automation | The S03 list, history and free-times cases change their expectations; new API cases for the actor's name and the next free day |
| Data, API and released apps | Additive API fields only. Nothing is released |
| Size and delay | Small to medium: about a day of API and web work and a QA re-check of the changed cases, as one follow-up PR after F07 merges (decision 098) |

## Recommendation

Approve all three, built after F07's pull request merges as one follow-up. SUG-F07-01 is a few lines of web work;
SUG-F07-02 and SUG-F07-03 are the ones that save the desk real time.

## Decision

Awaiting the owner.
