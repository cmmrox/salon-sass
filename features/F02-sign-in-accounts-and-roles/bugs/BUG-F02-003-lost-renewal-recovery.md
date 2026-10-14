---
id: BUG-F02-003
title: A lost renewal response leaves a signed-out account screen without sign-in recovery
severity: major
status: open
found_by: TC-F02-S04-008
breaks: AC-4
found_on: 2026-10-14
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F02-003 Lost renewal leaves an unavailable account instead of fresh sign-in

## Steps

Local alpha, source 8eeca6f/runtime closure 34b59f2. Authenticate a random synthetic Manager through the real API and arrange the actual issued HttpOnly web cookies. Use a browser context with Web Locks enabled.

1. Open `/workspace/account` and wait for the account controls.
2. Advance the application's injected Clock to one second after the issued access expiry.
3. Reload the account. For the actual `/identity-web/staff/refresh` network request, forward it to the real BFF/API and let the renewal commit, then abort the browser response with connection reset. Do not fabricate tokens or alter product code.
4. Observe the subsequent real `/identity-web/staff/clear` request and the settled error screen.

## Expected

S04 AC-4 and the browser identity policy require unknown-outcome/spent renewal not to be retried, the affected cookie family to be cleared, and fresh sign-in recovery. Authentication failures return to the appropriate same-origin sign-in. The other kind remains independent.

## Actual

The refresh is attempted exactly once and staff cookies are cleared, which are correct. The browser remains at `/workspace/account`, however, with no sign-in recovery link. It displays only a service-unavailable paragraph and "Try again". The user has already been signed out; this page gives no immediate fresh sign-in path. The full diagnostic case timed out waiting for the promised sign-in navigation. Independent reproduction, waiting for the successful clear response instead of a navigation timeout, confirmed the same settled state in 1.9 seconds.

## Evidence

- `qa/reports/20261014-013535-F02/F02-S04-browser-security.log`: S04-008 failed at lost-response recovery navigation.
- `qa/reports/20261014-013535-F02/lost-renewal-independent.json`: renewalAttempts1, path `/workspace/account`, staffCookiesRemaining0, signInRecoveryLink0, unavailable/try-again wording.
- `qa/reports/20261014-013535-F02/lost-renewal-independent.png`: credential-free settled error screen.

Independent triage used the real committed renewal and browser network interruption. Clock/fixtures were restored by the normal guarded QA fixture. Preserve the once-only renewal and cleared-family behavior; repair fresh-sign-in recovery rather than retrying the spent credential.

## Re-verification

Pending developer fix. Check lost response, replay revocation, future-expiry401 and delayed renewal/sign-out ordering for both kinds, then the full F02 feature.
