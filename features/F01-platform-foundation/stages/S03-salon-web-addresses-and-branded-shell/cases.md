# F01-S03 test cases

Environment: local only; the synthetic alpha, beta and gamma salons of docs/qa/test-plan.md. CR-010 verifies staff-only entry over the integrated F02 runtime; no customer web surface is delivered. Since CR-012 (F02-S04 AC-9) signed-out entry is the standalone staff sign-in, which names the salon (`staff-sign-in-name`) and has no workspace header; CR-013 (F02-S04 AC-10/AC-11) makes the salon's brand the accent of every salon screen and forbids any workspace frame before sign-in. Reconciled 2026-10-17 under change-requests step 5.

## Cases

### TC-F01-S03-001 Each salon hostname enters only its own staff workspace

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-1
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Open all three salon hosts, refresh, and request an uppercase host.
- **Expected:** 307 no-store to fixed same-host /workspace, then unauthenticated staff password sign-in; the sign-in names the full salon and the title is the salon's name, no other salon data. Legacy /sign-in and /account lead to staff sign-in with no customer controls.
- **Automation:** `qa/web/F01/S03-branded-shell.spec.ts`

### TC-F01-S03-002 Signed-out staff entry identifies its own salon with no workspace or customer shell

- **Type:** branding
- **Priority:** high
- **Covers:** AC-2
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Open each salon's `/` signed out; read the sign-in's salon name and initial; look for the workspace shell, header and navigation and the customer shell and navigation.
- **Expected:** The staff sign-in with that salon's full name and initial; no workspace frame (CR-012, CR-013 AC-11) and no customer shell or navigation (CR-010). The brand accent on sign-in and workspace is F02's TC-F02-S04-024; "neutral workspace" no longer applies (CR-013).
- **Automation:** `qa/web/F01/S03-branded-shell.spec.ts`

### TC-F01-S03-013 Staff entry and foundation pages match the supplied reference grammar at all six widths

- **Type:** responsive
- **Priority:** high
- **Covers:** AC-12
- **Performed as / preconditions:** Public synthetic caller; running local product with three salons. Published HTML final cascade and CR-007 are the expected-result source.
- **Steps:** At 360/390/768/1024/1280/1440 inspect every salon's signed-out staff entry, bare-domain status, unknown and suspended pages. Verify actually loaded Poppins 400/500/600, 15px body and the exact warm palette tokens on all of them, and that the signed-out entry shows the salon-named sign-in with no workspace header/rail and no customer shell. Save rendered screenshots. Restore gamma activation.
- **Expected:** Reference palette/type hold on every page; no horizontal overflow or clipped content. The signed-in workspace's responsive rail/tabs and header are proved by F02's TC-F02-S04-023 (F01 has no signed-in fixture). Only compact identity headers may ellipsize, retaining full DOM name and full-name tooltip; verify this narrow exception explicitly. Brand contrast remains covered by TC002. Foundation pages inherit warm type and surfaces. No fictional booking/catalogue routes appear.
- **Automation:** `qa/web/F01/S03-branded-shell.spec.ts`; rendered evidence under the run's `design/` folder.
- **Reference limitation:** Browser policy explicitly rejected local file preview and alternative access; this pass uses the source-defined final reference contract and rendered product screenshots, and does not claim a side-by-side preview.

### TC-F01-S03-003 Unknown reserved and lookalike hosts share the neutral not-found page

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-3
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Request unknown, www, console, two-prefix and suffix-lookalike hosts.
- **Expected:** 404, identical safe body, no salon identifiers.
- **Automation:** `qa/web/F01/S03-branded-shell.spec.ts`

### TC-F01-S03-004 The bare domain retains the status page

- **Type:** functional
- **Priority:** high
- **Covers:** AC-4
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Read meta then open the bare domain.
- **Expected:** Service and version equal API metadata.
- **Automation:** `qa/web/F01/S03-branded-shell.spec.ts`

### TC-F01-S03-005 Suspension hides the brand and activation restores the next request

- **Type:** functional
- **Priority:** high
- **Covers:** AC-5
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Suspend gamma, request gamma web/API and other salons, then activate gamma.
- **Expected:** 503 neutral web; API 403 salon_suspended; alpha/beta unaffected; gamma immediately307 to /workspace after activation.
- **Automation:** `qa/web/F01/S03-branded-shell.spec.ts`

### TC-F01-S03-006 Forged client tenant selectors cannot change the web salon

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Prove beta exists, then request alpha with beta in query, cookie, forwarded headers and internal resolved header.
- **Expected:** Alpha brand only; never beta.
- **Automation:** `qa/web/F01/S03-branded-shell.spec.ts`

### TC-F01-S03-007 Simultaneous pages never cross salon brands

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-7
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Release 60 virtual users for 300 requests evenly across salons.
- **Expected:** 300 correct pages (each the salon-named staff sign-in reached through the same-host entry redirect); zero errors and brand mismatches.
- **Automation:** `qa/load/F01/S03-web-rush.js`

### TC-F01-S03-008 A stopped API gives bounded safe unavailability and recovers

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-8
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Stop API, request alpha, start API, request alpha again.
- **Expected:** 503 service unavailable within 5s, no brand or internals; next root request307 to /workspace without web restart.
- **Automation:** `qa/web/F01/S03-branded-shell.spec.ts`

### TC-F01-S03-009 A silent API gives bounded safe unavailability and recovers

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-8
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Stand in with a listener that receives requests without answering; restore API.
- **Expected:** 503 within 5s and restoration on next request; stand-in observed at least one request.
- **Automation:** `qa/web/F01/S03-branded-shell.spec.ts`

### TC-F01-S03-010 Long name and safe error states fit both widths and pass axe

- **Type:** accessibility
- **Priority:** high
- **Covers:** AC-9
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Inspect beta, unknown host and suspended gamma at 360 and 1280px.
- **Expected:** No overflow/clipping and zero serious/critical axe findings.
- **Automation:** `qa/web/F01/S03-branded-shell.spec.ts`

### TC-F01-S03-011 Strings and result test ids follow the shared convention

- **Type:** build
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Read visible strings and test ids on all three pages.
- **Expected:** Every string translated except configured salon name; expected result handles exist and ids have prescribed names.
- **Automation:** `qa/web/F01/S03-branded-shell.spec.ts`

### TC-F01-S03-012 The contract describes a suspended salon

- **Type:** api
- **Priority:** high
- **Covers:** AC-11
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local production-like stack seeded and its exported CA; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. Inspect salon 403 and error enumeration; shared contract drift checks run as S01/S02 automation.
- **Expected:** 403 documented and salon_suspended exposed; contract check passes.
- **Automation:** `qa/web/F01/S03-branded-shell.spec.ts`
