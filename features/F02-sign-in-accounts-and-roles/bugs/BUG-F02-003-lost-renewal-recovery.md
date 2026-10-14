---
id: BUG-F02-003
title: A lost renewal response leaves a signed-out account screen without sign-in recovery
severity: major
status: verified
found_by: TC-F02-S04-008
breaks: AC-4
found_on: 2026-10-14
fixed_in: f7f9937
fix_note: Unknown-outcome renewal clears the affected kind and requests fresh sign-in instead of leaving an unavailable account screen.
developer_note: 'Developer regression reproduced both staff/customer unavailable deadends before the fix (two failures; ordinary-outage case passed). Focused seven tests pass after the fix, including once-only renewal, successful/failed clear, fixed same-origin sign-in and kind isolation; ordinary principal outage remains service_unavailable. Type-only assertion correction is 0524f6a. Lead combined all six S04 gates passed (API 503 tests; lint/typecheck/test/build/contracts exit 0) on integrated source. Running product 0d489ff52b54c93a7ca85bfe98449f56234f985a: for staff and customer, forwarded the actual BFF renewal to the API (200 committed), aborted the browser response with connection reset, observed exactly one renewal and successful clear (200), zero affected kind cookies and navigation respectively to /workspace/sign-in and /sign-in. Other-kind cookies stayed byte-identical and its live principal returned 200 at its valid API Clock instant. Sanitized proof is ignored .local/bug003-browser-proof.json and credential-free recovery screenshots are .local/bug003-staff-recovery.png and .local/bug003-customer-recovery.png in the web worktree. Clock restored to real; only isolated walkthrough Manager sessions revoked and its synthetic customer deleted; retained keys, unrelated accounts, audit and scheduler preserved. Repeated setup used fresh synthetic phones after retained abuse limits, without clearing counters. Initial stale S04 Manager fixture was rejected and replaced with a fresh isolated Manager; no product assertion was weakened. API focused package/web build exit 0; actual standalone scan covered 1417 files and all four retained key values, with zero private artifacts. Independent QA re-verification remains pending.'
verified_on: 2026-10-14
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

Verified on 2026-10-14. S04-008 passed independently after the fix and again in the final whole F02 run: actual committed renewal response loss attempts renewal once, clears only the affected cookie family, returns to the appropriate sign-in, preserves the other principal and cannot resurrect revoked authority. Other replay/expiry/ordering assertions passed. Evidence: `qa/reports/20261014-115732-F02/summary.json` and the corresponding registered entry logs.
