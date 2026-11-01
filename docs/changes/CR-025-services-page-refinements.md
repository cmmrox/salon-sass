---
id: CR-025
title: Services page refinements from the F05 review
status: approved
requested_by: QA review of F05
requested_on: 2026-11-01
decided_on: 2026-11-01
requirements: [CAT-01, CAT-02, CAT-05]
features: [F05]
stages: [F05-S01, F05-S03]
---

# CR-025 Services page refinements from the F05 review

## Request

QA's F05 review (`features/F05-service-catalogue/review.md`) classed two suggestions as "should":

1. **SUG-F05-01.** Merge the stacked information banners at the top of the Services and Products
   tabs into one.
2. **SUG-F05-02.** Fold empty categories on the Services tab into one line, instead of a heading
   and "No services yet." for each.

## Why

1. On a phone, the Services tab shows two banners and the Popular card before the first service,
   and the Products tab shows two banners before the first product. The tax sentence repeats on
   both tabs. A salon owner opens this page to change a price, and has to scroll past the same
   notices every time.
2. A new salon starts with ten categories (decision 092) and no services, so its Services tab is
   ten headings each saying "No services yet.", twice the screen's height. The prototype draws an
   empty category as one line, but with ten defaults the list still dominates the page.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None added. Presentation of CAT-01, CAT-02 and CAT-05 only |
| Features and stages | F05-S01 and F05-S03 (complete): the banners become one Alert per tab; empty categories collapse into one line such as "No services yet in: Nails, Waxing, Spa" with a way to add a service |
| Test cases and automation | The S01 and S03 banner and empty-state browser cases, and the journeys' banner checks, change their expectations |
| Data, API and released apps | None. Nothing is released |
| Size and delay | Small: a few hours of web work and a QA re-check of the changed cases. If approved before F05's PR merges it rides on the F05 branch; otherwise a small follow-up PR |

## Recommendation

Approve both. They make the page shorter for every salon, change no data, and cost little.

## Decision

Approved by the owner on 2026-11-01 ("Approve, build after PR"): both changes are approved and
built after F05's pull request merges, as a small follow-up on its own branch, re-checked by QA on
the changed cases. F05 closes and is submitted as QA passed it; F05-S01 and F05-S03 are reopened by
this change request only when that follow-up starts.
