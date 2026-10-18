# F01 Platform foundation and tenant isolation: product review

## BUG-F01-006 re-verification (2026-10-18)

BUG-F01-006 is verified at 18bb512. The API production image now carries `libexpat-2.9.0-r0`, and `images-scan.sh` passes both images. The S05 production-like stack (12/12 cases) and deployment checks pass with images N and N+1 built at that tree. Sealed F01 delta `qa/reports/20261018-022325-F01-delta-9149b677b3ef`: 13/13 entries pass. Developer gates `qa/reports/20261018-021645-F01-commands-7797ee59cdeb`: 10/10. The web entries were re-executed at the same tree only so that the closure receipts match the reviewed tree; CR-017's results are otherwise unchanged. QA status: pass.

Reviewed revision `c522818`. The F02 re-run at 18bb512 found a timing defect in TC-F02-S04-029, repaired in `c522818` (see F02's review). The grouped assessment draft is `qa/reports/cr017-assessment-draft/scoped-qa.json`.


## Current CR-017 independent review (2026-10-18)

Reviewed as: a professional on a 360/390px phone, a receptionist on a 768px tablet and a salon owner at a 1280-1440px desk, signed in to alpha (`#D6086A`), beta (`#1F2937`, long name) and gamma (`#FDE68A`); a caller on the platform domain, an unknown host and a suspended salon.
Compared with: the accepted CR-016 build (`79ec9d2`, rendered side by side on a second port) and the prototype's Salon mode tokens. Competitor findings below are not refreshed.

### Verdict

The shadcn/ui Sidebar works well on every width. From 900px nothing changes for a desk user: the same 200/236px white rail, brand-filled current item and 60px header. On a phone the bottom tab bar is replaced by a menu button in the header that opens the Sidebar as a sheet. It is keyboard- and screen-reader-correct: a named "Menu" button that reports open or closed, a "Staff workspace" dialog that focuses the current route, focus kept inside, and Escape, the backdrop or choosing a route closing it with focus back on the button. Only permitted routes appear. Every shell and foundation state uses the component the web.md map names, and nothing native or hand-built remains. One product defect blocks the pass, and it is not CR-017's doing: the unchanged API production image now carries a newly published, fixable HIGH vulnerability (BUG-F01-006, major). QA status: failing until it is fixed.

### Verification in this pass

- Tested revision `2fc8425e6048d6fa133c6169b7d63265adc68563` on `charithm/test/CR-017-feature-qa`; product source identical to `29fb3d1` (F01-S03 at `94296b8`, F02-S04 at `0b84f53`). API and web rebuilt from this tree; stack images N and N+1 built at it.
- Developer gates `qa/reports/20261018-014949-F01-commands-fcd90bc3cfa8`: 10/10 (Maven, install, lint, typecheck, unit tests, web build, contracts, selftest, image build, workflow lint).
- Sealed F01 delta `qa/reports/20261018-015641-F01-delta-db23e058e597`: 12 of 13 entries pass. Results: web-status-page 8, web-status-page-api-down 11, strings-and-test-ids, browser-hostnames 1, branded-shell 12, web-rush 603/603 checks, styling-setup, shadcn-shell 1, sidebar-navigation 2 (new, TC-F01-S03-017), component-rules (new, TC-F01-S03-018), deployment-traffic 2 cases and the journey. production-like-stack had 11 of 12 cases pass; TC-F01-S05-002 failed on BUG-F01-006.
- Retained under CR-009, not rerun: the S01/S02 API, database, migration, contract, load, seed and listening checks and S04, because only `apps/web` and the lockfile changed. The impact is in the draft `qa/reports/cr017-assessment-draft/scoped-qa.json`.
- Manual, performed 2026-10-18: TC-F01-S03-016 (comparison with the CR-016 build), TC-F01-S03-019 (the owner's component-map review) and TC-F01-S01-040 (status page states). Evidence is in `qa/reports/20261018-CR017-manual/`.
- Expectations CR-017 made wrong were corrected without changing their meaning. The navigation helper now checks the Sidebar, and waits for its width transition after a resize. The styling check expects the generated plain-page CSS. The source scan lets composed ui primitives forward test ids. A design capture now waits for an open modal instead of the main landmark it hides. The new cases were seen failing on the CR-016 build for the intended reasons (`failure-proof.log`).

### Component-map review (owner's request)

Every state was inventoried by `data-slot`: the shell on account, staff and audit at six widths and three brands, the phone sheet, status loaded, loading and unavailable, application not-found, and the three plain pages.
- **Navigation:** Sidebar (`sidebar`, `sidebar-menu-button` with `isActive`, `sidebar-trigger`, `sidebar-inset`), rendered as its sheet below 900px.
- **Containers:** Card.
- **Status:** the reachable summary and the unavailable state are Alerts (positive and caution). The retry is a Button with `asChild`; loading is Skeleton.
- **Plain pages:** they carry the Card, Alert and Button slots and classes, with Tailwind-generated CSS and no script.
- **No raw controls:** no raw `button`, `input`, `select`, `textarea`, `table` or `dialog` appears outside a shadcn/ui component in any state.
- **Plain HTML is used only where the map allows it:** headings, paragraphs, the prototype glyphs, and the not-found page's home link (a navigation link, not an action).

### What the comparison with the CR-016 build found

- **Identical** at all six widths (30 pairs): application not-found, the three plain pages, and the status page with the API down.
- **Changed, as CR-017 lists:**
  - The status summary is a taller positive Alert.
  - Below 900px the header gains the menu button and the tab bar is gone.
- **Unchanged from 900px:** the rail looks as it did. The selected Sidebar item uses the brand fill on the rail and in the phone sheet (gamma: ink on yellow), as AC-15 asks.

### How the new navigation feels

- **Desk:** unchanged. Tab reaches the salon name, then the three routes in order, and Ctrl/Cmd+B cannot collapse the rail.
- **Phone:** moving between screens now takes two taps instead of one, and the current page is no longer visible at the bottom of the screen. In return there is more room for content (48px bottom padding instead of the tab bar), and the sheet is clear and roomy (288px, 46px rows, the brand-filled current page). Back-office web apps commonly use a menu drawer, and a professional checking one screen loses little. A receptionist switching often between screens loses a tap each time. This is noted only; the owner chose it (decision 048).
- **Sheet focus:** it opens on the current route, so the next Tab or Enter moves on from where the person already is. It has no visible close control: a touch user closes it by tapping the darkened page or choosing a route (SUG-F01-03).

### Suggestions

| Id | Class | Suggestion | Disposition |
| --- | --- | --- | --- |
| SUG-F01-03 | idea | Give the phone navigation sheet a visible close button | backlog |

#### SUG-F01-03 A visible close button on the phone navigation sheet

- **Observed:** the Sidebar's phone sheet closes on Escape, on its backdrop or on choosing a route. There is no close button (`sheet-*.png` in `unmasked/`).
- **Why it matters:** a first-time phone user looking for an X may not try tapping the darkened page; it is a small hesitation.
- **Instead:** a `SheetClose` icon button in the sheet header, labelled from the translation file.
- **Evidence:** `qa/reports/20261018-CR017-manual/unmasked/sheet-*-360.png`.

### Notes for the lead (documents)

- `docs/design/ui-ux-standards.md` says `--color-brand-soft` is used for "the selected Sidebar item on a phone". The product (and F01-S03 AC-15, "the brand fill of the selected item") fills it with the brand. The document line is out of date.
- Commit the draft assessment when F01 is re-verified. F01 needs a further production-like-stack receipt after BUG-F01-006 is fixed.

### Bugs filed from this pass

- BUG-F01-006 (major, open): the API production image ships libexpat 2.8.5 with the fixable HIGH CVE-2026-77214, so `images-scan.sh` refuses it (F01-S05 AC-2, NFR-07). It reproduces 3 of 3 times, including on yesterday's CR-013 image: the advisory is new and the Dockerfile is unchanged. The web image is clean.
- BUG-F01-005 (minor) stays open; its symptom did not occur this time.


## Current CR-016 independent review (2026-10-17)

Reviewed as: a salon owner at a 1280-1440px desk, a receptionist on a 768px tablet and a professional on a 360/390px phone, signed in to alpha (`#D6086A`, default), beta (`#1F2937`, dark, long name) and gamma (`#FDE68A`, light); and a caller on the bare platform domain, an unknown host and a suspended salon.
Compared with: the accepted pre-CR-016 build of this product (`fefc4fd`, rendered side by side). The competitor findings below are not refreshed: CR-016 changes how the screens are built, not what is compared with Fresha or Booksy.

### Verdict

The Tailwind CSS 4 and shadcn/ui rebuild is invisible to the people who use it, which is what CR-016 promised. Every F01 screen and state (status loaded and unavailable, application not-found and the three plain pages, 36 pairs) is pixel-identical to the accepted build at all six widths, and so is the signed-in workspace shell (header, rail or phone tabs) on account, staff and audit for all three salons, with the same test ids and wording. The one difference on those signed-in pages is inside alpha's staff list, F02-S04's Professional-grant checkbox (TC-F02-S04-030); TC-F01-S03-016's manual record, which lists the shell among its rows, means the shell itself. The setup is the documented one and is guarded: the styling-rules test fails, naming the file, for each of the four violations AC-13 lists. No product defect was found. QA status: pass, on base-plus-delta evidence for the lead's closure.

### Verification in this pass

- Tested revision `79ec9d21516bbfb7871a52733e73335a647a11b6` on branch `charithm/test/CR-016-feature-qa` (product source identical to the developer-gated `858cac3`; the QA commits change only QA files). Running product: local API (meta `033cc61`, Java unchanged) and production web rebuilt from this tree; production-like stack images N and N+1 built at `1530cea`.
- Developer gates: `qa/reports/20261017-225523-F01-commands-0c672da5c8e5`, 10/10 at `033cc61` (Maven 518 tests, lint, typecheck, unit tests, web build, contracts, selftest, image build, workflow lint).
- Sealed F01 delta `qa/reports/20261017-231346-F01-delta-9faa7cb28a8b` at `1530cea`, 11/11 entries pass: web-status-page 8, web-status-page-api-down 11, strings-and-test-ids (TC-F01-S01-055), browser-hostnames 1, branded-shell 12, web-rush (300 iterations from 60 virtual users, 603/603 checks, no cross-salon page), styling-setup (TC-F01-S03-014, new), shadcn-shell (TC-F01-S03-015, new), production-like-stack (10 cases), deployment-traffic (TC-F01-S05-008/009) and the salon-isolation journey.
- Retained under CR-009, not rerun: S01 API, database, migration, profile, CI, contract and local-script checks; S02 API, database, load, seed, listening and contract checks; S04 platform services and nonlocal hooks. A CSS rebuild cannot reach their source, contracts or scripts; the impact assessment is in the lead's draft `qa/reports/cr016-assessment-draft/scoped-qa.json`.
- Manual: TC-F01-S03-016 (pixel comparison, below) and TC-F01-S01-040 (status page states) performed 2026-10-17. Evidence `qa/reports/20261017-CR016-manual/`.
- Triage before the passing run (`qa/reports/20261017-230554-F01-delta-e9d15d94b63c`, 8/11, preserved, not counted): strings-and-test-ids was a QA scanner defect (it flagged the shadcn/ui primitives, which forward the caller's `data-testid`, and missed shadcn/ui controls at their use sites; repaired in `1530cea` with two new failure alterations); the two S05 checks were an environment fault (the stack still ran images from `c78b909` and `QA_STACK_NEXT` was unset; repaired by building N and N+1 at the tested tree and restarting the stack with its data kept). Two shared helpers read the theme tokens under their pre-CR-016 names; they now read the shadcn/ui names AC-13 introduced, with the same expected values.

### What works well

- Pixel-for-pixel continuity: the rail, phone tabs, sticky header, cards, status panel and plain pages render exactly as before, for a dark, a light and the default brand.
- The plain proxy pages still answer 404/503 with no script, inline the theme and show no salon.
- One keyboard outline everywhere (solid 3px `#1D5FD0`, offset 2px), including the status page's retry action.
- The styling rules are enforced by a test that really fails, and the source scan now holds every shadcn/ui control to a test id where it is used.

### Suggestions

No new suggestion. SUG-F01-01 (CR-004) and SUG-F01-02 (backlog) below stand.

### Notes for the lead (documents)

- `docs/qa/automation.md` could record the method used here for a change meant to be invisible: build the last accepted revision in a scratch worktree, serve it on a second port against the same API and sessions, and compare screenshot pairs and test-id/text inventories.
- Shared QA helpers now read theme tokens by their shadcn/ui names (`--background`, `--foreground`, `--ring`, `--destructive`, ...); the source scan treats `src/components/ui/` primitives that spread `props` as forwarding the caller's `data-testid`.
- The draft assessment must be committed by the lead (QA may not write `features/*/scoped-qa.json`) and both features' `evidence.scoped_assessment` pointed at it.

### Bugs filed from this review

- None. BUG-F01-005 (minor, pre-existing scanner message) stays open; the image scan completed normally in this pass.


## Current CR-010 independent scoped review (2026-10-15)

The web application now serves salon administrators and relevant staff only. Tenant root and legacy account links reach staff access; customer OTP/profile/privacy screens are absent. Every former customer BFF GET/POST returns no-store404 with zero upstream API calls and unchanged cookies, including when the API is unavailable. The Customer HTML preview is explicitly a later React Native design; Salon web is the default. Mobile implementation waits until M1-web is released.

Final tested source: `3f6ec78b25d3445fc3fe6f4c31247c748c61e506`. F01 scoped report `qa/reports/20261015-095342-F01-delta-bf8315318646` passed3/3groups (12shell callbacks, one cross-stage journey and300concurrent entry iterations, zero wrong salons/errors). F02 scoped report `qa/reports/20261015-095550-F02-delta-76392eb5c4d7` passed5/5groups/24callbacks. Combined37Playwright callbacks plus the300iteration scenario, distinct from8automation groups. Final developer-command report `qa/reports/20261015-094736-F01-commands-cba54c3ab5ea` passed10/10. Original full F01/F02 and PR5two-fix evidence were independently checked intact and retained; this is base-plus-delta verification, not a new full feature pass. Exact path impact, source/log digests and limits are in `features/F02-sign-in-accounts-and-roles/scoped-qa.json`.

Chosen types: functional staff journeys; tenant isolation/permissions and security for every authority boundary; concurrency for staff tabs and shared tenant routing; resilience for API down/silent/shared-budget behavior; responsive/accessibility/branding/build for the changed staff shell and prototype. Unchanged Java customer identity/privacy API, data integrity/provider/load and foundation evidence is retained. No payment or native mobile feature exists in this repair; no regression/smoke or remote work was requested.

Cases were migrated under approved CR010: removed obsolete customer browser OTP/profile/privacy/coexistence journeys; retained S02/S03 Java customer API cases; repurposed S04-003 to zero-upstream customer boundary and S04-006 to staff-only aliases. Current automated scope contains every changed criterion. Original S04-018 staff manual evidence is retained only where unaffected; current human judgment is separate in `qa/reports/CR010-final-manual/judgment-observations.json`.

Manual review used actual keyboard password/Tab/Enter/navigation/logout and confirmation cancellation at360/768/1440, with19focus observations (3px solid outlines), six current staff screenshots, final prototype screenshots and current reference-mapped staff/confirmation frames. Inspected current768staff entry,360staff account,1440long-name workspace and360/1440prototype. Warm Poppins/cards/pills and desktop rail/compact bottom tabs match Salon reference; no customer canvas/navigation appears. The compact prototype label overlap is repaired. Comparison is with actual locally rendered HTML and source contract; no competitor refresh or new mobile traversal is claimed. Existing competitor findings below remain historical.

Bugs found: BUG-F02-008 major (proxy reached tenant API before rejecting customer BFF) and BUG-F02-009 minor (prototype logo covered the mobile label at360px); both independently verified after fixes. Original diagnostic report `qa/reports/20261015-094132-F02-delta-19fef4dae9b3` remains a failed report, never closure evidence. Final shared proxy changed after diagnosis, so all8affected browser groups were legitimately reverified on final source; unchanged API suites were not repeated.

Environment/test repairs: preserved persistent service sessions and private keys. An earlier documented reset completed API/database but refused an unmanaged existing web port; current build/health was checked and retained. Final documented reset exited0, rebuilt/restarted exact source, retained key bytes/mode and every stack volume, and removed only synthetic local data before F02 clock checks. Fixture evidence: `qa/reports/CR010-final-fixture-refresh.json`. Repaired test-only stale streamed redirect observations and an obsolete removed staff/profile row; no retries, thresholds or authority assertions weakened. Manual harness404preparation and duplicate hidden streamed DOM observations were corrected without product changes.

Ranked suggestions remain proposals: F02role capability preview and authenticator timing copy (should, CR006); F01operator diagnostics (should, CR004), oversized exceptional status heading (idea/backlog). No new suggestion was adopted as a requirement here. F01real hosting/TLS case remains deferred to explicit staging authorization; F02has no deferred case. Local repair QA passes; owner PR merge and release/deployment remain separate.

## Historical review before CR-010

The following review describes the superseded customer-web interpretation and original evidence. It is retained for provenance and does not describe current delivered web scope.

Reviewed again 2026-10-14 as a customer using a 360-pixel phone, an operator, and a client developer.
Compared with public Fresha and Booksy documentation; no accounts, real data or remote deployment.

## Verdict

The repaired foundation follows the supplied warm design grammar, gives each salon a distinct address and readable identity, and safely separates unknown, suspended and unavailable states. The current full F01 pass is green; existing account navigation belongs to the already implemented F02. Public TLS and real-host operation remain uncertified and explicitly deferred.

## Current CR-007 independent verification

Full F01 `qa/reports/20261014-232942-F01-2f5e0e96dfa2` passed 32/32 registered automation groups (all exit 0) in 1593.839 seconds on `e4e254f4463a274fdfd055df67b3056e83a249f0`. The frozen inventory has 153 declared cases: 149 automated, three performed manual cases, and one explicitly deferred real-host/TLS case. No cases or automation were added during this continuity pass. Build/API/security, tenant isolation, data integrity, resilience/concurrency and bounded local load protect startup, routing, shared services and delivery; responsive/branding/accessibility checks protect the repaired visual contract. Payment, mobile and end-user permissions remain outside F01's scope; F02 independently verifies identity and roles next.

Source remained clean and unchanged throughout. All four declared configuration files retained exact content and modes before/after; local and stack metadata reported the tested SHA, healthy endpoints and real clock. Recorded developer gates passed 10/10 in `qa/reports/20261014-232225-F01-commands-482422587e59`. Root prepared fresh isolated `salon-stack-cr007-cert2-20261014-175127`; retained historical volumes and private keys were preserved. Before rollout the stack held successful real V1–V10 migrations; only the transparent QA V11 resource was added to the next image, and rollback retained its marker.

Current 50-VU deploy/rollback/instance-loss traffic completed 40,554 / 42,738 / 8,589 requests respectively, each with zero HTTP failures and zero foreign-salon responses. The local read baseline passed at 13.48 ms p95 across 79,966 requests over three synthetic brand rows; this does not certify production capacity.

BUG-F01-002 migration-only startup, BUG-F01-003 success contrast and BUG-F01-004 early401 request logging are independently verified by their original cases and this full pass. All are major severity; no blocker/major bug remains unresolved in F01. Historical BUG-F01-001 rollback protection passed unchanged again. No new product bug was found.

The earlier `qa/reports/20261014-224010-F01-9712edabe0d3` collection executed32 groups with31 passing and one environment failure: first-use Java vulnerability database download exceeded the existing scanner budget before findings were produced. Root prepared the official Trivy 0.75.0 vulnerability and Java databases using the supported download operation, exit 0, and retained public metadata/hash proof in the context. Current real images scan successfully, and the vulnerable Alpine control is rejected with named CVEs. No scanner threshold, timeout, test retry or assertion was changed; prior failed evidence remains preserved.

Visual mapping: Customer header/circular identity/anchored icon tabs/430px canvas and reference cards/pill actions; neutral foundation notices extend the same Poppins/warm-card grammar. Current `design/` evidence covers Alpha/Beta/Gamma, foundation loaded and safe error states across 360/390/768/1024/1280/1440. Independent continuity review inspected current Alpha 360, Beta 1440, suspended 360 and status 1280 captures. The standalone image serves all three Poppins assets with HTTP 200 font/ttf, loads 400/500/600, renders 390px phone and 430px desktop canvas. Required manual loading/keyboard judgment in `qa/reports/20261014-CR007-manual-fixed/manual-judgment.json` is preserved and still applies to unchanged presentation source. The supplied reference could not be previewed after the recorded browser refusal: comparison uses its final CSS/source-defined contract and rendered product evidence, with no claimed side-by-side preview.

Review ranking stays unchanged: SUG-F01-01 operator diagnostics is the should proposal already linked to CR-004; SUG-F01-02 oversized 320px/200% heading grouping remains an idea/backlog item outside narrowed status widths. No extra exploration or requirements were introduced. Documentation correction for the lead: S05 cases footer still describes the old V8 fixture and S04 case preconditions mention an exported local CA; current approved HTTP scope uses no CA and dynamic next migration V11. The original invariants and frozen tests already prove the current behavior. These prose corrections were not applied to certified cases after verification.

## What works well

- The compact Beta header deliberately uses the reference ellipsis while retaining its full accessible name and tooltip; content and controls fit, and dark/light salon colours retain readable foregrounds.
- Unknown addresses show a neutral response; a suspended salon reveals no branding. The status page offers one clear retry action with visible keyboard focus.
- Local operator diagnostics provide stable error codes, trace ids, tenant-tagged delivery/cleanup and least-privilege data access. Interrupted committed work recovers with the same id.

## Suggestions

Ranked, most valuable first. These are proposals, not requirements or permission to implement.

| Id | Class | Suggestion | Disposition |
| --- | --- | --- | --- |
| SUG-F01-01 | should | Give operators an authenticated view of dead messages and backlog when the console is built | CR-004 |
| SUG-F01-02 | idea | Keep oversized status headings within their notice background at narrow widths | backlog |

### SUG-F01-01 Operator view of failed background work

- **Observed:** A failed probe becomes dead and its local meter increases; finding the affected message currently requires API/log inspection. The production-like profile appropriately hides local hooks and metrics endpoints.
- **Why it matters:** The operator needs to distinguish a temporary provider outage from work requiring intervention without reading raw records or receiving a customer's data unnecessarily.
- **Instead:** In the later authenticated operator console, show tenant-scoped counts, status and trace references with deliberate support access; connect provider-dependent alerts after hosting is selected.
- **Evidence:** TC-F01-S04-007 and TC-F01-S04-016, current API triage report. No new operational UI is required by F01.

### SUG-F01-02 Large text in the status notice

- **Observed:** At 320 pixels with root text enlarged to 200%, the unavailable heading extends beyond its inner coloured notice. The overall page does not scroll horizontally, and the required 360/1280 layouts are readable.
- **Why it matters:** A reader using large text should retain the visual grouping of an error and its explanation.
- **Instead:** Permit long heading words to wrap inside the notice when available inline space is small.
- **Evidence:** qa/reports/triage-sol-20261013/status-unavailable-320-200percent.png; this extra exploration is outside the stage's expressly narrowed width checks.

## Compared with other products

| Aspect | Ours | Others | Fit for our users |
| --- | --- | --- | --- |
| Salon entry point | Dedicated salon hostname and brand, with no other salon shown | Fresha supports direct booking links; Booksy supplies a public business profile/link | Our identity boundary matches the white-label intent. Booking capability is scheduled in later features and is not compared as delivered here. |
| Foundation internals | Tenant isolation, delivery leases and local diagnostics | These internals are not presented in the public customer documentation | No meaningful customer comparison for these internals. |

Sources checked 2026-10-13: [Fresha client booking paths](https://www.fresha.com/help-center/knowledge-base/online-profile/101646-learn-how-clients-book-appointments-online), [Booksy business profiles](https://biz.booksy.com/en-gb/features/booksy-profile).

## Bugs filed from this review

BUG-F01-001 found an intermittent rollback outage and is now verified after the developer fix.
Existing six stage bugs are verified and their cases remain in the full feature pass.

## QA repairs and evidence limits

The first 32-entry run at qa/reports/20261013-121417-F01 failed and remains preserved. QA corrected local-metrics/registry expectations explicitly superseded by S04; used decoded browser text for ampersands; corrected SQL dollar delimiters; advanced retention from a future clock so the scheduled occurrence can be reached; scoped delivery logs separately from legitimate foreign-host 404 request logs; declared the ECS helper type; bounded Docker-log capture and used a unique trace; held the database lock through thaw and measured the actual pause event; and moved traffic to Linux k6 with only the stack CA trusted. No assertion threshold, request deadline or retry policy was relaxed.

The 36-test API/web triage and corrected SQL/freeze checks passed. Eighteen critical callbacks failed against deliberately wrong fixtures for the intended assertions, with original source hashes retained. A native k6 TLS fixture failed before product traffic; Linux CA-only preflight made 222 successful requests/checks with zero failures or tenant mismatches. Its two-second preflight does not claim the full deployment scenario or its minimum count.

The earlier local read baseline had p95 616.53 ms and 893 reads per salon. Fresh controlled measurements with unchanged thresholds gave p95 17.52 ms with the stack stopped (58,997 reads), and 12.83 ms with it running (81,564 reads), both 30 VUs/20 seconds and zero errors or wrong tenants. The earlier slowdown was not reproduced; its cause is unproven. These are local measurements over three brand rows, not target-host or scale certification.

Persistent terminals are required in this environment: an exited test terminal kills runtime descendants it restarted. QA restored affected services and holds the final runner's terminal open. Manual ports and visual checks were refreshed on 2026-10-13. CI proof is the independently inspected green/failing-lint/green evidence archived under the retained main Git directory; the proof PR is closed unmerged. Real hosting/domain/public TLS is the sole deferred feature case, under decision 018.

## Approved local HTTP scope

CR-005 replaces local certificate/HTTPS/redirect/HSTS checks with HTTP hostname and first-request routing checks. Public TLS remains in deferred TC-F01-002 for an explicitly requested staging deployment. The earlier TLS run and interrupted report20261013-132232-F01 are historical evidence only; they do not certify the revised scope. No certificate or trust setup is used by the new pass.


## HTTP-only full pass and rollback triage

The recorded run `qa/reports/20261013-142212-F01` passed 31 of 32 automation entries. The only
failure was TC-F01-S05-008: rollback returned 36 genuine page 503s while web-2 was being
replaced and the remaining web-1 readiness probe timed out. These errors preceded QA traffic
shutdown by about nine seconds. BUG-F01-001 records the exact interval and filtered edge logs.
No request threshold was relaxed and no full suite was rerun merely hoping for a pass.

## Historical final verification (2026-10-13)

Full local HTTP-only F01 run `qa/reports/20261013-152705-F01` passed all 32 registered entries
on committed revision `771c56b89f192deec47ab3d4f68f04a7dbcbf6ce`. BUG-F01-001 also passed the
independent focused deployment/rollback test before this run. Full-run rollout/rollback/loss
traffic had 38,202 / 44,241 / 7,154 requests respectively, zero HTTP failures and zero wrong-tenant
responses. The unchanged read-load threshold passed with 14.91 ms p95 and 70,151 salon reads.

Docker Hub registry DNS failed during image preparation. A report-only wrapper used temporary
Dockerfile copies omitting only the external frontend syntax directive, preserving source and
Dockerignore bytes, to build current images with cached bases and Docker's built-in frontend.
The original image assertions passed. This compiler deviation and hashes are preserved in
`qa/reports/rollback-fix-20261013/builtin-build-provenance.json`; old images were not relabelled.
The independent QA runner completed after its agent hit workspace credits. The lead, who did not
build application code, inspected the final summary, key logs and unchanged traffic thresholds
and recorded this result under the QA role. No staging deployment or local TLS testing occurred.

## Owner-directed proportional verification after PR5 fixes (2026-10-15)

The owner explicitly directed: do not repeat QA for unchanged files; reuse valid prior results. The actual product diff from the previous complete F02 source `e73d99b0bbb38ec4db6b9cc837ea635286a1fed0` to fixed runtime source `0b3f1d91315705727d76087b19bb57120a086629` contains only `AccountScreen.tsx`, `Confirmation.tsx` and new developer `AccountRecovery.test.tsx`. The changed production components are imported through AccountPage and do not enter F01's status, plain error or public salon foundation routes. Tenant/backend/stack code and F01 automation did not change.

The prior complete F01 report `qa/reports/20261014-232942-F01-2f5e0e96dfa2` remains 32/32 on `e4e254f4463a274fdfd055df67b3056e83a249f0`; complete F02 `qa/reports/20261015-000506-F02-3e38935bf776` remains 13/13 on `e73d99b0bbb38ec4db6b9cc837ea635286a1fed0`. Their local integrity receipts and every entry's log digest were independently checked intact. Their original source/fixture contexts remain recorded; resets are not represented as the identical database generation. Old full `qa_revision`/`evidence` fields were not rewritten to claim a new complete pass.

New independent selected browser evidence on actual fixed 0b3f1d: both original regressions passed 2/2 in 7.7 seconds. The three recoverable confirmation variations (customer delete 503, staff revoke-all 503, staff-remove 409) now expose accessible failure feedback and preserve refused state. Actual Owner self-demotion with another Owner retained now removes Staff access/Activity log links while principal and direct 403 permission controls hold. BUG-F02-006/007 are independently verified. The developer's 16 related component checks cover permission updates from initial load/mutation/broadcast, unchanged-permission stability and confirmation focus/recovery; the complete recorded 10/10 command collection `qa/reports/20261015-010423-F01-commands-55f23c47f02f` passed on product-equivalent d94b166. Developer checks remain separately labelled.

The repeated F01 collection `qa/reports/20261015-011303-F01-3bcb75c887d4` completed 11/32 entries before interruption; its runner was already gone when the owner directed no repeated unchanged scope. The original stale running summary is preserved without editing or treating it as a pass. Separate interruption note: `.local/cr007-review-repeat-interruption.json`. Scoped source, report/log digests, old identities, exact coverage and limits: `.local/cr007-owner-scoped-qa-assessment.json`. Fixed browser execution log: `qa/reports/20261015-PR5-fixed-reproduction/reproduction.log`.

Limits are explicit: no new complete feature result on 0b3f1d, whole accessibility/width matrix, full account suite, native manual traversal or independent broadcast multi-tab browser variation is claimed after these fixes. Prior related results and the developer's focused checks are retained under the owner's proportional-scope exception. No regression/smoke/remote scope was added, and no merged-full report was synthesized. Lead closure must preserve this distinction in its scoped evidence policy.
