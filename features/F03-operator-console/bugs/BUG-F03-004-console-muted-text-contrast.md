---
id: BUG-F03-004
title: Console help and empty-state text has 4.25:1 contrast on the canvas, below WCAG AA
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

# BUG-F03-004 Console help and empty-state text has 4.25:1 contrast on the canvas, below WCAG AA

Also fails F03-S02 AC-15 (TC-F03-S02-026), F03-S03 AC-12 and F03-S04 AC-10 (TC-F03-S04-017).

## Steps

Account: a synthetic `OPERATOR_ADMIN` or `OPERATOR_SUPPORT`. Data: one plan and one salon.

1. Open `/console/salons` and `/console/plans` (search help under the search field), a salon
   detail with no lifecycle history (empty description), `/console/support` with no sessions
   (empty description), the Create salon, Create plan and Start support session sheets (field
   descriptions), and a salon's subscription card.
2. Run the automated accessibility scan.

## Expected

WCAG 2.2 AA 1.4.3: normal-size text at least 4.5:1 against its background (NFR-12; the same
standard was applied to BUG-F01-003). `docs/qa/test-types.md`: no serious violation.

## Actual

Every time. `text-muted-foreground` (#6b7280) on the console canvas (#f3f0eb) measures 4.25:1 at
14-15px normal weight. axe `color-contrast` (serious) on `#console-salons-search-help`,
the plans search help, the lifecycle and support empty descriptions, `#console-support-duration-description`
and every field description in the console sheets, and on secondary text in the subscription card.
On the white workspace cards the same token passes (workspace billing, support access and audit
pages scanned clean).

## Evidence

- Scan output: "Element has insufficient color contrast of 4.25 (foreground color: #6b7280,
  background color: #f3f0eb, font size: 10.5pt (14px), font weight: normal). Expected contrast
  ratio of 4.5:1".
- Recorded run: entries `F03-S01 console-screens` (TC-F03-S01-034), `F03-S02 billing-screens`
  (TC-F03-S02-026) and `F03-S04 support-screens` (TC-F03-S04-017); run
  `qa/reports/20261019-093648-F03-09df7b0ccb75` (revision c1d6e73).

## Re-verification

