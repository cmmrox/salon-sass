---
id: BUG-F02-007
title: Workspace navigation retains removed permissions after live self-demotion
severity: major
status: fixed
found_on: 2026-10-15
fixed_in: d94b166cb119aad39f64973085be47430bf1137e
fix_note: 'Observed live staff permission-set changes refresh the server navigation once across initial load, mutation reload and BroadcastChannel; unchanged or reordered permissions and other-kind events do not refresh. Focused checks passed 16/16 after meaningful failing regressions (.local/logs/f02-review-components-red.log and f02-review-components-green.log). All six S04 developer commands passed in the complete 10/10 recorded run qa/reports/20261015-010423-F01-commands-55f23c47f02f on this source; independent browser re-verification remains pending.'
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
found_by: TC-F02-S04-004
breaks: AC-3
---

# BUG-F02-007 Workspace navigation retains removed permissions after live self-demotion

## Steps

Role: authenticated Alpha Owner in Chromium at 1280px. A second independently seeded active Owner remains, so the last-Owner guard does not prevent legitimate demotion. Running API/web source is `e73d99b0bbb38ec4db6b9cc837ea635286a1fed0`, product-equivalent to PR5 at `4575173`.

1. Open `/workspace/staff`; prove Staff access and Activity log navigation links exist.
2. In the current Owner's own staff row, choose RECEPTIONIST using the real role selector. Do not reload or navigate.
3. Read the current BFF principal and direct staff/audit responses, and inspect the current workspace navigation.

## Expected

S04 AC-3 and approved CR-007 permission-filtered navigation: after the live principal loses `staff.list` and `audit.list`, those controls disappear from the current workspace without a manual reload. Account controls remain available; actual API/BFF permission checks remain authoritative.

## Actual

The real role mutation succeeds; current principal is RECEPTIONIST with neither permission, the visible content says permission denied, and direct staff/audit BFF reads correctly return 403. Nevertheless one Staff access link and one Activity log link remain for at least the unchanged 10-second assertion window each. The server-rendered shell retains initial permissions while AccountScreen changes its own live principal. Reproduced once with exact role/principal/denial positive controls; this finding does not establish data exposure or a permission bypass.

## Evidence

`qa/reports/20261015-PR5-review-reproduction/reproduction.log` records the two selected real-browser regressions and their exact failed assertions. Browser screenshots/error context remain under its `playwright/2026-10-14T19-28-15-060Z-58419/` folder. The selected QA file is `qa/web/F02/S04-functional.spec.ts`; no full feature pass was run for this diagnosis. Initial preflight refused a stopped web service before exercising product; the lead restored the unchanged completed web build in a persistent terminal, then the bounded reproduction ran.

## Re-verification

Pending developer fix, exact affected regression rows and full independent F02 verification on frozen final source.
