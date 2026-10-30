---
id: BUG-F01-009
title: Under the deploy check's 50 virtual users, a rolling deploy, its rollback and an instance loss fail requests, because the one instance left serving goes unhealthy or too slow
severity: major
status: open
found_after_merge: 2026-10-30
found_by: TC-F01-S05-008
breaks: AC-8
found_on: 2026-10-30
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F01-009 Under the deploy check's 50 virtual users, a rolling deploy, its rollback and an instance loss fail requests, because the one instance left serving goes unhealthy or too slow

Also F01-S05 AC-9 (TC-F01-S05-009). Found by the release suite after the modular-architecture refactor, and
present before it: origin/main's own API and web fail the same check (below). The check had not run since
F01 (2026-10-13): from F03 until BUG-F01-007's fix no stack API could start.

## Steps

Account: none. Data: the production-like stack on a new, empty volume, seeded (`stack-seed.sh`); images N
and N+1 built from one tree (`images-build.sh`, then `qa/checks/F01/S05-fixture-build.sh <N+1>`). The
machine otherwise quiet (Docker: 10 CPUs, 7.7 GiB).

1. `QA_STACK_NEXT=<N+1> bash qa/checks/F01/S05-deployment.sh`: k6 with 50 virtual users on `/`,
   `/api/v1/salon` and `/api/v1/meta` of the three salons through the edge while `stack-deploy.sh` goes
   N -> N+1, then N+1 -> N, then while one API and one web instance are `docker stop`ped and started.

## Expected

AC-8: deploy and rollback each complete with zero failed requests and zero connection errors. AC-9: stopping
one API and one web instance causes no failed GET.

## Actual

Every run fails (5 runs on 4 builds; the deploy direction alone passed twice):

| Build | Deploy N->N+1 | Rollback | Instance loss |
| --- | --- | --- | --- |
| refactor d9c8379, release run | 0 of 24232 failed | 9 of 21637 (connection-level "Request Failed") | 2 of 5481 |
| refactor b57dc23, run 2 | 26 of 23077 | not reached (deploy check failed first) | failed |
| origin/main API + BUG-F01-007/008 fixes, current web | 0 of 21218 | 354 of 24830 | 52 of 4876 |
| origin/main API and web (Next 16.4.1 only), same fixes | 62 of 14836, and api-2 did not become ready in time | not reached | 426 of 4477 |

A probe (one keep-alive GET per path every 100 ms) and `docker events` show where the failures fall: in the
windows where one instance of a tier carries all the traffic. On the refactor (run 2) GET / answered 503 after
4.4 s at 07:08:24 while the only ready API (api-1, started 07:06:08, healthy 07:07:05) carried the load and
api-2's replacement took 67 s to become healthy; the web, which resolves the salon through the API within 3 s,
answered 503. On origin/main (isolation 1) the lone web instance turned unhealthy while its partner was being
replaced (07:23:56 to 07:24:17), so the edge had no web upstream and answered / with fast 503s. An API instance
takes 37 to 67 s from start to healthy under this load, and in two runs `stack-deploy.sh` gave up on api-2
("did not become ready"). The edge has no access log, so connection-level failures cannot be attributed further.

So: one API or one web instance cannot carry the check's 50 virtual users on this machine, and the deploy and an
instance loss leave exactly one for a minute or more. Whether the remedy is capacity (a third instance or a
surge instance during the rollover), faster start and readiness, or health checks that do not take a busy but
working instance out of the rotation is the developer's to decide; it is not in the refactor's code.

## Evidence

Local, uncommitted: `qa/reports/20261030-113523-release-aaa901fb602c` (release run),
`qa/reports/20261030-121103-F01-S05-9b06d7734553` (targeted re-run), and
`qa/reports/20261030-F01-S05-deploy-diagnosis/` (refactor-run2, main-api-iso1, main-api-web-iso2 with k6
summaries, the probe's failures and the container events; refactor-run1-confounded is not evidence: an extra
probe load made its deploy abort). The origin/main builds were made on a temporary local branch, never shared,
and deleted.

## Re-verification

<QA, after a fix.>
