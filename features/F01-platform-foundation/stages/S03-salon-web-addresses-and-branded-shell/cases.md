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

## CR-016 cases (reconciled 2026-10-17)

CR-016 rebuilds the web app on Tailwind CSS 4 and shadcn/ui with no intended visual or behaviour change. TC-F01-S03-001 to -013 are unchanged in meaning and are rerun. The shared design helper now reads the prototype's tokens under shadcn/ui's variable names (AC-13); their values are unchanged.

### TC-F01-S03-014 Tailwind CSS 4 and shadcn/ui are set up and the styling rules test fails on each introduced violation

- **Type:** build
- **Priority:** high
- **Covers:** AC-13
- **Performed as / preconditions:** QA operator; committed tree with its dependencies installed. Nothing in the repository is altered: violations are made in a copy of the committed `apps/web` in a temporary folder outside it.
- **Steps:** Read the committed `postcss.config.mjs`, `components.json`, `src/app/globals.css`, `src/styles/theme.css` and `src/lib/utils.ts`; list every style sheet and every style-sheet import under `apps/web/src`. Run `src/styles/styling-rules.test.ts` on the unaltered copy; then, one at a time, add a CSS Module, a second style sheet, a component with `bg-[#D6086A]` and a component holding the hex colour `'#D6086A'`, and run it again; remove each and run it once more.
- **Expected:** Tailwind's PostCSS plugin with no `tailwind.config`, Tailwind 4; `components.json` names `globals.css`, CSS variables and the `@/components/ui` and `@/lib/utils` aliases; `globals.css` imports Tailwind and the theme; `theme.css` holds `--background #F3F0EB`, `--foreground #111827`, `--card #FFFFFF`, `--secondary #EAE5DC`, `--muted-foreground #6B7280`, `--border #DDD6CA`, `--destructive #B3261E`, `--ring #1D5FD0`, with `--primary`, `--primary-foreground` and `--accent` reading the brand variables; `cn()` built on tailwind-merge. The only style sheets are `globals.css`, `theme.css` and `plain-page.css`, no CSS Module; only the root layout imports a style sheet, and the plain pages read theirs as text. The test passes on the unaltered and the restored copy and fails, naming the added file, for each violation.
- **Automation:** `qa/checks/F01/S03-styling-setup.sh`

### TC-F01-S03-015 The rebuilt shell, navigation and foundation pages keep the reference look at six widths and three brands

- **Type:** responsive
- **Priority:** high
- **Covers:** AC-14
- **Performed as / preconditions:** A synthetic Manager of each salon, signed in through the API; a public caller for the foundation pages. Rows: alpha `#D6086A` (default), beta `#1F2937` (dark, long name), gamma `#FDE68A` (light). Gamma is suspended and the API stopped briefly for the plain pages, and both are restored.
- **Steps:** At 360/390/768/1024/1280/1440 open each salon's workspace account: read the theme tokens and loaded Poppins, the shell, header, navigation and main area, the selected navigation item, the test ids and the fit; Tab to the first navigation link. At the same widths open the platform status page and the application not-found page (`/salon-site` on alpha), and Tab to the not-found page's link. Request the plain salon-not-found, temporarily-unavailable and service-unavailable pages.
- **Expected:** Warm canvas `#F3F0EB`, Poppins 400/500/600 at 15px and the prototype's tokens everywhere; sticky 60px header (white with a border from 900px), the 200/236px rail from 900/1200px and fixed bottom tabs below 900px; the selected rail item filled with the brand and a readable foreground, the phone tab a soft brand tint with ink text; the header names the salon; test ids follow the convention; no horizontal overflow or clipping. Keyboard focus draws a solid 3px `#1D5FD0` outline offset 2px. Status card white, 24px radius, no shadow, 22px heading; the not-found page neutral with no salon's colour. The plain pages answer 404/503/503 with their handles, no `<script>`, the theme inlined and no salon's name or colour.
- **Automation:** `qa/web/F01/S03-shadcn-shell.spec.ts`; screenshots under the run's `design/` folder.

### TC-F01-S03-016 Every shell and foundation screen renders as the pre-CR-016 build did

- **Type:** responsive
- **Priority:** high
- **Covers:** AC-14
- **Performed as / preconditions:** QA operator. The last pre-CR-016 product (`fefc4fd`, identical to the QA-passed CR-013 build) is built in a scratch worktree outside the repository and served on a second port against the same API; the same sessions and data are used for both builds.
- **Steps:** Capture each state from the old and the new build back to back at the six widths: platform status (loaded, unavailable), application not-found, the three plain pages, and the signed-in workspace shell on account, staff and audit for alpha, beta and gamma. Compare each pair pixel by pixel, and their test-id sets, visible text and overflow.
- **Expected:** Pairs pixel-identical; any difference is explained by data or by a change CR-016 requires, and is classified. Same test ids and text, no overflow.
- **Manual:** performed 2026-10-17; it needs a second build of an earlier revision, so it is a one-time migration judgment rather than regression automation. Evidence `qa/reports/20261017-CR016-manual/`.
