---
id: BUG-F01-001
title: Rolling rollback briefly removes every web upstream and returns 503
severity: major
status: fixed
found_by: TC-F01-S05-008
breaks: AC-8
found_on: 2026-10-13
fixed_in: 24006d5303302e006317c3ccedf6af3f7b4734f8
fix_note: Caddy now requires three consecutive failed active health checks before removing a backend; a pinned-image regression test verifies recovery after one transient failure.
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F01-001 Rolling rollback briefly removes every web upstream and returns 503

## Steps

Account: signed-out synthetic salon visitor. Role: public reader. Data: alpha, beta and gamma
from the approved seed, local HTTP-only `salon-stack`, images `qa-f01-n` and `qa-f01-next`
built from QA revision `940b696` (the next image additionally contains QA's additive V8 fixture).

1. Start the production-like local stack and seed the three synthetic salons.
2. Run TC-F01-S05-008 through `qa/scripts/sdlc run --feature F01`, with
   `QA_STACK_NEXT=qa-f01-next`. The registered deployment-traffic entry runs 50 k6 VUs
   continuously reading `/`, `/api/v1/salon` and metadata over all three salon hostnames.
3. Deploy `qa-f01-next`; once complete, deploy `qa-f01-n` again while the same traffic continues.
4. Correlate `traffic-rollback.json` with the edge's health/error logs.

## Expected

F01-S05 AC-8 / NFR-18: rollout and rollback answer every request with zero failed requests,
connection errors or foreign-salon responses. At least one ready web upstream remains available.

## Actual

The rollback completed in 122 seconds but returned 36 HTTP 503 responses out of 29,496 requests
(error rate 0.0012205044751830757). Alpha had 10 failed page requests, beta 10 and gamma 16.
The edge logged `no upstreams available` on `/` from 2026-10-13 09:20:50.3242857 UTC
through 09:20:50.6225514 UTC. These responses occurred roughly nine seconds before traffic
was stopped; they are separate from the later `context canceled` messages caused by QA shutdown.
No cross-tenant response occurred (count zero).

Immediately before the errors, web-2 was absent while being replaced. The edge's 09:20:49.7628708
UTC readiness probe of the remaining web-1 timed out waiting for headers. It returned to the
rotation at 09:20:50.6288514 UTC, after the 36 errors. This observed interval proves that the
rolling replacement did not retain an available web upstream throughout rollback. This occurred
in one recorded rollback, with 36 independently failed page requests. No blind rerun was used.

## Evidence

- `qa/reports/20261013-142212-F01/summary.json`: 31 of 32 automation entries passed; only
  deployment-traffic failed.
- `qa/reports/20261013-142212-F01/F01-S05-deployment-traffic.log`: TC-F01-S05-008 failed
  rollback `http_req_failed: rate==0`; instance-loss TC-F01-S05-009 passed.
- `qa/reports/20261013-142212-F01/traffic-rollback.json`: unchanged zero-error and tenant thresholds.
- `qa/reports/20261013-142212-F01/stack-deploy-qa-f01-n.log`: rollback replaced API instances
  in 25/31 seconds and web instances in 17/18 seconds, total 122 seconds.
- `qa/reports/20261013-142212-F01/rollback-edge-filtered.jsonl`: retained exact health and
  error records for this interval; no credentials or real personal data.

## Re-verification

Pending developer fix. First repeat the existing deployment/rollback case with the same
50 VUs and zero-error thresholds, then the full F01 run. Keep the failure evidence above.
