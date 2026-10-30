---
id: BUG-F02-014
title: Under the standalone server bound to 127.0.0.1, /salon-site never answers 404 and the proxy rewrites it into itself without end
severity: minor
status: verified
found_after_merge: 2026-10-30
found_by: exploratory
breaks: "F02-S04 spec, UI and UX: /salon-site remains externally 404 (CR-010); docs/engineering/web.md, Tenant routing"
found_on: 2026-10-30
fixed_in: 4e0b299
fix_note: "The proxy passes its not-found target (/salon-site/nowhere) straight through, before any rewrite or API lookup and with no salon handed on, so a rewrite that Next.js proxies back to the server (HOSTNAME=127.0.0.1) ends in one pass with the application's 404 page."
developer_note: "Cause, from Next.js 16.4.1 (dist/server/lib/router-utils/resolve-routes.js:470): a proxy rewrite whose origin differs from the server's own (HOSTNAME=127.0.0.1, Host alpha.localhost) is proxied over HTTP to itself, so the target re-enters the proxy; the console hostname looped the same way. Unit test in route-request.test.ts feeds each kind of not-found rewrite back in (7 cases, all failed before). From outside, own standalone server on port 3132, HOSTNAME=127.0.0.1 and 0.0.0.0: /salon-site, /salon-site/x, /salon-site/nowhere, console /workspace and /salon-site, /workspace;x all 404 data-testid=not-found in under 0.13 s (before: 8 s timeout, 582 looping log lines). Gates at 0dbe7d2 (complete logs in /Users/cmmrox/Personal/Projects/salon-sass-bugfix/build/regression-bugfix-evidence/gates, summary.txt): ./mvnw -q verify exit 0 (864 tests; three earlier runs failed only OperatorAccessIntegrationTest.simultaneousAttemptsOnOneEmailCountEveryAttempt under load averages 10-40, which passes alone and with the new tests run first, and base fbab6d0 passed at the same time), pnpm -r lint/typecheck/test exit 0 (web 700/700 on retry after load timeouts), web build, format:check, knip, contracts:check, lint-workflows, images-build, images-scan, test-edge-health-tolerance, test-script-prerequisites, sdlc selftest exit 0."
verified_on: 2026-10-30
approved_by: null
found_in_release: null
---

# BUG-F02-014 Under the standalone server bound to 127.0.0.1, /salon-site never answers 404 and the proxy rewrites it into itself without end

Reported unverified in the F23-S06 handoff; reproduced independently by QA during the F23 pass. It
happens the same on `origin/main` (Next.js 16.4.0) as on the F23 head (16.4.1), so F23 did not
cause it; it belongs to the retired-route rule of F02-S04 (CR-010).

## Steps

Account: none. Data: the local API running (fresh database `salon_f23_qa`); the web app built
(`pnpm --filter @salon/web build`).

1. Start Next.js's standalone server as the image does, but bound to the loopback address:
   `HOSTNAME=127.0.0.1 PORT=3102 NODE_ENV=production SALON_WEB_LOCAL_HTTP=true
   SALON_API_BASE_URL=http://localhost:8090 SALON_PLATFORM_DOMAIN=localhost node apps/web/server.js`
   from `apps/web/.next/standalone` (local proxy keys loaded the way `scripts/serve.mjs` loads them).
2. `curl -H 'Host: alpha.localhost:3102' http://127.0.0.1:3102/salon-site` (and `/salon-site/x`).
3. For comparison, the same with `HOSTNAME=0.0.0.0` (the image's setting) and through
   `infra/scripts/local-service.sh start web` (`scripts/serve.mjs`).

## Expected

404 with the application's not-found page for `/salon-site` and every child path, whatever address
the server listens on (F02-S04: "`/salon-site` remains externally 404"; web.md: "is not publicly
reachable and returns 404").

## Actual

With `HOSTNAME=127.0.0.1` no answer within 15 s (curl timeout) on both paths; the server's request
log shows the proxy's rewrite target `/salon-site/nowhere` requested again and again (629 log lines
for two requests on the F23 head, 309 for one on `origin/main`), because the rewrite target lies
under the prefix the proxy rewrites (`route-request.ts`: `NOWHERE = '/salon-site/nowhere'` inside
`INTERNAL_PREFIX = '/salon-site'`) and the standalone server re-enters the proxy for it. Every
request to the retired path therefore turns into an unbounded series of internal requests. With
`HOSTNAME=0.0.0.0`, and through `serve.mjs`, both paths answer 404 at once, so the image as deployed
is not affected. Every time (2 of 2 requests on each build).

## Evidence

Server logs kept in the local, uncommitted `qa/reports/20261030-F23-preflight/`:
`server-127.log` (F23 head) and `main-server.log` (`origin/main`): repeated `GET /salon-site/nowhere 200` lines; no secrets.

## Re-verification

QA, 2026-10-30, on d9c8379 (the web build of `local-service.sh start web`): the standalone server with `HOSTNAME=127.0.0.1`
answers `/salon-site`, `/salon-site/x` and `/salon-site/nowhere` 404 with the application's not-found page in 2 to 23 ms,
with one internal `/salon-site/nowhere` pass per request (3 log lines for 3 requests, against 629 before the fix);
`/no-such-page` 404 and `/workspace/sign-in` 200 as before. With `HOSTNAME=0.0.0.0` the same answers. Verified.
