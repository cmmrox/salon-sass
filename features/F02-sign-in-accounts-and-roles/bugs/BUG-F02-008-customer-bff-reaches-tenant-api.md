---
id: BUG-F02-008
title: Removed customer BFF requests still reach tenant API through web proxy
severity: major
status: open
found_by: TC-F02-S04-003
breaks: AC-5
found_on: 2026-10-15
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F02-008 Removed customer BFF requests still reach tenant API through web proxy

## Steps

Committed source: `8a2bb8498732cd9c78375f9ce9ad4f2fd6ce9cf3`. Local synthetic Alpha and an API-issued customer credential cookie; no real message or data.

1. Start local API/web and obtain synthetic customer API authority (positive foundation control).
2. Stop the API using its existing local-service control. Listen on its port with the QA recording stand-in returning503 to any request.
3. GET `http://alpha.localhost:3100/identity-web/customer/principal` with exact same-origin Origin.
4. Inspect status and recording stand-in. Restore the actual API in finally.

## Expected

Approved CR-010 and revised F02-S04 AC-5: every former customer BFF GET/POST returns no-store404 without upstream calls, cookie changes or messages. The boundary must be independent of API health. This is removal of customer web delivery; retained Java APIs remain callable for the future native app.

## Actual

The first GET returns503 rather than404. The request reaches hostname resolution through the shared web proxy before the BFF handler rejects the customer kind. Source inspection finds `route-request.ts` awaits `resolve(Host)` before dispatching the customer route; the route handler's early rejection does not protect the complete HTTP request path.

The case fails at its first status assertion, so later loop rows and zero-call/cookie assertions are unexecuted, not passing. Direct API foundation credentials remain separately covered by retained API evidence; no data exposure is claimed here.

## Evidence

`qa/reports/20261015-094132-F02-delta-19fef4dae9b3/002-F02-S04-browser-security.log`, case TC-F02-S04-003: Expected404, Received503. Error context under that report's `playwright/2026-10-15T04-12-26-178Z-91337/web-F02-S04-browser-securi-fe562--API-or-cookie-side-effects-web/`.

The same group passed staff renewal/replay, unsupported-browser controls, CSRF, artifact/cookie secrecy, staff redirects/isolation and staff-only legacy entry. Those passing checks do not excuse the failing boundary.

## Re-verification

Pending developer fix; rerun unchanged TC-F02-S04-003 through its registered browser-security group with an API stand-in. Required result: every former customer operation GET/POST404, no-store, zero API calls and unchanged cookies; restored direct Java API authority still works.
