# F01-S02 Tenant registry and data isolation: product review

Reviewed as: an API client developer and a local operator; salon owners and customers are affected by the isolation guarantee but have no new screen in this stage.
Compared with: Fresha and Booksy public business-page documentation, read on 2026-10-12. Their public pages do not expose their database isolation design, so no claim is made about relative backend security.

## Verdict

The foundation is ready for the lead's stage-closure gates. All 45 automated acceptance and boundary cases passed locally; no product bug was found. Hostnames select exactly one salon, requests with no salon fail closed, and database outages produce bounded errors and recover without restarting the API. This is not a complete customer-facing salon site; that surface starts in F01-S03.

## What works well

- The public response is small and clear: exactly slug, published name and colour, with no tenant UUID or framework details. Alpha, beta's long name and gamma all decode correctly through the generated client.
- Unknown/reserved/lookalike hosts receive the same not_found answer. Client-supplied tenant parameters and forwarded headers cannot redirect an alpha request to beta.
- The application role is independently restricted. Read-only grants and row-level security both prevent writes; rollback-only probes with write grants prove the policy itself is effective.
- Reused HTTP and database connections do not retain another salon's context. A 140-user rush and sustained mixed traffic produced zero cross-salon responses.
- Temporary database failures recover without manual repair. In the recorded pass, 24 requests in flight at the freeze returned 503 in 2.022–3.052 seconds, 494 frozen arrivals took at most 2.031 seconds, and recovery took 590 ms and 728 ms after the two resume invocations.
- Loopback exposure is real for IPv4 and IPv6. A separate control listener made the network refusal meaningful despite this machine's VPN route; opt-in exposes both services and switching it off restores both loopbacks.

## Suggestions

Ranked, most valuable first. Neither is a requirement or approval to build it.

| Id | Class | Suggestion | Disposition |
| --- | --- | --- | --- |
| SUG-F01-S02-01 | idea | Give clients a documented retry policy for temporary 503 responses before customer pages depend on this read. | |
| SUG-F01-S02-02 | idea | Consider tenant-aware conditional brand reads when the public branding payload grows. | |

### SUG-F01-S02-01 Document retry guidance for temporary outages

- **Observed:** The endpoint returns stable service_unavailable errors while the database is frozen/stopped. The committed OpenAPI contract describes 503, but neither its response headers nor the general API guidance specify a retry delay/backoff policy.
- **Why it matters:** A future salon page on patchy mobile data should avoid repeated full-page retries or flooding a dependency that is recovering. Clients currently have to choose this independently.
- **Instead:** Agree a client retry/backoff policy and, if useful, a conservative Retry-After header. Keep unknown-salon 404 responses non-retryable. This should be decided with the public shell's error behavior rather than inferred by a client.
- **Evidence:** The 503 schema in docs/api/openapi.json; recorded freeze/recovery stream in qa/reports/20261012-170409-F01-S02/database-silent-stream.jsonl. This is an improvement proposal, not a missing AC-10 behavior.

### SUG-F01-S02-02 Consider conditional public-brand reads

- **Observed:** A healthy GET returns the complete brand body each time and no ETag or Last-Modified header. Local sustained reads were fast (83,993 tenant reads, p95 14.76 ms), but all three current brand rows are tiny.
- **Why it matters:** Repeat visits over mobile data could avoid an unchanged response as logo/cover/profile settings grow in later stages. The present payload alone does not justify delaying this stage.
- **Instead:** When expanding branding, evaluate tenant-aware validators and an explicit cache policy; ensure hostname/tenant identity is part of every cache and validator key and verify draft/published changes independently.
- **Evidence:** Direct alpha/beta/gamma API inspection and the local read-load report. No staging-capacity claim is made.

## Compared with other products

| Aspect of this flow | Ours | Others | Better for our users |
| --- | --- | --- | --- |
| A link identifies a salon/business | One exact salon hostname; direct API read provides only its public brand. Branded browser shell follows in F01-S03. | [Fresha](https://www.fresha.com/help-center/knowledge-base/online-presence/102398-learn-how-clients-book-appointments-online) offers direct booking links and business profiles. [Booksy](https://biz.booksy.com/en-gb/features/booksy-profile) presents a business page with services, prices and availability. | Competitors currently provide a complete user experience; our hostname mechanism is an appropriate foundation, not a feature-parity claim. |
| Showing business identity | Name and colour available independently for all three salons; no additional fields yet. | Booksy documents logo/cover/description customization on its public profile. | Competitors today; richer branding is already assigned to F04, not a new requirement for this stage. |
| Tenant data isolation | Independent API, role, RLS, catalogue and concurrency evidence retained locally. | Public business-page articles do not establish backend isolation guarantees. | No comparison possible from public evidence. |

## Documents for the lead to reconcile before closure

- The handoff's two isolation-layer integration-test names and all deliberate framework choices must remain documented for later tenant tables. QA verified the outside behavior rather than rerunning developer integration gates.
- docs/architecture/multi-tenancy.md, docs/engineering/backend.md and docs/operations/environments-and-releases.md already contain the NULLIF policy, lazy registry resolution, nil UUID, request timeout, two-role setup and loopback settings in this checkout. These handoff notes are now reconciliation checks rather than reasons to rewrite those documents again.
- docs/architecture/repository-structure.md already lists the PostgreSQL role SQL under infra/, but does not explicitly list apps/web/scripts/. docs/engineering/web.md and docs/architecture/tech-stack.md do not explain local custom-server startup and the production standalone caveat reported in the handoff. The lead should record the facts in the appropriate documents before closing, and preserve F01-S05's production-serving decision.
- CR-002 and CR-003 must be marked implemented by the lead as required by the spec's done criteria; QA does not edit change requests.

## Environment and verification evidence

- Stage-only run: qa/reports/20261012-170409-F01-S02, PASS, nine automation entries, 45 cases. Machine: Darwin arm64, Mac16,12, 10 logical CPUs, 16 GiB memory; API/web/PostgreSQL/k6 share it, with only three tenant/brand rows (machine.txt records versions). No regression, smoke, other stage or other feature was run.
- First attempted run, qa/reports/20261012-170219-F01-S02, could not exercise the running API/web: the command execution tool reaped the detached child processes after local-up's shell exited. The tooling prerequisites and PostgreSQL were present. A persistent controlling shell repaired process lifetime; all affected automation was rerun in the recorded pass. This was an environment fault, not a product defect.
- Negative oracle copies under qa/reports/F01-S02-oracle-probes deliberately use wrong expectations to show every new case can go red. All 45 cases rejected their wrong expected results (27 shell, 15 API, one browser, two k6); summary.json lists every case. The original assertions and application code are unchanged. Database catalogue/FORCE and contract drift cases additionally include deliberate broken rollback objects or temporary contract copies as controls.
- No manual or deferred acceptance cases. Manual API/operator exploration and public competitor-document review supplement the automated pass. No real customer data, messages, payments or remote deployment was involved.

## Bugs filed from this review

None. No unresolved minor or trivial defects were left open.

## Stage QA checklist

- [x] Read stage spec, handoff, requirements, Read first documents and docs/qa/.
- [x] Start and preflight the product; repair execution-environment process lifetime.
- [x] Choose and justify test types.
- [x] Write and register all 45 cases.
- [x] Register nine automation entries.
- [x] Observe each new case fail against an intentionally wrong expectation.
- [x] Run exactly F01-S02 through qa/scripts/sdlc run.
- [x] Triage failures; no product defect found.
- [x] Explore API/operator behavior and compare public competitor evidence.
- [x] Record final QA fields; generate and validate.
- [x] Run QA ownership guard and commit.
