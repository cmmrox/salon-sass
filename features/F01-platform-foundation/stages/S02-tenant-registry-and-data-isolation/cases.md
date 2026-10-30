# F01-S02 Tenant registry and data isolation: test cases

## Test approach

| Test type | Why it applies to this stage |
| --- | --- |
| api | Public brand endpoint, hostname handling, error and generated client contract; browser connectivity is AC-11. |
| tenant-isolation | No client input, request reuse or SQL query may expose or change another salon. |
| concurrency | AC-4 simultaneous traffic exercises pooled connections and virtual-thread request scope. |
| security | Host ambiguity, role escalation, narrow registry access and least network exposure. |
| data-integrity | Registry constraints, tenant table catalogue, migration and seed integrity. |
| resilience | Frozen/stopped PostgreSQL must fail within five seconds and recover without restart. |
| build | Contract drift gate and generated client types must reject broken copies. |
| load | Local read latency baseline under NFR-03; correctness remains mandatory under sustained mixed traffic. |

Not applied: functional, responsive, accessibility and browser branding: no UI changes, as spec explicitly states. Permissions: endpoint is public and F02 sign-in does not exist. Payment-integrity/mobile: no money or mobile app. Local load is not certification of NFR-03 or NFR-04 on staging.

Environment: local API/web and salon-postgres only. Three synthetic salons from docs/qa/test-plan.md; no accounts. Database checks use app/owning roles from handoff. Tests creating data, granting writes or probing ownership roll back; reset explicitly removes only local synthetic data. Non-local profiles are tested on this same local container in a temporary database.

## Expectations at the full F01 pass

The approved S03/S04 specifications supersede the skeleton's original response shape: `timeZone` is a public salon field (S04 AC-4), `traceId` identifies each error request (S04 AC-1), and equivalent safe errors exclude that trace when compared. Invalid bare network hosts return the neutral 404 page (S03 AC-3, decision 028). S04 adds audit/outbox/job migrations; migration tests prove the baseline once and every committed migration once, with history unchanged on restart. Recovery timing uses successful PostgreSQL resume return, with invocation and confirmation marks retained, as the QA timing rule requires.

## Cases

Every case is automated; no manual or deferred acceptance cases. Human API/operator review is recorded separately in review.md.

### TC-F01-S02-001 each salon's hostname returns that salon's slug, name and brand colour, and no other field

- **Type:** api
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Request each salon through the shared API client; check schema, exact three-field body and cookies.
- **Expected:** 200 with its own slug, name and colour, no id or extra field.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-002 a salon's brand is served on its own hostname and on no other salon's

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-1
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Read each owner’s brand, then read both other hostnames and compare all six pairs.
- **Expected:** Each owner exists and no other hostname discloses its brand or id.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-003 the hostname is matched without regard to case, and the port in it is ignored

- **Type:** api
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Send upper/mixed-case hosts, omitted/different/empty ports, mixed header name and padded host values.
- **Expected:** Every well-formed variant identifies the same salon.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-004 a hostname that resolves to no salon answers 404 not_found, with the same body whichever hostname it was

- **Type:** security
- **Priority:** high
- **Covers:** AC-2
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Read alpha first, then unknown, reserved, nested, lookalike, long-label, trailing-dot and IP hosts with and without ports.
- **Expected:** 404 not_found problem details with identical body; no salon data or id.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-005 the same 404 is given on another path of the salon's API, apart from instance

- **Type:** api
- **Priority:** high
- **Covers:** AC-2
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Compare unknown salon on /api/v1/salon with absent path on alpha.
- **Expected:** 404 bodies agree apart from instance; instance names the requested path.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-006 meta and health answer 200 on every hostname that resolves to no salon

- **Type:** api
- **Priority:** high
- **Covers:** AC-2
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Request meta and health on the six AC-2 hostnames and all seeded salon hostnames.
- **Expected:** 200 from each; health UP, metadata identifies service only.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-007 a salon named in a query parameter is never trusted: alpha's hostname returns alpha's brand

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-3
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Confirm beta exists; request alpha with beta’s slug/id/hostname in each query variant and all variants together; reverse the salons.
- **Expected:** Only the Host salon is returned. A `;` path parameter makes the path non-canonical: since F03-S01 it is refused 400 `bad_request` before routing, showing nothing of the other salon.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-008 a salon named in a request header other than Host is never trusted: alpha's hostname returns alpha's brand

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-3
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Confirm beta exists; send forwarded, tenant, origin, cookie and authorization headers individually and together, including beta query parameters.
- **Expected:** Noncredential tenant-selector headers return only Alpha under Alpha's Host and cannot make the bare domain resolve to a salon. The invalid Authorization Bearer UUID row is explicitly refused with 401 `invalid_session` and the same generic nonleaking problem, per approved F02 credential validation; it never selects Beta. Combined valid selectors still return Alpha, and combined invalid credentials are refused.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-009 a request that names two hosts, or a host no hostname is written like, is refused and given no salon's data

- **Type:** security
- **Priority:** high
- **Covers:** AC-2, AC-3
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Send raw duplicate/conflicting/malformed Host headers and absolute request targets naming different salons, Unicode, injection and invalid ports.
- **Expected:** 400 or 404 problem details, no salon data or internals; service remains healthy.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-010 Sequential requests on one connection cannot inherit another salon

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-1, AC-2, AC-3
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. On one socket send the 21-step salon/platform/failure conversation sequentially.
- **Expected:** All 21 answers match each request’s hostname; nine correct brand responses and no inherited context.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-011 Pipelined requests on one connection cannot inherit another salon

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-1, AC-2, AC-3, AC-4
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Pipeline the same 21-step conversation on one socket before receiving responses.
- **Expected:** All 21 ordered answers correspond to their own request’s hostname; no inherited context.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-012 nothing but a read is accepted on the salon endpoint, and an attempt to write changes nothing

- **Type:** security
- **Priority:** high
- **Covers:** AC-1, AC-3
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Read alpha/beta; attempt POST/PUT/PATCH/DELETE brand takeover, then OPTIONS and HEAD; read all brands again.
- **Expected:** Writes return 405 method_not_allowed and change nothing; Allow GET,HEAD,OPTIONS; HEAD has no body.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-013 no answer of the salon endpoint shows how the product is built, sets a cookie or allows another origin

- **Type:** security
- **Priority:** high
- **Covers:** AC-1, AC-2, AC-3
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Inspect 200,400,404,405,406 replies and origin/preflight requests, bodies and headers.
- **Expected:** No internals, UUID, cookie, cross-origin credential grant or framework-identifying header.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-014 the contract describes GET /api/v1/salon with its 200 and 404, and the running API agrees with it

- **Type:** api
- **Priority:** high
- **Covers:** AC-12
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Check committed path/schema/required fields; validate live brand/error replies and compare local served OpenAPI.
- **Expected:** GET has 200 Salon and 404 Problem; exactly three required public fields; served contract matches.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-015 the generated client exposes the endpoint: a call through it returns each salon's brand and a typed 404

- **Type:** api
- **Priority:** high
- **Covers:** AC-12
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Call the generated client on each hostname and an unknown slug.
- **Expected:** Typed own-brand data on 200 and typed not_found error on 404.
- **Automation:** `qa/api/F01/S02-salon.spec.ts`

### TC-F01-S02-020 A simultaneous mixed-salon rush has zero mismatches or errors

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-4
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Release 140 users together: 300 requests by hostname, 300 alternating Host on kept-alive connections, 100 bare-domain requests; read brands afterwards.
- **Expected:** 100 own-brand replies per salon per tenant scenario; zero mismatches/errors; bare domain always 404.
- **Automation:** `qa/load/F01/S02-salon-rush.js`

### TC-F01-S02-021 Steady mixed-salon reads remain isolated and meet the local latency baseline

- **Type:** load
- **Priority:** high
- **Covers:** AC-4
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Run 30 virtual users for 20 seconds, cycling across the three seeded brands, with no ramp.
- **Expected:** Zero failures or leaks; >=1000 correct replies per salon; local baseline p95 below 300 ms (NFR-03), not staging capacity certification.
- **Automation:** `qa/load/F01/S02-salon-read-load.js`

### TC-F01-S02-030 The application's role is not a superuser, cannot bypass row-level security, belongs to no other role and owns nothing

- **Type:** security
- **Priority:** high
- **Covers:** AC-5
- **Performed as:** local database app/owning roles
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Query role attributes, memberships, ownership and schema/database privileges as owner and app.
- **Expected:** App has no superuser, BYPASSRLS, role memberships, object ownership or privilege to create permanent schema objects.
- **Automation:** `qa/checks/F01/S02-database-isolation.sh`

### TC-F01-S02-031 The application's role is refused any read of the tenant table, by row, by column or by count

- **Type:** security
- **Priority:** high
- **Covers:** AC-5
- **Performed as:** local database app/owning roles
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Attempt tenant reads by all/selected columns, count, COPY, joins and narrow lookup.
- **Expected:** Tenant table reads are permission denied; app cannot read registry table directly.
- **Automation:** `qa/checks/F01/S02-database-isolation.sh`

### TC-F01-S02-032 The application's role cannot give itself more: every attempt to get round the policy is refused and leaves the catalogue as it was

- **Type:** security
- **Priority:** high
- **Covers:** AC-5
- **Performed as:** local database app/owning roles
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Attempt role elevation, ownership, policy disabling, row_security off and unauthorized grants within rollback transactions.
- **Expected:** Escalation refused; RLS cannot be bypassed; rows/catalogue unchanged.
- **Automation:** `qa/checks/F01/S02-database-isolation.sh`

### TC-F01-S02-033 The registry is reached only through tenant_by_slug, which gives one slug's id, status, time zone and access state, to the application's role alone, and cannot be redirected

- **Type:** security
- **Priority:** high
- **Covers:** AC-5
- **Performed as:** local database app/owning roles
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Call tenant_by_slug for each salon and a lookalike/injection; inspect definer/search_path/execute grants; call from unprivileged role in rollback.
- **Expected:** Only exact slug lookup returns id/status (and, since F03-S02, time zone and access state); function fixed search_path and restricted execution; no broad tenant read.
- **Automation:** `qa/checks/F01/S02-database-isolation.sh`

### TC-F01-S02-034 With no tenant set, the application's role sees no row of salon_brand, although three exist

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** local database app/owning roles
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Owner confirms three brands; app reads whole table, each id and COPY with no tenant, inside/outside transaction.
- **Expected:** No rows despite seeded brands existing.
- **Automation:** `qa/checks/F01/S02-database-isolation.sh`

### TC-F01-S02-035 Set to alpha, the application's role sees alpha's row and no other, however the question is put

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** local database app/owning roles
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Confirm beta exists; set alpha and query beta by id/filter/join/subquery/temporary view/prepared statement/COPY and diagnostic function; read each owner’s row.
- **Expected:** Only the set tenant’s row visible, no beta/gamma values reach outputs or diagnostics.
- **Automation:** `qa/checks/F01/S02-database-isolation.sh`

### TC-F01-S02-036 On the running product, set to alpha, an UPDATE or DELETE aimed at beta's row and an INSERT with beta's tenant_id change nothing

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** local database app/owning roles
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. As app set alpha, attempt beta UPDATE/DELETE/INSERT and tenant-key move; compare beta row and row count afterwards.
- **Expected:** 0 affected or permission denied; INSERT refused; beta and total unchanged.
- **Automation:** `qa/checks/F01/S02-database-isolation.sh`

### TC-F01-S02-037 The policy itself: with the writes granted, alpha changes its own row and nothing of beta's, and with no tenant nothing can be written

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** local database app/owning roles
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. In owner rollback transactions temporarily grant writes and assume app role; prove own UPDATE works, other UPDATE/DELETE affect zero, cross-tenant INSERT/move refused, no-context writes blocked.
- **Expected:** RLS itself enforces isolation independently of read-only grants; no changes persist.
- **Automation:** `qa/checks/F01/S02-database-isolation.sh`

### TC-F01-S02-038 A tenant set in one transaction leaves the next transaction on the same connection with none

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** local database app/owning roles
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. On one psql connection set tenant in commit/rollback/failed/SET LOCAL transactions, then read outside and inside next transaction; compare backend PID.
- **Expected:** Setting ends with transaction, no later rows or UUID-cast errors, same backend PID throughout.
- **Automation:** `qa/checks/F01/S02-database-isolation.sh`

### TC-F01-S02-039 A tenant that is nil, empty or not a tenant id at all shows no row, and never another salon's

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** local database app/owning roles
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Read with nil, empty, unknown and malformed tenant settings; test uppercase UUID; try nil tenant row in rollback.
- **Expected:** No wrong-salon data; invalid UUID refused; uppercase resolves only alpha; nil registry id refused.
- **Automation:** `qa/checks/F01/S02-database-isolation.sh`

### TC-F01-S02-040 Row-level security is forced: an owner of the table that is not a superuser is held to the policy too

- **Type:** security
- **Priority:** high
- **Covers:** AC-7
- **Performed as:** local database app/owning roles
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. In rollback give table a nonsuperuser owner; read/no-context/update as owner, then remove FORCE for negative control.
- **Expected:** FORCE binds ordinary owner; no-context zero and alpha only; removing FORCE exposes all three in control only.
- **Automation:** `qa/checks/F01/S02-database-isolation.sh`

### TC-F01-S02-041 In the catalogue, every table with a tenant_id is protected in full: enabled and forced, one fail-closed tenant_isolation policy, tenant_id NOT NULL with a foreign key to tenant, and in every unique index

- **Type:** data-integrity
- **Priority:** high
- **Covers:** AC-7
- **Performed as:** local database app/owning roles
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Enumerate every tenant_id table and assert RLS/FORCE/policy/NOT NULL/FK/unique-index coverage; create deliberately flawed rollback probe tables.
- **Expected:** At least salon_brand checked, no product faults; each deliberately introduced catalogue fault detected; probes removed.
- **Automation:** `qa/checks/F01/S02-database-isolation.sh`

### TC-F01-S02-042 The registry refuses a slug that is reserved, too short, too long, or anything but lowercase letters, digits and inner hyphens, and a slug already taken

- **Type:** data-integrity
- **Priority:** high
- **Covers:** AC-8
- **Performed as:** local database app/owning roles
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. In rollback try all 17 reserved slugs, duplicate id/slug, short/long/Unicode/uppercase/punctuation/edge hyphens and valid 3/30-length/digit/inner-hyphen slugs.
- **Expected:** Invalid/duplicate refused by database, valid accepted; exactly three tenants afterwards.
- **Automation:** `qa/checks/F01/S02-database-isolation.sh`

### TC-F01-S02-043 The tenant and brand tables hold their own rules: a known status, a real tenant, one brand per salon, a name, and a colour written #RRGGBB

- **Type:** data-integrity
- **Priority:** high
- **Covers:** AC-7, AC-8
- **Performed as:** local database app/owning roles
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. In rollback try invalid/null status, absent/null tenant FK, duplicate brand, blank name, invalid/null colour and valid boundary colours.
- **Expected:** Integrity constraints refuse invalid records and accept valid values; final rows/catalogue identical.
- **Automation:** `qa/checks/F01/S02-database-isolation.sh`

### TC-F01-S02-050 Requests in flight at the moment PostgreSQL freezes are each answered 503 service_unavailable within 5 seconds

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Warm a 24-caller stream; lock salon_brand in uncommitted owner transaction; pause PostgreSQL while requests hold/wait for database connections.
- **Expected:** Requests started before freeze and unanswered once frozen return 503 service_unavailable within 5 seconds, before thaw; all salons represented.
- **Automation:** `qa/checks/F01/S02-database-silent.sh`

### TC-F01-S02-051 Requests made while PostgreSQL is frozen are each answered 503 within 5 seconds, for as long as it stays frozen, and nothing hangs

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Continue requests during initial freeze and a second freeze beyond 30 seconds; inspect stream and metadata/health.
- **Expected:** At least 300 frozen requests, including late arrivals, return 503 within 5 seconds; no hang; meta 200 and health 503.
- **Automation:** `qa/checks/F01/S02-database-silent.sh`

### TC-F01-S02-052 Within 5 seconds of PostgreSQL being thawed every salon is answered 200 with its own brand again, and the API was never restarted

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Resume after each freeze and read stream timed from resume invocation; compare API PID and all brands.
- **Expected:** First 200 within 5 seconds, requests after recovery return own brand, no API restart or cross-salon data.
- **Automation:** `qa/checks/F01/S02-database-silent.sh`

### TC-F01-S02-053 With PostgreSQL stopped, a salon's request is answered 503 service_unavailable within 5 seconds, and 200 again once it is started

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Stop PostgreSQL; request each salon repeatedly and 40 concurrently, then start database again.
- **Expected:** 503 service_unavailable within 5 seconds for all; metadata stays 200; recovery to own-brand replies without API restart.
- **Automation:** `qa/checks/F01/S02-database-silent.sh`

### TC-F01-S02-060 By default both ports refuse a connection on every non-loopback address of this machine, and listen on 127.0.0.1 and ::1 only

- **Type:** security
- **Priority:** high
- **Covers:** AC-11
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Create all-interface control listener; probe machine addresses and both service ports; inspect lsof bindings.
- **Expected:** At least one control-reachable non-loopback address exists and both product ports refuse it; exactly 127.0.0.1 and ::1 listening.
- **Automation:** `qa/checks/F01/S02-listening.sh`

### TC-F01-S02-061 A salon's hostname works from curl and from Node.js on both ports, whether it is reached over IPv4 or IPv6

- **Type:** api
- **Priority:** high
- **Covers:** AC-11
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Use curl default/-4/-6 and Node http family4/family6/default plus fetch on alpha API/web; read all brands.
- **Expected:** Both products reachable in both families, alpha correct, default resolver order works.
- **Automation:** `qa/checks/F01/S02-listening.sh`

### TC-F01-S02-062 The opt-in is named in .env.example and README.md, is off by default, and any value but true or false stops the scripts without starting anything

- **Type:** security
- **Priority:** high
- **Covers:** AC-11
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Read .env.example/README; invoke starts with invalid boolean values and local-up with maybe; compare bindings and PIDs.
- **Expected:** Opt-in documented false by default; invalid values refused with named setting and no altered service state.
- **Automation:** `qa/checks/F01/S02-listening.sh`

### TC-F01-S02-063 With the opt-in set, both ports are reachable on the network address; without it again, they are not

- **Type:** security
- **Priority:** high
- **Covers:** AC-11
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Restart both with SALON_LISTEN_ON_NETWORK=true; probe reachable non-loopback control addresses and request alpha; restart false and probe again.
- **Expected:** Opt-in opens both ports and serves alpha; bare address identifies no salon; disabling returns to both loopbacks and refuses network ports.
- **Automation:** `qa/checks/F01/S02-listening.sh`

### TC-F01-S02-064 a browser reaches each salon on its hostname, on the API's port and the web app's, over 127.0.0.1 and over ::1

- **Type:** api
- **Priority:** high
- **Covers:** AC-11
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. In Chromium open all salons’ API/web hostnames; launch separate browsers pinning alpha.localhost to IPv4 and IPv6 and inspect serverAddr.
- **Expected:** 200 with correct salon JSON and web content, hostname stays in address bar, exact intended loopback IP reached.
- **Automation:** `qa/web/F01/S02-salon-hostname-in-a-browser.spec.ts`

### TC-F01-S02-070 local-reset.sh followed by local-up.sh leaves exactly the three synthetic salons of the test plan, and nothing that was there before

- **Type:** data-integrity
- **Priority:** high
- **Covers:** AC-9
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Add synthetic fourth salon and rename gamma; confirm changed state; run local-reset then local-up; read database and all hostnames.
- **Expected:** Exactly original three salons/brands/ids/active statuses; fourth gone; least-privileged app role exists on fresh volume.
- **Automation:** `qa/checks/F01/S02-seed.sh`

### TC-F01-S02-071 Running local-up.sh again, and starting the API again, neither duplicates nor changes the salons

- **Type:** data-integrity
- **Priority:** high
- **Covers:** AC-9
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Record all six rows including timestamps; repeat local-up, restart API twice, run local-down/up and reread.
- **Expected:** Rows/timestamps, ids, names, colours and counts stay identical with no duplication.
- **Automation:** `qa/checks/F01/S02-seed.sh`

### TC-F01-S02-072 Started with a profile other than local against an empty database, the API creates the schema and no salon exists

- **Type:** data-integrity
- **Priority:** high
- **Covers:** AC-9
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. On this local container create empty temporary database; run API under staging then production profile, each twice; inspect migrations/schema/grants and HTTP; restore local and drop temp database.
- **Expected:** Schema created and protected, zero salons/brands, hostname reads 404, metadata/health work; local original rows unchanged after restore. No remote environment touched.
- **Automation:** `qa/checks/F01/S02-seed.sh`

### TC-F01-S02-080 pnpm contracts:check passes on the committed tree, and fails, naming the file, when the salon endpoint is missing from the document or from the client

- **Type:** build
- **Priority:** high
- **Covers:** AC-12
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Run contracts:check on committed product; in temporary committed-tree copy remove endpoint from document, then rename generated client path and rerun.
- **Expected:** Committed check green; each stale copy nonzero naming offending file; source tree unchanged.
- **Automation:** `qa/checks/F01/S02-contract.sh`

### TC-F01-S02-081 The generated client exposes GET /api/v1/salon with its three fields: code that uses them compiles, and code that asks for another path or for a tenant id does not

- **Type:** build
- **Priority:** high
- **Covers:** AC-12
- **Performed as:** unauthenticated API client or local operator
- **Preconditions:** Product is up with the three synthetic salons; every isolation check proves the target owner’s data exists first.
- **Steps:**
  1. Compile generated-client positive call and negative wrong-path/tenantId/PUT examples outside checkout, then QA typecheck.
- **Expected:** Positive compiles, each negative fails for its exact intended TS complaint, QA typecheck green.
- **Automation:** `qa/checks/F01/S02-contract.sh`

## Assertion failure controls

All 45 cases were also run from copies against deliberately wrong expectations; every case went red, while original tests and application code stayed unchanged. Evidence: `qa/reports/F01-S02-oracle-probes/summary.json` and its per-tool logs. The catalogue/FORCE and contract-drift cases additionally detect broken rolled-back objects and stale temporary contract copies. The real stage run with correct expectations passed at `qa/reports/20261012-170409-F01-S02`.
