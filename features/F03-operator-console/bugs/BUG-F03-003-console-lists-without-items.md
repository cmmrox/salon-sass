---
id: BUG-F03-003
title: Console item lists expose a list role with no list items, so screen readers cannot navigate them
severity: major
status: open
found_by: TC-F03-S01-034
breaks: AC-18
found_on: 2026-10-19
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F03-003 Console item lists expose a list role with no list items, so screen readers cannot navigate them

Also fails F03-S03 AC-12 (found by TC-F03-S03-017 on the lifecycle history).

## Steps

Account: a synthetic `OPERATOR_ADMIN`. Data: at least one plan and one salon (alpha, beta and gamma
are always listed); a salon with a suspension and a reactivation for the history.

1. Open `/console/salons`, `/console/plans` and `/console/salons/{id}` (Lifecycle history).
2. Run the automated accessibility scan (axe, WCAG 2.0-2.2 A/AA and best practice).

## Expected

F03-S01 AC-18 and F03-S03 AC-12: the screens are usable with assistive technology;
`docs/qa/test-types.md` accessibility: an automated scan with no serious or critical violation.
A list announces its items and their count.

## Actual

Every time. The shadcn/ui `ItemGroup` renders `<div role="list" data-slot="item-group">` whose
children are `<a>` rows without `role="listitem"`. axe reports `aria-required-children` (critical):
"Element has children which are not allowed: a" / "Required ARIA child role not present: listitem"
on `console-salons-list`, `console-plans-list` and the lifecycle history list. A screen reader
announces an empty or malformed list. The workspace screens scanned clean.

## Evidence

- Scan output: `{"route":"/console/salons","id":"aria-required-children","impact":"critical",
  "html":"<div role=\"list\" data-slot=\"item-group\" ... data-testid=\"console-salons-list\">"}`,
  the same for `/console/plans` and `/console/salons/{id}`.
- Recorded run: entries `F03-S01 console-screens` (TC-F03-S01-034) and `F03-S03 lifecycle-screens`
  (TC-F03-S03-017), run `qa/reports/20261019-093648-F03-09df7b0ccb75` (revision c1d6e73).

## Re-verification

