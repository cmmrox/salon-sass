# F01 Platform foundation and tenant isolation: product review

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
