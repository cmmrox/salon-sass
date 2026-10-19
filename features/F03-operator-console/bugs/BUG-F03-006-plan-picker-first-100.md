---
id: BUG-F03-006
title: The console's plan pickers offer only the 100 newest plans, so older plans cannot be assigned or moved to
severity: minor
status: fixed
found_by: TC-F03-S01-042
breaks: AC-7
found_on: 2026-10-19
fixed_in: aaa059a
fix_note: 'A shared PlanPicker keeps the newest 100 and adds a literal name/key search and previous/next pages, keeping the chosen plan; Console/BillingScreens tests red then green; on the product the oldest of 576 plans was found, a salon created on it and moved to another old plan, then reset (apps/api/target/f03-bugfix-evidence/plan-picker-after.json, plan-assign-after.json); gates (apps/api/target/f03-bugfix-evidence/gates/summary.txt): API verify exit 0 at 4a60e33 (688 tests), lint/typecheck/test/web build/contracts:check exit 0 at f122ad4'
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F03-006 The console's plan pickers offer only the 100 newest plans, so older plans cannot be assigned or moved to

Also F03-S02 AC-11 (moving a salon to another plan).

## Steps

Account: a synthetic `OPERATOR_ADMIN`. Data: more than 100 plans (the local database holds 462 after
QA runs; plans are immutable and never removed).

1. Create a plan dated earlier than the 100 newest (locally, any plan created at the real time is
   older than the 202 plans clock-moving tests dated in the future).
2. Open Create salon on `/console/salons`, or Move plan on a salon's subscription card, and open the
   Plan picker.

## Expected

F03-S01 AC-7: a created plan is "available for salon assignment"; F03-S02 AC-11: an Admin moves a
salon to another plan. Every plan the operator may assign can be found in the console.

## Actual

Every time once more than 100 plans exist: both pickers load `plans?page=0&size=100`
(`components/console/SalonScreens.tsx` and `BillingScreens.tsx`) and offer no search or further page,
so the plan is not in the list (`console-plan-id-option-<id>` not found). The API accepts the
assignment; only the console cannot make it. In production, plans older than the newest 100 would
become unassignable from the console.

## Evidence

- Recorded run `qa/reports/20261019-092728-F03-61c13956bcf6`, entry `F03-S01 console-screens`,
  TC-F03-S01-032: the plan created a moment earlier through the console is absent from the
  Create salon picker.
- `SELECT count(*) FROM plan` = 462, of which 202 have `created_at` in the future (test clock).

QA note: the picker cases now start their clock after the newest plan so that they test keyboard
use and assignment rather than this limit; TC-F03-S01-042 tests the limit and is deferred until
this bug is fixed.

## Re-verification

