---
id: BUG-F03-002
title: Console pages take 5.4 to 6.4 seconds to report unavailable when the database or API is down or silent, beyond the five-second deadline
severity: major
status: fixed
found_by: TC-F03-S02-027
breaks: AC-16
found_on: 2026-10-19
fixed_in: 7b44b83
fix_note: 'Operator entry now returns unavailable (5xx, unanswered meta, or no answer within the shared 3 s budget) and the console page renders console-error with the response instead of a second browser wait; entry.test.ts/ConsolePending.test.tsx red then green; on the product 5.33/5.34/6.37 s became 3.02-3.05 s for database stopped/paused and API frozen (apps/api/target/f03-bugfix-evidence/outage-before.json, outage-after.json); gates (apps/api/target/f03-bugfix-evidence/gates/summary.txt): API verify exit 0 at 4a60e33 (688 tests), lint/typecheck/test/web build/contracts:check exit 0 at f122ad4'
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F03-002 Console pages take 5.4 to 6.4 seconds to report unavailable when the database or API is down or silent, beyond the five-second deadline

Also breaks F03-S01 AC-19 (found by TC-F03-S01-038).

## Steps

Account: a synthetic `OPERATOR_ADMIN` bridged into the console's cookies. Data: one provisioned
salon. Local runtime (API 8090, web 3100, PostgreSQL 5440).

1. Open `http://console.localhost:3100/console/salons/{id}` (the salon detail with its
   subscription card); it renders normally.
2. Stop PostgreSQL (`infra/scripts/local-service.sh stop postgres`), or pause it (`pause postgres`),
   or freeze the API process (`SIGSTOP`).
3. Navigate to the same page (or `/console/salons`) and time until the console's error state
   (`console-error`) is visible. Restore the dependency.

## Expected

F03-S01 AC-19 and F03-S02 AC-16: with a stopped or silent database or API, console operations and
billing screens report unavailable within the existing five-second end-to-end outage deadline.
`operator-console.md`: BFF upstream calls share one three-second budget, with no fresh budget per
chained call.

## Actual

Every time (two rounds each, identical within 10 ms):

| Dependency | Page | Server response | Error visible |
| --- | --- | --- | --- |
| PostgreSQL stopped | `/console/salons/{id}` | 3,024 ms (HTTP 200) | 5,352 ms |
| PostgreSQL paused | `/console/salons/{id}` | 3,024 ms (HTTP 200) | 5,360 ms |
| API frozen | `/console/salons` | 3,037 ms (HTTP 200) | 6,367 ms |

The page's server render waits a full three-second upstream budget, then the page's own client
request waits again before the error appears. The workspace's billing page answers within the
deadline (2.05 s, the platform "Service unavailable" page), and a stopped API (connection refused)
is reported at once; the silent cases and the database outage on the console exceed it.

## Evidence

- Per-route timing marks from the by-hand reproductions: `qa/reports/f03-dev/diag3.log` (database
  stopped and paused) and `qa/reports/f03-dev/api-frozen-marks.json` (API frozen, two rounds; the
  BFF's principal and salons reads each answer 503 after about 3,010 ms).
- Recorded run: entries `F03-S01 console-security` (TC-F03-S01-038, step "silent") and
  `F03-S02 billing-screens` (TC-F03-S02-027, step "down"), run
  `qa/reports/20261019-093648-F03-09df7b0ccb75` (revision c1d6e73).

## Re-verification

