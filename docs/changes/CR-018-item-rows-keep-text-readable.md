---
id: CR-018
title: Item rows keep long text readable beside their actions
status: proposed
requested_by: QA review of F02 (SUG-F02-11)
requested_on: 2026-10-18
decided_on: null
requirements: []
features: [F02]
stages: [F02-S04]
---

# CR-018 Item rows keep long text readable beside their actions

## Request

QA's CR-017 review of F02 (SUG-F02-11, "should"): "Stop Item rows squeezing long text into a
narrow column beside their actions."

## Why

Since CR-017 the device, staff and audit rows are shadcn/ui Items. When a row's actions (the Role
Select, "Remove access", "Sign out this device") sit beside its text, a long email or a device's
"Created" date is squeezed into a column about 80px wide and breaks across four to six lines: the
staff list at 1280 (its card is about 450px wide inside a 1280px page, as before CR-017) and the
device list at 390. An owner checking who has access reads email addresses; broken mid-word they
are slow to read and easy to misread. Evidence: `qa/reports/20261018-CR017-manual/side/beta-staff-1280.png`,
`side/beta-account-390.png`, `unmasked/select-gamma-1280.png`.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None. NFR-21 and the prototype's responsive rows grammar already ask for readable rows. |
| Features and stages | F02-S04 (`complete`, F02 QA `pass` under CR-017, not merged): reopens if approved now; otherwise it is planned into a later stage. |
| Test cases and automation | One presentation check that a row's text keeps a minimum readable width at the six widths; QA adds it at the feature pass. |
| Data, API and released apps | None. |
| Size and delay | Small, web only: `ItemActions` wraps below `ItemContent` when the row is narrow, and the staff card takes the workspace content width on a desk. One developer stage pass and a targeted QA re-verification of F02. |

## Recommendation

Approve and include it in the open CR-016/CR-017 repair before its PR, while F02-S04 is fresh; the
cost is small and the problem is visible on the screens just rebuilt.

## Decision

<Left empty while proposed.>
