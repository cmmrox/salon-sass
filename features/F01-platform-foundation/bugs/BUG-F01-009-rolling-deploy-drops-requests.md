---
id: BUG-F01-009
title: Under the deploy check's 50 virtual users, a rolling deploy, its rollback and an instance loss fail requests, because the one instance left serving goes unhealthy or too slow
severity: major
status: fixed
found_after_merge: 2026-10-30
found_by: TC-F01-S05-008
breaks: AC-8
found_on: 2026-10-30
fixed_in: 78860f5
fix_note: "The edge balances by least connections with a 5 s health timeout and reaches every instance at a fixed address; deploys keep a third web instance (web-surge) serving while web-1 and web-2 are replaced, wait 15 s for each new instance to warm and survive a transient unhealthy report."
developer_note: "Cause, measured with an edge access log and k6 JSON logs on 4ad5e9b (fresh volume): every failure was a k6 10 s client timeout (\"aborting with incomplete response: reading: context canceled\" at the edge), none a connection error. The web tier is the bottleneck (each Node.js process ~100-150% CPU, its one main thread saturated; the API answered in a 3 ms median even alone). A warm web or API instance alone does not fail (60 s with one web: 0 of 42,868; an API restart: 0 of 35,628). Failures fell in transitions: (1) round_robin gave a cold or busy web instance half of every request, so most of the 50 VUs queued on it (sign-in renders up to 9.86 s); (2) the 2 s health timeout ejected a busy-but-answering web instance and, with its partner out, left no upstream (the origin/main 503s); (3) the next instance was taken out 3 s after the previous was ready, while cold JVMs still burned CPU; (4) Compose --wait aborted on a transient unhealthy (the \"did not become ready\" runs); (5) with one web instance during a deploy on this busy host (load average ~20), 21 sign-in renders exceeded 10 s. Fix commits: 268759f (least_conn, health_timeout 5s, 15 s settle, readiness wait, filtered access log: no headers, client address, query string or export token), 8c7de03 (web-surge during deploys), 78860f5 (security review: fixed edge-network addresses so no upstream or the web API base is resolved by name outside the stack; web-surge removed at deploy start, on success and on failure; restart no). Results: base 4ad5e9b check: deploy 60 of 29,834 failed, instance loss passed. Harness rollback on base: 0 of 61,939 but max 9.86 s (69 web responses over 5 s); same with 268759f: 0 of 50,243, max 3.9 s, none over 5 s. QA check on a4cc0b4 (before the surge): deploy 29 of 30,704 failed, all in the lone-web window; instance loss 0 of 5,642 (max 5.0 s). QA check on 78860f5, fresh volume: deploy 0 of 39,600, every threshold met, max 3.9 s, both versions seen; the rollback was aborted mid-run and instance loss and a second run were not run, by the owner's decision to stop load testing. So the final fix 78860f5 has not been re-run through the whole S05-deployment.sh check (rollback, instance loss, two consecutive passes). Tests: infra/scripts/test-edge-busy-instance.sh (fails 4 of 4 on the old Caddyfiles: busy only instance ejected to 503, slow instance given 80 of 160), test-stack-deploy-wait.sh, test-stack-deploy-order.sh (6 cases), test-stack-addresses.sh (fails 9 of 9 on 8c7de03); all 8 infra/scripts/test-*.sh pass on 78860f5. Applying 78860f5 to a running stack recreates its edge network once (stack-down.sh, then stack-up.sh)."
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
