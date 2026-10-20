---
id: CR-019
title: Operator console and support view usability follow-ups
status: proposed
requested_by: QA review of F03 (SUG-F03-01, SUG-F03-02, SUG-F03-03)
requested_on: 2026-10-20
decided_on: null
requirements: []
features: [F03]
stages: [F03-S01, F03-S04]
---

# CR-019 Operator console and support view usability follow-ups

## Request

QA's F03 review, three "should" suggestions:

1. SUG-F03-01: show a legacy salon's published brand name instead of "Not configured" in the salon
   directory and detail.
2. SUG-F03-02: disable the whole add-staff form (not only its submit button) in the support view
   and in a read-only salon.
3. SUG-F03-03: let operators pick time zone, country and currency from a searchable list instead of
   typing codes and IANA names.

## Why

1. Alpha, Beta and Gamma, provisioned before F03, show as three rows titled "Not configured",
   told apart only by hostname; suspension and closure start from that row
   (`qa/reports/f03-manual/console-salons-360.png`).
2. In the support view and a read-only salon the Email, Mobile, Initial password and Role fields
   stay editable although the action is refused, so a support operator can type a whole staff
   member, or appear to enter a password on the salon's behalf, before learning it never works.
3. Decision 050 accepted free-text codes. A mistyped time zone is only caught on submit, and a
   valid but wrong zone silently shifts every booking and invoice boundary
   (`qa/reports/f03-manual/console-create-salon-sheet-360.png`).

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None added or changed; presentation of PLT-01 and PLT-07 |
| Features and stages | F03 (complete, awaiting merge): reopen F03-S01 (1, 3) and F03-S04 (2) through a follow-up, or fold (1) into F04, which adds profile editing |
| Test cases and automation | New cases for each; TC-F03-S01-033/034 and TC-F03-S04-007 extended |
| Data, API and released apps | (1) display-only, or a one-time backfill of the platform name from branding; (2), (3) web only. No customer app |
| Size and delay | Small: about one stage of web work plus a short QA pass |

## Recommendation

Approve (2) and (3) as a small F03 follow-up after the F03 PR merges; approve (1) as display-only
(the brand name with a "from the salon's branding" hint), leaving a backfill to F04's profile
editing. None blocks the F03 merge.

## Decision

