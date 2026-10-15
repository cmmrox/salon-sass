---
id: BUG-F02-009
title: Prototype identity overlaps the mobile platform label at360px
severity: minor
status: open
found_by: TC-F02-S04-023
breaks: AC-9
found_on: 2026-10-15
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F02-009 Prototype identity overlaps the mobile platform label at360px

## Steps

Source `8a2bb8498732cd9c78375f9ce9ad4f2fd6ce9cf3`; revised HTML reference rendered in isolated local preview, no external publication.

1. Open `docs/design/prototype/index.html` at360x1000.
2. Inspect the top identity logo and the two design-reference platform buttons.

## Expected

Approved CR-010 labels clearly identify Salon web and deferred Customer mobile design. Revised visual acceptance requires readable labels at the width matrix.

## Actual

Salon is correctly selected and the explanatory note is clear, but the circular identity logo overlaps the start of `Customer mobile design (later)`, obscuring its initial letter. At1440px the label is readable. The current automated case checks button-to-button geometry and bounds; it did not compare the platform group with the logo and therefore passed. Human rendered inspection caught this gap.

## Evidence

`qa/reports/20261015-094132-F02-delta-19fef4dae9b3/playwright/2026-10-15T04-13-06-622Z-94516/web-F02-S04-presentation-T-9ada9-d-Customer-mobile-reference-web/prototype-staff-default-360.png` and corresponding1440px image. The running staff application is unaffected; this finding is in the reference artifact changed by CR-010.

## Re-verification

Pending lead-owned prototype correction and same-case logo/group overlap assertion, followed by independent rendered review.
