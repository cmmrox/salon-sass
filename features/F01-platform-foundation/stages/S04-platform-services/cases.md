# F01-S04 test cases

Environment: local only; the synthetic alpha, beta and gamma salons of docs/qa/test-plan.md. No user accounts exist until F02.

## Cases

### TC-F01-S04-001 Error responses retain trace and do not echo sensitive input

- **Type:** security
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Send table of 404/400/403/405/406/415/500/503 errors with a fixed W3C trace.
- **Expected:** Exact status/code/problem JSON, shared traceId, no internals or input.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-002 Invalid probes produce field errors without writes

- **Type:** data-integrity
- **Priority:** critical
- **Covers:** AC-2
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Send negative, too-large, wrong-type and multi-field probe bodies; compare DB counts.
- **Expected:** 400 bad_request, correct field errors; no outbox/audit effect.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-003 Problem contract enumerates every code

- **Type:** api
- **Priority:** high
- **Covers:** AC-3
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Compare Problem.code enumeration with all documented stable codes.
- **Expected:** Exact enumeration including salon_suspended; shared contract drift command passes.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-004 UTC instants remain unchanged under another machine zone

- **Type:** data-integrity
- **Priority:** high
- **Covers:** AC-4
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Restart API under Pacific/Chatham; read salon zones/meta; write at fixed clock and compare DB epoch.
- **Expected:** Correct zones, UTC meta, identical probe/DB instant.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-005 DST and midnight render exact salon-local offsets

- **Type:** data-integrity
- **Priority:** high
- **Covers:** AC-5
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Set London before/after DST and alpha before midnight; restore clock.
- **Expected:** Exact UTC and offset strings; +00:00 rather than Z; restore real clock.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-006 Committed work is sent exactly once and audited

- **Type:** data-integrity
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Create a zero-failure probe with caller trace; poll read until sent.
- **Expected:** Sent within 5s, 1 attempt/call; one matching audit action/time/after/trace.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-007 Retry backoff dead-letter and meter follow configured limits

- **Type:** resilience
- **Priority:** critical
- **Covers:** AC-7
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Create failures 2 then failures 50; advance clock past due/backoff/breaker; read each attempt and dead meter.
- **Expected:** Third attempt sent; exponential backoff; 10 attempts dead with last error; dead meter +1; no more attempts.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-008 An aborted transaction leaves no outbox or audit work

- **Type:** data-integrity
- **Priority:** critical
- **Covers:** AC-8
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Record counts; create abort true; compare counts.
- **Expected:** 500 internal_error and unchanged counts.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-009 Twenty slow deliveries do not hold up salon reads

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-9
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Create 20 sixty-second probes; while sending read all salon brands and wait for first failed attempt.
- **Expected:** Reads 200 each <1s; provider times out at 10s rather than 60s.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-010 Open breaker holds new work without harming the API

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Cause five failures; create beta probe; inspect before clock advance; pass breaker wait.
- **Expected:** Pending, 0 attempts/calls until wait; health/brand healthy; later delivered.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-011 API restart recovers the interrupted delivery id

- **Type:** resilience
- **Priority:** critical
- **Covers:** AC-11
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Create 8s probe; wait sending; stop/start API; advance beyond 60s lease.
- **Expected:** Same id; retry attempt 2 sent; one audit; no repair.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-012 One hundred simultaneous probes deliver once for their salon

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-12
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Submit 100 probes distributed 34/33/33 together; read all and delivery logs.
- **Expected:** Every probe sent with 1 attempt/call; every matching delivery line carries its own salon.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-013 Probes and audit/outbox records enforce salon and write isolation

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-13
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Prove beta owns a probe; ask beta for alpha probe and absent id; read transactions as salon_app; try audit UPDATE/DELETE.
- **Expected:** Identical 404 except instance/trace; no unbound/foreign rows; audit changes refused; S02 catalogue check includes both tables.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-014 Request logs preserve tenant and strip sensitive request values

- **Type:** security
- **Priority:** high
- **Covers:** AC-14
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Request gamma with query/cookie marker and trace, then with a separate trace and an invalid Bearer marker; read JSON lines.
- **Expected:** The noncredential request returns 200 and logs exactly one GET with Gamma's tenant and the caller trace. The invalid credential returns 401 `invalid_session`, with identical caller/response/log trace and exactly one GET log before tenant binding (no `tenant.id`). Both log sanitized method/path/status/positive duration and no marker, preserving the approved F02 credential contract.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-015 Background cleanup retains each salon newer messages

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-15
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Deliver alpha in past; pass 30 days; create beta newer message; poll old removal and job logs.
- **Expected:** Old alpha gone; beta remains; job lines name each salon.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

### TC-F01-S04-016 Staging exposes no local hook or metrics

- **Type:** security
- **Priority:** high
- **Covers:** AC-16
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Request every local-hook kind and metrics under production-like staging; inspect committed contract.
- **Expected:** 404 not_found, hooks absent from OpenAPI.
- **Automation:** `qa/checks/F01/S04-other-profile.sh`

### TC-F01-S04-017 Database restart recovers interrupted committed delivery

- **Type:** resilience
- **Priority:** critical
- **Covers:** AC-11
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. During 8s probe delivery stop/start postgres; wait healthy; pass lease.
- **Expected:** Same id eventually sent attempt2 without repair; one audit.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`
### TC-F01-S04-018 Client tenant selectors cannot write a probe into another salon

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-13
- **Performed as:** Public caller on alpha and beta synthetic local hosts.
- **Preconditions:** Both salons active; beta can read its existing probe.
- **Steps:**
  1. POST a probe on alpha with beta in query parameters, X-Tenant-Id, X-Forwarded-Host and a tenant cookie.
  2. Read the created id from alpha and beta.
- **Expected:** Alpha reads its probe; beta receives 404 not_found. The create action must not bind a foreign salon and delivery/audit remain owned by alpha.
- **Automation:** `qa/api/F01/S04-platform-services.spec.ts`

Harm protected: the new write endpoint must not accept another salon selected by the client; S02's read-only brand endpoint alone cannot catch that regression.
