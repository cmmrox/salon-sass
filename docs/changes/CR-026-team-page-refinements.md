---
id: CR-026
title: Team page refinements from the F06 review
status: approved
requested_by: QA review of F06
requested_on: 2026-11-09
decided_on: 2026-11-09
requirements: [STF-01, STF-02, STF-03, STF-05]
features: [F06]
stages: [F06-S01, F06-S02, F06-S03]
---

# CR-026 Team page refinements from the F06 review

## Request

QA's F06 review (`features/F06-staff-schedules-and-leave/review.md`) classed three suggestions as "should":

1. **SUG-F06-01.** One-day leave in fewer steps: once the first day is chosen, set the last day to it and open the
   last-day calendar at it, offering no earlier day.
2. **SUG-F06-02.** Leave the year out of dates within the current year on the card's leave badge and on the leave and
   block rows.
3. **SUG-F06-03.** Do not show "Name must be 2 to 40 characters." on a blank Add team member sheet before anything is
   typed or saved.

## Why

1. Most leave is one day. Today the last day starts empty and its calendar offers days before the first, so a one-day
   leave takes two full date choices, and a wrong order is only caught on save.
2. Dates print through the shared `formatDay` with the year (decision 106), so on the card the leave badge wraps to two
   lines. Within the current year the year adds nothing.
3. An empty form greets the owner with an error they have not made. The prototype draws the same, but it reads as a
   mistake.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None added. Presentation of STF-01 and STF-05 only |
| Features and stages | F06-S03 (the leave form's last day; the badge and rows' dates), F06-S01 (the Add sheet's first error). SUG-F06-02 changes the shared `formatDay` or adds a short form beside it, which reaches other screens' dates: the lead decides which at planning |
| Test cases and automation | The S03 leave form and badge cases and the S01 Add sheet case change their expectations |
| Data, API and released apps | None. Nothing is released |
| Size and delay | Small: a few hours of web work and a QA re-check of the changed cases, as a follow-up PR after F06 merges (decision 098) |

## Recommendation

Approve all three, built after F06's pull request merges as one small follow-up. They change no data and cost little.

## Decision

Approved by the owner on 2026-11-09 ("approve all three, go ahead"), after F06's merge (PR #23,
b6e18f9). A merged feature keeps its status and merge record (decision 098), so no stage is reopened:
the new criteria are added to the specs of F06-S01, F06-S02 and F06-S03 and built in one follow-up PR,
which also carries F06's merge record, and QA re-verifies the changed cases.

For SUG-F06-02 the lead chose a short form beside `formatDay`, used on the Team page only (decision
107): the card's leave badge and the rows of the Leave, Blocked time and Date changes tabs. The
Date changes rows are included so the schedule sheet's tabs print dates alike. F03's and F04's
screens keep `formatDay` with the year, so their cases do not change.
