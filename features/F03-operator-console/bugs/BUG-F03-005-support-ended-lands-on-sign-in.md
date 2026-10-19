---
id: BUG-F03-005
title: When support access ends, the next page sometimes shows the staff sign-in instead of the "session ended" notice
severity: minor
status: open
found_by: TC-F03-S04-010
breaks: AC-10
found_on: 2026-10-19
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F03-005 When support access ends, the next page sometimes shows the staff sign-in instead of the "session ended" notice

## Steps

Account: a synthetic `OPERATOR_SUPPORT` (fixture hook) and the provisioned salon's Owner. Data: one
provisioned salon. Browser: Chromium, a fresh context.

1. Start a support session and open its entry link; the support view loads with its banner.
2. Within a moment of the page loading (while the router's prefetches are still in flight), the
   Owner revokes the session (`DELETE /api/v1/salon/support-sessions/{id}` -> 204).
3. Navigate to another workspace page (`/workspace/staff`).

## Expected

F03-S04 AC-5: the workspace leaves the support view (it does). AC-10 and the S04 UI table: the
support entry page shows the ended/expired state, and the S04 handoff: "an ended view redirects to
`/workspace/support#ended`". The support operator should be told the session ended.

## Actual

Intermittent: 1 of 6 by-hand attempts, and once in the recorded run; the other attempts show the
"session ended" notice. In the failing attempt the prefetches started by the loaded page are refused
first and appear to clear the support cookies (QA's reading of the request timeline, not
confirmed in code); the navigation then reaches `/workspace/support` with no support cookie and is redirected to
`/workspace/sign-in`. The support operator sees the salon's staff sign-in form, with no word that
their session was ended. No access is gained: the credential is refused (AC-5 holds).

## Evidence

- Request timelines of the six attempts (attempt #2 ends on `/workspace/sign-in`, the others on
  `/workspace/support#ended`): QA diagnostic, summarised above; the failing sequence was
  `GET /workspace/staff ; GET /workspace/support ; GET /workspace/support ; GET /workspace/sign-in ; NAV /workspace/sign-in`.
- Recorded run `qa/reports/20261019-091904-F03-c39c4f0a50d0`, entry `F03-S04 support-screens`,
  TC-F03-S04-010: `support-entry-ended` not found; screenshot shows the staff sign-in page.

## Re-verification

