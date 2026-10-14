---
id: BUG-F02-006
title: Recoverable confirmation failure hides feedback behind the open modal
severity: major
status: fixed
found_on: 2026-10-15
fixed_in: d94b166cb119aad39f64973085be47430bf1137e
fix_note: 'Failed confirmations dismiss before the translated alert and safe reload recovery become accessible; deferred trigger focus restoration handles the busy DOM update without replaying destructive requests. Seven new regressions failed before the fix; focused checks passed 16/16 (.local/logs/f02-review-components-green.log). All six S04 developer commands passed in the complete 10/10 recorded run qa/reports/20261015-010423-F01-commands-55f23c47f02f on this source; independent browser re-verification remains pending.'
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
found_by: TC-F02-S04-011
breaks: AC-8
---

# BUG-F02-006 Recoverable confirmation failure hides feedback behind the open modal

## Steps

Role: authenticated synthetic customer or Owner at Alpha, Chromium at 1280px. Actual running API/web source is `e73d99b0bbb38ec4db6b9cc837ea635286a1fed0`, product-equivalent to PR5 source at `4575173`; no application source was changed.

1. Open customer `/account`, choose Delete salon account, then confirm. Arrange the actual BFF response at the browser boundary as 503 `{code:service_unavailable}`.
2. Repeat with staff `/workspace/account` revoke-all and 503, then Owner `/workspace/staff` removal of an active synthetic Receptionist and 409 `{code:conflict}`.
3. Wait for the response and for the confirmation action to be enabled again; inspect the modal's accessible alert region and unchanged underlying identity.

## Expected

S04 AC-8 and the required error/recovery/announced-error design rules: the actual failure and a safe recovery action are accessible in the active interaction context. Either dismiss the confirmation and expose announced error/retry with focus restored, or present accessible error/recovery inside it. Never automatically retry a destructive unknown-outcome mutation.

## Actual

All three settled refusals leave the native modal open, with zero alert elements inside it. The error/retry is rendered only in the inert background. The confirmation shows its original question and buttons without explaining the refusal. Cancel reveals the background error. Customer/Owner principal remains unchanged and the target staff member remains active, so this is error presentation/recovery, not a successful destructive operation. Reproduced 3/3 variations in one table; no retries were used.

## Evidence

`qa/reports/20261015-PR5-review-reproduction/reproduction.log` records the two selected real-browser regressions and their exact failed assertions. Browser screenshots/error context remain under its `playwright/2026-10-14T19-28-15-060Z-58419/` folder. The selected QA file is `qa/web/F02/S04-functional.spec.ts`; no full feature pass was run for this diagnosis. Initial preflight refused a stopped web service before exercising product; the lead restored the unchanged completed web build in a persistent terminal, then the bounded reproduction ran.

## Re-verification

Pending developer fix, exact affected regression rows and full independent F02 verification on frozen final source.
