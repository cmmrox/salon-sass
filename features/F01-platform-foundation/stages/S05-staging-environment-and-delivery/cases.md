# F01-S05 test cases

Environment: local only; the synthetic alpha, beta and gamma salons of docs/qa/test-plan.md. No user accounts exist until F02.

## Cases

### TC-F01-S05-001 Production images report tested commit and run without root or env files

- **Type:** build
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Inspect version/commit labels, actual UID, env files and edge metadata.
- **Expected:** Exact tested SHA/version, UID nonzero, no .env files.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-002 Image scanning rejects fixable high and critical vulnerabilities

- **Type:** build
- **Priority:** high
- **Covers:** AC-2
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Scan both production images; scan alpine:3.18.0 as vulnerable control.
- **Expected:** Zero on current images; nonzero and CVE named on vulnerable control.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-003 Every salon and safe host has valid HTTPS redirect and HSTS

- **Type:** security
- **Priority:** high
- **Covers:** AC-3
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Use only exported stack CA for salon/neutral hosts; check HTTP redirect; omit CA as negative.
- **Expected:** Correct pages/API, hostname-valid cert, HSTS, same-path HTTPS redirect; untrusted certificate refused.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-004 New salon gets HTTPS immediately without edge restart

- **Type:** security
- **Priority:** high
- **Covers:** AC-4
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Add unique synthetic salon via stack-seed; immediately read web/API; compare edge identity.
- **Expected:** Valid TLS and new brand on first request, same edge process.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-005 Only edge is published and forged tenant fields do not switch salons

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-5
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Inspect published container ports; request alpha web/API with beta selectors.
- **Expected:** Only HTTP/HTTPS edge ports; alpha stays alpha.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-006 Production-like roles enforce least privilege idempotently

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Inspect roles/table ownership; SELECT tenant as app; rerun role provisioning and compare attributes.
- **Expected:** No superuser/BYPASSRLS/table owner; SELECT refused; second provisioning leaves role attributes.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-007 Staging secrets and local hooks are closed by default

- **Type:** security
- **Priority:** high
- **Covers:** AC-7
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Try startup from committed copy with empty secrets; inspect hooks/metrics; scan tracked files for real local secret values; preseed count is recorded in setup.
- **Expected:** Startup refuses named setting before action; hooks 404; no automatic tenant; no committed secret.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-008 Deploy and rollback preserve every response under continuous traffic

- **Type:** resilience
- **Priority:** critical
- **Covers:** AC-8
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Run >=50 VUs over web/API/metadata across salons; deploy N+1, then N; compare migration histories.
- **Expected:** Zero failed requests/connections/cross-tenant results; N before, both observed during rollout, N+1 after; rollback same; new migration state retained.
- **Automation:** `qa/checks/F01/S05-deployment.sh`

### TC-F01-S05-009 One API and web instance can disappear and rejoin under traffic

- **Type:** resilience
- **Priority:** critical
- **Covers:** AC-9
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Under 50 VUs stop one API/web, confirm surviving reads, start them and wait readiness.
- **Expected:** Zero failed GET/cross-tenant result; return without edge action.
- **Automation:** `qa/checks/F01/S05-deployment.sh`

### TC-F01-S05-010 Missing images refuse deployment without stopping anything

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Snapshot instances/version; deploy absent tag; compare snapshots and query.
- **Expected:** Nonzero with image message before instance action; identities/start times/version unchanged; serves normally.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-011 Web API and collector preserve tenant trace without secrets

- **Type:** security
- **Priority:** high
- **Covers:** AC-11
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. View alpha with trace and secret markers; read web/API JSON logs and collector spans/metrics.
- **Expected:** One alpha web line, same API trace/id, collector API spans carry alpha, S04 meters exist, no query/cookie/token value logged.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-012 Delivery workflow enforces CI main publication and manual staging

- **Type:** build
- **Priority:** high
- **Covers:** AC-12
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Run actionlint; inspect publish/deploy gates; execute missing-secret guard block outside workflow.
- **Expected:** Valid workflow, build/scan after successful main CI only; dispatch+staging deploy; guard fails naming each absent secret.
- **Automation:** `qa/checks/F01/S05-stack.sh`

