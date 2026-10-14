---
id: BUG-F02-004
title: Account form test ids use camelCase instead of the required kebab-case contract
severity: major
status: open
found_by: TC-F02-S04-021
breaks: AC-8
found_on: 2026-10-14
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F02-004 Account form test ids violate the required naming contract

## Steps

Fixed product runtime0f26259, QA frozen7bb8991 with the independently proven sign-in readiness correction under iteration. Local alpha, random synthetic Owner authenticated through the real password/TOTP APIs.

1. Open `/workspace/account` and wait for logout to be visible and the loading indicator to disappear.
2. Collect the actual visible `data-testid` attributes.
3. Apply the documented lowercase kebab-case naming rule, without altering its validator.
4. Repeat on customer profile and staff creation fields.

## Expected

S04 AC-8 requires stable test ids. Its Read first design standards section86 explicitly makes them a contract with QA and requires `<area>-<thing>` or `<area>-<thing>-<action>` in kebab-case. Engineering web116 requires each interactive id to follow that naming rule.

## Actual

The settled Owner form provides `account-oldPassword` and `account-newPassword`; the profile controls similarly derive ids from the camelCase property marketingConsent; the existing language select correctly uses `account-language`. The first visible invalid id fails the unchanged naming check. This is distinct from the earlier loading-scrape test fault: the form is now ready, nonempty and visible.

## Evidence

- `qa/reports/20261014-fixed-source-triage/repaired-cases.log`: S04-021 reaches the settled inventory and fails on `account-oldPassword` against the documented lowercase kebab-case regex.
- `apps/web/src/components/account/Controls.tsx` derives test ids directly from the supplied input name; AccountScreen supplies camelCase field names. This read-only source corroboration identifies all variants for the developer; expected behavior comes from the design standards, not the source.
- In the same repaired-case run, Journey002, shared cancellation-budget020 and signed-address022 all passed without weakening their behavioral assertions.

## Re-verification

Pending. Announce changed test handles in the handoff, preserve JSON field names and update only the QA centralized map/selectors to the final stable handles. Recheck the actual visible control inventory and all affected account flows, then the full F02 feature through the lifecycle. No relaxed naming assertion or missing control is accepted.
