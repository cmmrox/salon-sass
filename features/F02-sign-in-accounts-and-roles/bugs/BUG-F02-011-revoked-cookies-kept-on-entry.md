---
id: BUG-F02-011
title: Server-side entry redirects a revoked staff session to sign-in but leaves its credential cookies in the browser
severity: minor
status: open
found_by: TC-F02-S04-008
breaks: AC-4
found_on: 2026-10-17
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F02-011 Server-side entry redirects a revoked staff session to sign-in but leaves its credential cookies in the browser

## Steps

Account: a synthetic alpha MANAGER seeded through the local hook. Tested source `be7e550` (application identical to `756fdef`).

1. Sign in through the API and rotate once (`POST /api/v1/identity/sessions/refresh`).
2. Replay the spent refresh token: the API answers 401 `invalid_session` and revokes the family.
3. Put the rotated access, refresh, expiry and session values in the `salon_staff_*` cookies and open `http://alpha.localhost:3100/workspace/account`.

## Expected

AC-4: replay revocation cannot restore a signed-out session, and the browser's staff credentials for that revoked family are cleared. TC-F02-S04-008 expects no renewal attempt and no `salon_staff_refresh` cookie afterwards. This held before CR-013, when the client discovered the revocation and the BFF cleared the cookies.

## Actual

The page lands on `/workspace/sign-in` with no renewal attempt (correct), but the proxy's 307 carries no `Set-Cookie`. All four `salon_staff_*` cookies, including the revoked refresh credential, remain. Every time (recorded run and direct probe).

No authority is restored. But the stale credentials stay in the browser, and every later `/workspace` visit goes `/workspace` → `/workspace/account` → an API `/identity/me` call → sign-in. Once the access cookie's expiry time passes, the decision-045 named-workspace flash appears for a family the server has already been told is revoked. Clearing the cookies on that 307 would avoid both.

## Evidence

- Recorded run `qa/reports/20261017-063102-F02-delta-ff3abe97e455`, group `browser-security`, TC-F02-S04-008: `Expected: false / Received: true` at the refresh-cookie assertion (`qa/reports/20261017-063102-F02-delta-ff3abe97e455/002-F02-S04-browser-security.log`, screenshot under `qa/reports/20261017-063102-F02-delta-ff3abe97e455/playwright/`).
- Direct probe: replay → 401; `GET /workspace/account` with the cookies → 307 `/workspace/sign-in`, `set-cookie: []`; `GET /workspace` → 307 `/workspace/account`, `set-cookie: []`.

## Re-verification

<QA, after a fix.>
