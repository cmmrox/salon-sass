---
id: BUG-F02-009
title: Prototype identity overlaps the mobile platform label at360px
severity: minor
status: verified
found_by: TC-F02-S04-023
breaks: AC-9
found_on: 2026-10-15
fixed_in: "docs/design/prototype/index.html platform selector CSS"
fix_note: "Place design-platform controls on a complete row in compact headers; constrain both button widths and retain the desktop Salon row."
developer_note: null
verified_on: 2026-10-15
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

Verified independently on2026-10-15 against `3f6ec78b25d3445fc3fe6f4c31247c748c61e506`. Tightened existing TC-F02-S04-023 passed at360/768/1440: platform buttons fit, do not overlap one another or identity. Independently inspected final360/1440screenshots; Customer mobile design (later) is clear on its own compact row, Salon web selected, deferred React Native note readable. Presentation group passed6/6. Evidence: `qa/reports/20261015-095550-F02-delta-76392eb5c4d7`. Original failed diagnostic evidence remains preserved.
