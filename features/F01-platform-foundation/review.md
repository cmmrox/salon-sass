# F01 Platform foundation and tenant isolation: product review

Reviewed 2026-10-13 as a customer using a 360-pixel phone, an operator, and a client developer.
Compared with public Fresha and Booksy documentation; no accounts, real data or remote deployment.

## Verdict

The foundation gives each salon a distinct address and readable identity, and safely separates unknown, suspended and unavailable states. It is ready to support later customer features once its full automated pass is green; the empty salon body is the expressly planned shell, not a booking site yet. Public TLS and real-host operation remain uncertified and explicitly deferred.

## What works well

- The long beta name wraps at 360 pixels, and dark/light salon colours retain readable foregrounds.
- Unknown addresses show a neutral response; a suspended salon reveals no branding. The status page offers one clear retry action with visible keyboard focus.
- Local operator diagnostics provide stable error codes, trace ids, tenant-tagged delivery/cleanup and least-privilege data access. Interrupted committed work recovers with the same id.

## Suggestions

Ranked, most valuable first. These are proposals, not requirements or permission to implement.

| Id | Class | Suggestion | Disposition |
| --- | --- | --- | --- |
| SUG-F01-01 | should | Give operators an authenticated view of dead messages and backlog when the console is built | |
| SUG-F01-02 | idea | Keep oversized status headings within their notice background at narrow widths | |

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

BUG-F01-001 (major): rollback briefly removes every web upstream, returning 36 page requests with HTTP 503. Existing six stage bugs are verified and their cases remain in the full feature pass.

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
