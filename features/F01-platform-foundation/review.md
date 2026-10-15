# F01 Platform foundation and tenant isolation: product review

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
