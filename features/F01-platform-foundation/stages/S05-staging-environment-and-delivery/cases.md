# F01-S05 test cases

Environment: local only; the synthetic alpha, beta and gamma salons of docs/qa/test-plan.md. No user accounts exist until F02.

## Cases

### TC-F01-S05-001 Production images report tested commit and run without root or env files

- **Type:** build
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local HTTP production-like stack seeded; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Inspect version/commit labels, actual UID, env files and edge metadata.
  2. Open alpha's entry on the HTTP local production-like stack with Chromium (the staff sign-in since CR-010/CR-012; there is no customer web shell); require successful requests for all three packaged Poppins files, loaded/checkable 400/500/600 font faces, the warm canvas token `#F3F0EB`, alpha's name, and a full-width sign-in at 390px and 1280px. Save actual font resource metadata and a rendered screenshot. Reconciled 2026-10-17: the former 430px customer canvas is not a web reference (F02-S04 AC-9).
- **Expected:** Exact tested SHA/version, UID nonzero, no .env files. Standalone image actually serves and renders the supplied design fonts and canvas, with no fallback or HTML returned in place of a font. Evidence is local HTTP only; no VPS/TLS certification.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-002 Image scanning rejects fixable high and critical vulnerabilities

- **Type:** build
- **Priority:** high
- **Covers:** AC-2
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local HTTP production-like stack seeded; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Scan both production images; scan alpine:3.18.0 as vulnerable control.
- **Expected:** Zero on current images; nonzero and CVE named on vulnerable control.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-003 Every salon and safe host resolves through the HTTP edge

- **Type:** security
- **Priority:** high
- **Covers:** AC-3
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local HTTP production-like stack seeded; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Request each salon and neutral hostname on the HTTP edge.
- **Expected:** Correct salon pages/API; bare host status200; unknown and console404. Staging TLS is deferred to TC-F01-002 under CR-005.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-004 New salon gets HTTP immediately without edge restart

- **Type:** security
- **Priority:** high
- **Covers:** AC-4
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local HTTP production-like stack seeded; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Add unique synthetic salon via stack-seed; immediately read web/API; compare edge identity.
- **Expected:** New brand on first HTTP request, same edge process.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-005 Only edge is published and forged tenant fields do not switch salons

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-5
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local HTTP production-like stack seeded; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Inspect published container ports; request alpha web/API with beta selectors.
- **Expected:** Only the HTTP edge port; alpha stays alpha.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-006 Production-like roles enforce least privilege idempotently

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local HTTP production-like stack seeded; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Inspect roles/table ownership; SELECT tenant as app; rerun role provisioning and compare attributes.
- **Expected:** No superuser/BYPASSRLS/table owner; SELECT refused; second provisioning leaves role attributes.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-007 Staging secrets and local hooks are closed by default

- **Type:** security
- **Priority:** high
- **Covers:** AC-7
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local HTTP production-like stack seeded; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Try startup from committed copy with empty secrets; inspect hooks/metrics; scan tracked files for real local secret values; preseed count is recorded in setup.
- **Expected:** Startup refuses named setting before action; hooks 404; no automatic tenant; no committed secret.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-008 Deploy and rollback preserve every response under continuous traffic

- **Type:** resilience
- **Priority:** critical
- **Covers:** AC-8
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local HTTP production-like stack seeded; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Run >=50 VUs over web/API/metadata across salons; deploy N+1, then N; compare migration histories.
- **Expected:** Zero failed requests/connections/cross-tenant results; N before, both observed during rollout, N+1 after; rollback same; new migration state retained.
- **Automation:** `qa/checks/F01/S05-deployment.sh`

### TC-F01-S05-009 One API and web instance can disappear and rejoin under traffic

- **Type:** resilience
- **Priority:** critical
- **Covers:** AC-9
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local HTTP production-like stack seeded; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Under 50 VUs stop one API/web, confirm surviving reads, start them and wait readiness.
- **Expected:** Zero failed GET/cross-tenant result; return without edge action.
- **Automation:** `qa/checks/F01/S05-deployment.sh`

### TC-F01-S05-010 Missing images refuse deployment without stopping anything

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local HTTP production-like stack seeded; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Snapshot instances/version; deploy absent tag; compare snapshots and query.
- **Expected:** Nonzero with image message before instance action; identities/start times/version unchanged; serves normally.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-011 Web API and collector preserve tenant trace without secrets

- **Type:** security
- **Priority:** high
- **Covers:** AC-11
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local HTTP production-like stack seeded; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. View alpha with trace and secret markers; read web/API JSON logs and collector spans/metrics.
- **Expected:** One alpha web line, same API trace/id, collector API spans carry alpha, S04 meters exist, no query/cookie/token value logged.
- **Automation:** `qa/checks/F01/S05-stack.sh`

### TC-F01-S05-012 Delivery workflow enforces CI main publication and manual staging

- **Type:** build
- **Priority:** high
- **Covers:** AC-12
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local HTTP production-like stack seeded; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Run actionlint; inspect publish/deploy gates; execute missing-secret guard block outside workflow.
- **Expected:** Valid workflow, build/scan after successful main CI only; dispatch+staging deploy; guard fails naming each absent secret.
- **Automation:** `qa/checks/F01/S05-stack.sh`


## Deployment fixture provenance

TC-F01-S05-008 uses N and N+1 images built by `infra/scripts/images-build.sh` from the tested revision. `qa/checks/F01/S05-fixture-build.sh` adds only the QA-owned `V8__qa_rollout_probe.sql` to the next API image's jar. It compares every original jar entry byte-for-byte and asserts exactly one new resource. No application source or migration is edited. The fixture creates only a synthetic platform table/marker; rollout must apply V8 while old instances serve, and rollback must retain that migration and marker. The original next product image stays tagged `-product`; fixture images are local only and must never be published.
