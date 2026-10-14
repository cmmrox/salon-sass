---
id: BUG-F02-004
title: Account form test ids use camelCase instead of the required kebab-case contract
severity: major
status: verified
found_by: TC-F02-S04-021
breaks: AC-8
found_on: 2026-10-14
fixed_in: 5af298ad7822143bdb89e2468fe0c16f4ad0f1c7
fix_note: Shared Field and Check derive kebab-case test handles while preserving native input, label, error association and API field names.
developer_note: 'Changed actual handles account-oldPassword -> account-old-password, account-newPassword -> account-new-password, their -error derivatives, and account-marketingConsent -> account-marketing-consent. Shared preferredLanguage Field now yields account-preferred-language (and -error); the existing actual profile select remains account-language. MFA account-code, identifier/password, staff-email/mobile/password, notifications-* and professional-grant remain unchanged. The declared handle and real password/profile/Owner-creation/MFA component regression failed on six affected handles before the fix; final 16 tests, web lint and typecheck passed. Lead combined all six S04 gates passed on feature 672a5d7 (API 503 tests, no failures/errors/skips; lint/typecheck/test/build/contracts exit 0). Developer live inventory on exact product/API meta 5af298ad7822143bdb89e2468fe0c16f4ad0f1c7 checked actual staff old/new password and both validation-error handles, original name/id/aria error references, password JSON keys oldPassword/newPassword, optional MFA code handle/name, and actual customer marketing consent/language update JSON preferredLanguage/marketingConsent. Every visible collected test id met the unchanged lowercase kebab-case rule. No password change or MFA verification was performed; isolated customer profile changed only for binding proof then customer was deleted. Proof and safe screenshots are ignored .local/bug004-browser-proof.json and .local/bug004-staff-inventory.png/.local/bug004-customer-inventory.png in the web worktree; no credential values are in the proof. Initial private customer submission was blocked by the required empty name; corrected synthetic name and reran successfully without changing product requirements. Own Manager walkthrough sessions revoked, Clock restored real, keys/unrelated accounts/audit/provider controls/scheduler preserved. Fresh web build exited 0 and actual standalone secrecy scan passed for 1417 files/all four retained key values with zero private artifacts. Independent QA re-verification remains pending.'
verified_on: 2026-10-14
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

Verified on 2026-10-14. S04-021 passed on a settled nonempty inventory with unchanged kebab-case naming assertions. The central map names old-password/new-password/marketing-consent and error derivatives; existing account-language/MFA handles remain unchanged. Functional and all three feature journeys passed in the final whole F02 run. Evidence: `qa/reports/20261014-115732-F02/summary.json` and the corresponding registered entry logs.
