# F03-S01 Operator access, salon provisioning and plans: test cases

Prepared during the F03 build (lifecycle "QA preparation during a build") from the approved
spec, `docs/architecture/operator-console.md`, PLT-01/PLT-04 and `docs/qa/`, and reconciled with
the final specs, decisions 049-053 and the stage handoffs at feature-verify. Expected results come
from those documents only. The feature's `cases.md` holds the test approach for the
whole feature and the journeys.

Environment: local only. The console is `console.localhost` (API port and web port from
`qa/shared/env.ts`). Operator Admin and Support accounts are created per test through the
loopback `/local/operator` fixture hooks with credentials and TOTP seeds generated in memory.
New salons are provisioned per test with unique slugs (a retired or taken slug is never reused);
alpha, beta and gamma are read and compared, never provisioned over or changed. Salon staff
accounts come from the F02 identity fixtures (fixed salons) or from the provisioned Owner.

Final behaviour after F03-S04: the S04 spec states that S04's grant of `operator.salon.read` to
Support supersedes S01 AC-3's denial of salon reads. The cases therefore expect Support to be
refused salon creation and every plan action, and allowed the salon directory and detail.

## Tables

Hosts for AC-2 (API port and web port): `alpha.localhost`, bare `localhost`, `127.0.0.1`,
`api.localhost`, `admin.localhost`, `support.localhost`, `www.localhost`, `a.console.localhost`,
`console.localhost.example.com` (Host header only), `xconsole.localhost`, `console-x.localhost`;
each also with `X-Forwarded-Host: console.localhost` and `Forwarded: host=console.localhost`.
Upper case `CONSOLE.LOCALHOST` and the console host with its port are the positive controls.

| Protected operator action (final, after S04) | Admin | Support | Staff roles x5, customer | Signed out |
| --- | --- | --- | --- | --- |
| `GET /me`, `POST /sessions/refresh`, `POST /sessions/sign-out` | allow | allow | 401 `invalid_session` | 401 `invalid_session` |
| `GET /salons`, `GET /salons/{id}` | allow | allow | 401 `invalid_session` | 401 `invalid_session` |
| `POST /salons` | allow | 403 `permission_denied` | 401 `invalid_session` | 401 `invalid_session` |
| `GET /plans`, `GET /plans/{id}`, `POST /plans` | allow | 403 `permission_denied` | 401 `invalid_session` | 401 `invalid_session` |

Invalid input rows (AC-7, AC-10) are listed in the cases; each row expects 400 `bad_request` with a
field error naming the field, unless the row says 404.

## Cases

Every case id here is declared under `cases:` in the yaml beside it, and the reverse.

### TC-F03-S01-001 An enrolled operator obtains console access only after password and TOTP

- **Type:** security
- **Priority:** critical
- **Covers:** AC-1
- **Performed as:** Synthetic Admin with an enrolled TOTP seed, anonymous API client on the console host
- **Preconditions:** Admin created through the operator fixture hook; rate state reset
- **Steps:**
  1. POST /api/v1/operator/sign-in with the correct email, password and device label
  2. Call GET /me with the returned challenge token as a bearer
  3. POST /mfa/verify with the challenge and a current TOTP code
  4. GET /me with the issued access token
- **Expected:** Sign-in answers 200 with an MFA challenge and no access or refresh credential; the challenge token is refused by /me (401 `invalid_session`); verify returns credentials; /me returns `{id,kind:"operator",role:"OPERATOR_ADMIN",permissions,mfaEnabled:true}` with no tenant field. Responses are no-store.
- **Automation:** `qa/api/F03/S01-operator-access.spec.ts`

### TC-F03-S01-002 A first-time operator must enroll MFA before any session exists

- **Type:** security
- **Priority:** critical
- **Covers:** AC-1
- **Performed as:** Synthetic Support operator without a TOTP seed
- **Preconditions:** Operator created through the fixture hook without MFA
- **Steps:**
  1. Sign in with the correct password
  2. POST /mfa/challenges for the enrollment challenge
  3. POST /mfa/challenges with a verification challenge of an enrolled Admin
  4. POST /mfa/verify with a code computed from the returned setup secret
- **Expected:** Sign-in gives an enrollment challenge and no credentials; setup (secret and otpauth URI) is returned only for the enrollment challenge and refused for a verification challenge; credentials are issued only after the valid code; /me then shows `mfaEnabled:true`.
- **Automation:** `qa/api/F03/S01-operator-access.spec.ts`

### TC-F03-S01-003 Wrong, expired, consumed, exhausted or replayed MFA challenges issue no session

- **Type:** security
- **Priority:** critical
- **Covers:** AC-1
- **Performed as:** Enrolled synthetic Admin
- **Preconditions:** Test clock under QA control; rate state reset
- **Steps:**
  1. Verify with an altered challenge token
  2. Verify five times with wrong codes, then once with the right code
  3. Start a challenge, move the clock past its five-minute expiry, verify with a right code
  4. Complete one challenge, verify it again; on a new challenge reuse the already used TOTP step
- **Expected:** Every attempt answers 401 `invalid_credentials` with no credentials and no operator session cookie; only the single successful verify created a session.
- **Automation:** `qa/api/F03/S01-operator-access.spec.ts`

### TC-F03-S01-004 Simultaneous verification of one challenge creates exactly one operator session

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-1
- **Performed as:** Enrolled synthetic Admin
- **Preconditions:** One fresh MFA challenge
- **Steps:**
  1. Send eight identical /mfa/verify requests at once with the same current code
- **Expected:** Exactly one 200 with credentials; seven 401 `invalid_credentials`; a later challenge cannot reuse that TOTP step.
- **Automation:** `qa/api/F03/S01-operator-access.spec.ts`

### TC-F03-S01-005 Operator API and console routes on any other host answer 404 before side effects

- **Type:** security
- **Priority:** critical
- **Covers:** AC-2
- **Performed as:** Signed-in Admin (valid credential) and anonymous callers
- **Preconditions:** Host table above; a unique plan key prepared
- **Steps:**
  1. For every host row call GET /api/v1/operator/me and POST /api/v1/operator/plans (unique key, valid body) with the Admin bearer, with and without forged forwarding headers
  2. Request /console/salons and /operator-web/ actions on the same hosts at the web port
  3. On the console host list plans and search for the prepared key
  4. Call GET /api/v1/meta on the bare host and GET /api/v1/salon on alpha; open the bare status page and the alpha workspace sign-in
- **Expected:** Every non-console row answers 404 `not_found` (API) or the not-found page (web), never 401/403; no plan with the prepared key exists; upper case and port variants of the console host work; bare status, meta and the alpha workspace still answer normally.
- **Automation:** `qa/api/F03/S01-operator-access.spec.ts` and `qa/web/F03/S01-console-security.spec.ts`

### TC-F03-S01-006 Admin and Support receive exactly their documented operator permissions

- **Type:** permissions
- **Priority:** critical
- **Covers:** AC-3
- **Performed as:** Synthetic Admin, synthetic Support, signed out
- **Preconditions:** Role table above; one existing plan and one provisioned salon
- **Steps:**
  1. Call every protected action in the table as each actor
  2. Before and after the denied calls, read plan and salon totals and the target records as Admin
- **Expected:** Each cell matches the table (allow, 403 `permission_denied`, 401 `invalid_session`); totals and records are unchanged by every denied call.
- **Automation:** `qa/api/F03/S01-operator-access.spec.ts`

### TC-F03-S01-007 Every salon staff role and the customer principal are refused every operator action

- **Type:** permissions
- **Priority:** critical
- **Covers:** AC-3
- **Performed as:** alpha Owner, Manager, Receptionist, Professional, Accountant and an alpha customer, each with its own valid credentials
- **Preconditions:** F02 identity fixtures seeded on alpha; a customer signed in through the local code capture
- **Steps:**
  1. Call every row of the role table on the console host with each tenant bearer
  2. Repeat a create plan call on the actor's own salon host
- **Expected:** Console host: 401 `invalid_session` for every call; salon host: 404 `not_found`; no plan, salon or receipt is created.
- **Automation:** `qa/api/F03/S01-operator-access.spec.ts`

### TC-F03-S01-008 An operator credential authenticates no tenant identity or workspace

- **Type:** security
- **Priority:** critical
- **Covers:** AC-4
- **Performed as:** Signed-in Admin
- **Preconditions:** Admin credentials issued on the console
- **Steps:**
  1. Call GET /api/v1/identity/me, /staff, /audit and /sessions on alpha and on the bare API with the operator bearer
  2. Place the browser's operator cookies in a context and open the alpha workspace
- **Expected:** Every identity call answers 401 `invalid_session` (bare and alpha); the workspace redirects to its own sign-in. No staff session or audit entry is created for the operator.
- **Automation:** `qa/api/F03/S01-operator-access.spec.ts`

### TC-F03-S01-009 Matching operator and staff email and password grant no extra authority

- **Type:** security
- **Priority:** critical
- **Covers:** AC-4
- **Performed as:** A synthetic Admin and an alpha Manager seeded with the same email and password
- **Preconditions:** Both accounts exist with identical identifier and password values
- **Steps:**
  1. Sign in as staff on alpha and call operator /me and plans on the console with that credential
  2. Sign in as operator and call staff /me on alpha with that credential
- **Expected:** Each credential works only for its own kind: the staff credential is 401 on the console, the operator credential is 401 on alpha; each /me shows only its own kind and role.
- **Automation:** `qa/api/F03/S01-operator-access.spec.ts`

### TC-F03-S01-010 Two newly provisioned salons keep isolated staff and brand data

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-4
- **Performed as:** Admin, then each provisioned Owner
- **Preconditions:** Two salons provisioned in this test; alpha and beta brands read first
- **Steps:**
  1. Sign each Owner in at its own salon (enrolling MFA)
  2. Each Owner lists staff and reads GET /api/v1/salon on its host
  3. Owner A signs in on salon B's host; Owner A calls B's staff list with A's bearer on B's host
  4. Read alpha and beta brands again
- **Expected:** Each staff list contains only its own Owner; each brand shows its own name and time zone; the cross-salon sign-in is 401 `invalid_credentials` and the cross-host bearer 404 `not_found`; alpha and beta are unchanged.
- **Automation:** `qa/api/F03/S01-provisioning.spec.ts`

### TC-F03-S01-011 Expired, signed-out, revoked and disabled operator sessions authorize nothing

- **Type:** security
- **Priority:** critical
- **Covers:** AC-5
- **Performed as:** Signed-in Admin sessions
- **Preconditions:** Test clock under QA control; the disable-operator fixture hook (built in S02)
- **Steps:**
  1. Move the clock beyond access expiry and call /me with the old access token
  2. Sign out (204), then use that access and that refresh
  3. Move past the seven-day absolute session end and refresh
  4. Disable a signed-in operator and call /me with its live access
- **Expected:** Every call after the event answers 401 `invalid_session`; sign-out answers 204; no new credentials are issued.
- **Automation:** `qa/api/F03/S01-operator-access.spec.ts`

### TC-F03-S01-012 Refresh rotates once and reuse revokes the whole family

- **Type:** security
- **Priority:** critical
- **Covers:** AC-5
- **Performed as:** Signed-in Admin
- **Preconditions:** One session with refresh R1
- **Steps:**
  1. Refresh with R1 to obtain R2
  2. Refresh with R1 again
  3. Refresh with R2; call /me with the R2 access
- **Expected:** First refresh 200 with a different refresh; R1 reuse 401 `invalid_session`; R2 and its access are then refused: the family is revoked.
- **Automation:** `qa/api/F03/S01-operator-access.spec.ts`

### TC-F03-S01-013 Concurrent renewals never leave two usable refresh families

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-5
- **Performed as:** Signed-in Admin
- **Preconditions:** One session with refresh R1
- **Steps:**
  1. Send eight refreshes with R1 at once
  2. Try each returned refresh once more
- **Expected:** At most one refresh succeeded; no two returned refresh tokens both work afterwards; if reuse was detected, all are refused.
- **Automation:** `qa/api/F03/S01-operator-access.spec.ts`

### TC-F03-S01-014 Email ceiling counts known and unknown operators and survives a restart

- **Type:** security
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** Anonymous caller on the console host
- **Preconditions:** Rate state reset; test clock under QA control
- **Steps:**
  1. Ten wrong-password attempts for a known operator email (alternating case)
  2. Eleventh attempt; restart the API and attempt again
  3. Repeat steps 1-2 for an unknown email without the restart
  4. Move the clock beyond fifteen minutes and attempt again
- **Expected:** Attempts 1-10 answer 401 `invalid_credentials` with identical bodies for known and unknown; the eleventh and the post-restart attempt answer 429 `rate_limited` with a positive Retry-After; after the window an ordinary 401 returns.
- **Automation:** `qa/api/F03/S01-runtime.spec.ts`

### TC-F03-S01-015 Address ceiling holds across distinct emails and forged forwarding headers

- **Type:** security
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** Anonymous caller on the console host
- **Preconditions:** Rate state reset
- **Steps:**
  1. Twenty-nine wrong attempts with distinct unknown emails and distinct X-Forwarded-For values
  2. Two attempts with a present invalid address assertion (a malformed one and a forged signed one)
  3. The thirtieth wrong attempt; then attempts with a new email and new X-Forwarded-For or Forwarded values
- **Expected:** The invalid assertions are 400 `invalid_client_assertion` before rate accounting and are not counted (identity-and-access.md, address assertion); the thirtieth is 401; every later attempt is 429 `rate_limited` with positive Retry-After whatever the forwarding headers say.
- **Automation:** `qa/api/F03/S01-operator-access.spec.ts`

### TC-F03-S01-016 Concurrent failures cannot exceed the ceilings or reset MFA attempts

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** Anonymous caller; enrolled Admin
- **Preconditions:** Rate state reset
- **Steps:**
  1. Send twenty wrong-password attempts for one email at once
  2. On one challenge send five wrong codes at once, then the right code
- **Expected:** Exactly ten 401 and ten 429 for the email; the challenge is exhausted after the five failures and the right code is refused; no failed transaction undid a counted attempt.
- **Automation:** `qa/api/F03/S01-operator-access.spec.ts`

### TC-F03-S01-017 An Admin creates a plan whose exact values persist and can be assigned

- **Type:** api
- **Priority:** high
- **Covers:** AC-7
- **Performed as:** Synthetic Admin
- **Preconditions:** Unique plan key
- **Steps:**
  1. POST /plans with Idempotency-Key and every field at its bounds (seats 1, branches 1000000, allowance 0, both booleans true)
  2. GET /plans/{id}; find it in GET /plans?q=<key>
  3. Restart the API and read it again; provision a salon with this planId
- **Expected:** 201 with `{id,key,name,staffSeats,branches,messageAllowance,customDomain,dedicatedApp,createdAt,updatedAt}` equal to the input; identical after restart; the salon detail shows `plan:{id,key,name}` of it.
- **Automation:** `qa/api/F03/S01-runtime.spec.ts`

### TC-F03-S01-018 Invalid plan definitions are refused with field errors and create nothing

- **Type:** api
- **Priority:** high
- **Covers:** AC-7
- **Performed as:** Synthetic Admin
- **Preconditions:** Plan total read first
- **Steps:**
  1. POST /plans for each row: each field missing; unknown field; `"5"`, `1.5`, `null`, `true` for a limit; seats or branches 0 or 1000001; allowance -1 or 1000001; boolean as string; key of 2 or 31 characters, with underscore, leading hyphen; name empty, blank or 121 characters; key already taken (409 row)
  2. Read the plan total and search for each key
- **Expected:** Each row is 400 `bad_request` naming the field (the taken key 409 `plan_key_taken`); the total is unchanged and no attempted key exists.
- **Automation:** `qa/api/F03/S01-provisioning.spec.ts`

### TC-F03-S01-019 An Admin provisions a salon whose profile, plan and hostname are correct

- **Type:** api
- **Priority:** critical
- **Covers:** AC-8
- **Performed as:** Synthetic Admin
- **Preconditions:** A plan; unique slug given in mixed case
- **Steps:**
  1. POST /salons with the complete profile, planId and owner credentials
  2. GET /salons/{id}; find it in GET /salons?q=
  3. GET /api/v1/salon on `<slug>.localhost`
- **Expected:** 201 with exactly `{id,slug,hostname,planId,createdAt}`, slug lower-cased, hostname `<slug>.<platform domain>`; detail shows every supplied nonsecret value, `status:active`, the plan summary and ownerEmail, and no password; the hostname resolves the supplied name and time zone.
- **Automation:** `qa/api/F03/S01-provisioning.spec.ts`

### TC-F03-S01-020 The provisioned Owner signs in only at its salon and must enroll MFA

- **Type:** security
- **Priority:** critical
- **Covers:** AC-9
- **Performed as:** The provisioned Owner; the Admin who provisioned it
- **Preconditions:** Salon provisioned in this test
- **Steps:**
  1. Owner signs in at alpha and at the console with the supplied password
  2. Owner signs in at its salon: expect `enroll_mfa`; enroll and verify
  3. Read /me; call Owner-only staff list
  4. Call /api/v1/identity/me on the new salon with the Admin's operator bearer
- **Expected:** alpha and console refuse (401 `invalid_credentials`); no session before enrollment; afterwards /me shows role OWNER with the existing Owner permissions; the operator bearer is 401; the provisioning response held no password or token.
- **Automation:** `qa/api/F03/S01-provisioning.spec.ts`

### TC-F03-S01-021 Invalid salon input or a missing plan creates no tenant, Owner, brand or audit

- **Type:** api
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** Synthetic Admin
- **Preconditions:** Salon total read first; platform audit baseline taken
- **Steps:**
  1. POST /salons for each row: every reserved slug; slug of 2 or 31 characters, upper-case-only after trim invalid characters, leading or trailing hyphen; the 3- and 30-character boundaries (positive rows); country `XX`, currency `ABC`, language `fr`, zone `Mars/Base`; name empty or 121; address 501; contact email 255 or malformed; phone not Sri Lankan mobile; owner name empty; owner email malformed; owner password 14 code points or common; unknown top-level or owner field; missing or malformed Idempotency-Key; random planId (404 row)
  2. Check each rejected slug's hostname, the salon total and platform audit
- **Expected:** Each negative row is 400 `bad_request` naming the field (header named for the key) or 404 `not_found` for the plan; no hostname resolves, the total grows only by the positive rows, and no success audit or receipt exists for a rejected row.
- **Automation:** `qa/api/F03/S01-provisioning.spec.ts`

### TC-F03-S01-022 Simultaneous creations for one slug create one salon and cannot replace a fixed salon

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-11
- **Performed as:** Synthetic Admin
- **Preconditions:** A plan; one unique slug
- **Steps:**
  1. Send ten POST /salons at once with distinct keys and owners for the same slug
  2. Send creations for `alpha`, `beta`, `gamma`
  3. Read tenants, Owners and brands for the slug from the database; read the alpha brand
- **Expected:** Exactly one 201; nine 409 `slug_taken`; one tenant, one Owner, one brand, one receipt and one success audit; the fixed-salon rows are 409 `slug_taken` and alpha, beta and gamma are unchanged.
- **Automation:** `qa/api/F03/S01-provisioning.spec.ts`

### TC-F03-S01-023 Simultaneous creations for one plan key create exactly one plan

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-11
- **Performed as:** Synthetic Admin
- **Preconditions:** One unique plan key
- **Steps:**
  1. Send ten POST /plans at once with distinct keys of idempotency and the same plan key
- **Expected:** Exactly one 201 and nine 409 `plan_key_taken`; GET /plans?q=<key> totals 1.
- **Automation:** `qa/api/F03/S01-provisioning.spec.ts`

### TC-F03-S01-024 Repeated creation with one key replays the original result and one audit

- **Type:** api
- **Priority:** critical
- **Covers:** AC-12
- **Performed as:** Synthetic Admin; a second Admin; anonymous
- **Preconditions:** Unique plan key and salon slug
- **Steps:**
  1. Create a plan and a salon; repeat each with the same key and normalized-equal payload, sequentially and eight at once
  2. Repeat with the same key and a changed field
  3. Send the same key and payload unauthenticated and as the second Admin
- **Expected:** Every replay answers 201 with the original id and body; one record and one creation audit exist; changed input is 409 `idempotency_conflict` and changes nothing; unauthenticated is 401 and reveals no result; the second Admin's key is scoped to it and does not return the first Admin's result.
- **Automation:** `qa/api/F03/S01-provisioning.spec.ts`

### TC-F03-S01-025 A failed Owner or brand bootstrap leaves nothing and a retry with the original key succeeds

- **Type:** data-integrity
- **Priority:** critical
- **Covers:** AC-13
- **Performed as:** Synthetic Admin with the controlled bootstrap-failure fixture
- **Preconditions:** Failure armed for the Owner step, then for the brand step
- **Steps:**
  1. POST /salons with a unique slug and key
  2. Check the hostname and the database for tenant, profile, assignment, Owner, brand, receipt and success audit rows
  3. Disarm the failure and repeat with the original key
- **Expected:** The failed request is an error with no resolvable salon and no row of any kind for it; alpha, beta and gamma are unchanged; the retry answers 201 and creates the salon exactly once.
- **Automation:** `qa/api/F03/S01-provisioning.spec.ts`

### TC-F03-S01-026 Directories paginate, search literally and expose only allowlisted metadata

- **Type:** api
- **Priority:** high
- **Covers:** AC-14
- **Performed as:** Synthetic Admin
- **Preconditions:** Five plans and three salons with a unique name fragment; one name containing `%` and `_`
- **Steps:**
  1. Page through each list with size 2; search the fragment in mixed case; search `%` and `_` literally
  2. Send page -1, 100001, size 0, 101, q of 121 characters; GET an unknown and a malformed id
  3. Read alpha's detail
- **Expected:** `{items,page,size,total}` with correct totals, creation time descending then id, no duplicates across pages; literal case-insensitive matching; invalid paging 400 naming the field; unknown id 404, malformed id 400; items and details contain only the documented keys with no tenant business records or credentials; alpha shows null plan and null unset profile values.
- **Automation:** `qa/api/F03/S01-provisioning.spec.ts`

### TC-F03-S01-027 Tenant and platform database roles cannot cross the platform boundary

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-15
- **Performed as:** Database observer: the tenant runtime role and the platform runtime role
- **Preconditions:** Platform role name from the handoff; rows proved to exist first
- **Steps:**
  1. As the tenant role, select and insert on each platform table and execute each bootstrap function, in rolled-back transactions
  2. As the platform role, select and insert on tenant tables (identity, brand)
  3. Read role attributes, table owners and forced RLS; compare alpha/beta/gamma rows with the pre-migration fixture values
- **Expected:** Every cross-boundary statement is refused; the platform role is non-owner, non-superuser, without BYPASSRLS; forced RLS remains; fixed salons and their rows are intact; the catalogue is unchanged after the check.
- **Automation:** `qa/checks/F03/S01-database-boundary.sh`

### TC-F03-S01-028 The API refuses to start with an owning, RLS-bypassing or missing platform role

- **Type:** security
- **Priority:** critical
- **Covers:** AC-15
- **Performed as:** Database observer controlling local API start
- **Preconditions:** Temporary roles created inside the check and dropped afterwards
- **Steps:**
  1. Start the API with the platform connection configured as the table owner
  2. Start it with a BYPASSRLS role
  3. Start it with the platform role configuration removed
  4. Restore the configuration and start
- **Expected:** Each misconfigured start refuses to serve (no health 200) without falling back to another role; the restored start is healthy.
- **Automation:** `qa/checks/F03/S01-database-boundary.sh`

### TC-F03-S01-029 Platform audit records operator and creation events immutably and without secrets

- **Type:** security
- **Priority:** critical
- **Covers:** AC-16
- **Performed as:** Synthetic Admin; database observer
- **Preconditions:** Test clock fixed at a known instant; unique values for every secret and personal field
- **Steps:**
  1. Perform a failed sign-in, a successful sign-in with MFA, sign-out, a plan creation, a salon creation and a rolled-back creation
  2. Read the platform audit rows for these actors and subjects; attempt UPDATE and DELETE as the platform role
  3. Search audit, receipts, API and web logs, and every error body for the password, TOTP seed, tokens, contact email, phone and address values
- **Expected:** One row per event with actor, action, subject, the fixed application time and a trace id; no success row for the rolled-back creation; UPDATE and DELETE are refused; none of the searched values appears anywhere.
- **Automation:** `qa/api/F03/S01-audit.spec.ts` and `qa/checks/F03/S01-database-boundary.sh`

### TC-F03-S01-030 Console mutations reject missing or foreign Origin and keep credentials in host-only cookies

- **Type:** security
- **Priority:** critical
- **Covers:** AC-17
- **Performed as:** Browser context on the console; anonymous and signed-in Admin
- **Preconditions:** Console reachable at its web port
- **Steps:**
  1. Send every /operator-web mutation (sign-in, MFA verify, refresh, sign-out, create plan, create salon) with no Origin, a foreign Origin and `Sec-Fetch-Site: cross-site`
  2. Sign in through the UI; read cookies, page HTML, client JSON responses, localStorage, sessionStorage and visited URLs
- **Expected:** Every hostile mutation is refused with no effect; `salon_operator_*` cookies are HttpOnly, host-only (no Domain), SameSite as documented; no token or challenge credential appears anywhere else; only the transient authenticator setup is displayed during enrollment.
- **Automation:** `qa/web/F03/S01-console-security.spec.ts`

### TC-F03-S01-031 Concurrent console tabs and a browser without Web Locks stay safe

- **Type:** functional
- **Priority:** high
- **Covers:** AC-17
- **Performed as:** Signed-in Admin in two tabs; a browser context without `navigator.locks`
- **Preconditions:** Access close to expiry via the test clock
- **Steps:**
  1. Trigger renewal in both tabs at once; then sign out in one tab and act in the other
  2. Open the console in the context without Web Locks; attempt sign-in and, with existing cookies, read the salon directory
- **Expected:** Both tabs keep one working session after renewal; after sign-out the other tab requires sign-in; without Web Locks a translated browser-support message appears, credential-changing actions are blocked and reads still work.
- **Automation:** `qa/web/F03/S01-console-security.spec.ts`

### TC-F03-S01-032 A keyboard user signs in, defines a plan, creates a salon and inspects it

- **Type:** functional
- **Priority:** high
- **Covers:** AC-18
- **Performed as:** New synthetic Admin using only the keyboard
- **Preconditions:** Empty search results available by unique query
- **Steps:**
  1. Sign in and enroll MFA from the console
  2. Open Create plan, submit invalid values, correct and submit
  3. Open Create salon, submit a taken slug, then a valid one; simulate an uncertain failure and retry
  4. Open the salon detail; search with no match
- **Expected:** Loading, validation (field messages announced), duplicate (`slug_taken` message), unavailable, empty and success states appear; passwords are cleared after error and success; the retry reuses the same key and creates one salon; success shows hostname, plan and the Owner's next sign-in step; the detail shows the values, checked once more through the API.
- **Automation:** `qa/web/F03/S01-console-screens.spec.ts`

### TC-F03-S01-033 Console screens fit every width in the matrix without clipping or horizontal scroll

- **Type:** responsive
- **Priority:** high
- **Covers:** AC-18
- **Performed as:** Signed-in Admin
- **Preconditions:** Long salon and plan names; a long list and an empty search
- **Steps:**
  1. At 360, 390, 768, 1024, 1280 and 1440 open sign-in, salon and plan directories and details, and both create sheets
- **Expected:** No horizontal scroll; no test-id element outside the viewport or clipped; every action reachable; the sheet is a bottom sheet on phones and a right drawer on desktop. Long values are used (plan and salon names); an empty directory cannot be produced on the shared local database, so the empty state is left to the developer's component tests and noted in the review.
- **Automation:** `qa/web/F03/S01-console-screens.spec.ts`

### TC-F03-S01-034 Console screens pass the automated scan, keep focus contained and announce errors

- **Type:** accessibility
- **Priority:** high
- **Covers:** AC-18
- **Performed as:** Signed-in Admin, keyboard
- **Preconditions:** As TC-F03-S01-033
- **Steps:**
  1. Run the axe scan on each screen and state
  2. Open each sheet: Tab cycles inside, Escape closes, focus returns to the opener
  3. Submit invalid input and read the live region
- **Expected:** No serious or critical violations; labels on every field; focus contained and returned; errors announced.
- **Automation:** `qa/web/F03/S01-console-screens.spec.ts`

### TC-F03-S01-035 Each operator role sees exactly its console navigation and Support no management actions

- **Type:** permissions
- **Priority:** high
- **Covers:** AC-18
- **Performed as:** Signed-in Admin; signed-in Support
- **Preconditions:** Final post-S04 permissions
- **Steps:**
  1. List the console navigation for each role
  2. As Support open /console/plans and the Create salon action
- **Expected:** Admin navigation is exactly Salons, Plans, Support sessions and Sign out; Support's is exactly Salons, Support sessions and Sign out (the final navigation in the S04 spec, superseding S01 AC-18's list); Support has no plan or create controls and sees a clear access-denied state on plans.
- **Automation:** `qa/web/F03/S01-console-screens.spec.ts`

### TC-F03-S01-036 Console screens follow the supplied Salon-mode reference components

- **Type:** functional
- **Priority:** medium
- **Covers:** AC-18
- **Performed as:** QA reviewer
- **Preconditions:** Prototype reference components and the rendered console at the width matrix
- **Steps:**
  1. Compare shell, cards, fields, list rows and sheets with `docs/design/prototype/index.html` Salon mode at 360 and 1280
  2. Record screenshot pairs; label the absence of exact operator reference screens
- **Expected:** The console uses the neutral theme and the reference grammar (Sidebar, Sheet, Item rows, Badge, Empty, Pagination); differences are classified in review.md.
- **Automation:** manual, because judging conformance to a reference grammar with no exact operator screen needs a person; performed at feature-verify

### TC-F03-S01-037 A stopped or silent database is reported within five seconds and recovers without restart

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-19
- **Performed as:** Signed-in Admin (API and console)
- **Preconditions:** Database controlled through `infra/scripts/local-service.sh` (stop, then pause)
- **Steps:**
  1. With requests in flight, stop the database; list salons and create a salon (key K) from API and console
  2. Start the database; mark the instant it accepts connections; retry the create with key K
  3. Repeat with the database paused (silent) instead of stopped
- **Expected:** Unavailable (503 `service_unavailable` / console unavailable state) within five seconds for each request; no partial salon; recovery within five seconds of the database actually being back; the retry with K creates exactly one salon.
- **Automation:** `qa/api/F03/S01-runtime.spec.ts`

### TC-F03-S01-038 A stopped or silent API is reported by the console within five seconds and recovers

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-19
- **Performed as:** Signed-in Admin in the console
- **Preconditions:** API controlled by stopping it, then by suspending its process
- **Steps:**
  1. Stop the API; load the salon directory and submit Create plan
  2. Start it; mark health 200; reload
  3. Repeat with the API process suspended (silent), then resumed
- **Expected:** The console shows unavailable within five seconds (three-second upstream budget), keeps the form for a retry with the same key and no partial plan; it recovers within five seconds of the API answering, without a web restart.
- **Automation:** `qa/web/F03/S01-console-security.spec.ts`

### TC-F03-S01-039 Local operator hooks are loopback-only, absent from the contract and absent outside local

- **Type:** security
- **Priority:** critical
- **Covers:** AC-20
- **Performed as:** Anonymous caller; local API restarted under a non-local profile
- **Preconditions:** Machine's non-loopback address known; retained local keys
- **Steps:**
  1. Search docs/api/openapi.json for `/local/operator`
  2. Call each operator hook through the non-loopback address
  3. Start the API under the non-local profile with explicit synthetic secrets; call the hooks; list operator accounts in the database
  4. Restart locally; check fixture identities, keys and alpha/beta/gamma rows
- **Expected:** No hook in the contract; non-loopback calls 404; non-local hooks 404 and no default operator is created; local restart keeps fixture identities, keys and existing salons unchanged.
- **Automation:** `qa/api/F03/S01-runtime.spec.ts` and `qa/checks/F03/S01-database-boundary.sh`

### TC-F03-S01-040 Built web artifacts contain no environment files and no identity or operator key value

- **Type:** build
- **Priority:** critical
- **Covers:** AC-20
- **Performed as:** Shell check over the standalone web build
- **Preconditions:** `pnpm --filter @salon/web build` output present; local key files present
- **Steps:**
  1. List files in the build output and search for `.env*` files
  2. Search the build for every identity and operator key value from the local key files without printing them
  3. Prove the search fails on a copy outside the repository with one key planted
- **Expected:** No environment file and no key value in the build; the planted copy is detected (exit non-zero).
- **Automation:** `qa/checks/F03/S01-build-artifacts.sh`

### TC-F03-S01-041 The generated contract states the nullability and success statuses the API returns

- **Type:** api
- **Priority:** medium
- **Covers:** AC-14
- **Performed as:** Check of the committed `docs/api/openapi.json`
- **Preconditions:** None; added at feature-verify after the operator sign-in, salon detail and entitlements responses failed the generated schema for their documented nulls (BUG-F03-001). The spec's "API and data changes" require the generated contract and client to include nullability and 201/204 statuses; AC-14's allowlisted fields are what clients read through it.
- **Steps:**
  1. List every schema property that combines `$ref` with a nullable `type`, or a nullable `type` with an `enum` lacking null
  2. Read the documented success status of the S04 support routes that answer 201 or 204
- **Expected:** No property forbids the null the specs require (legacy `plan`, `credentials` before MFA, legacy entitlements, `notice`); the S04 start and entry-code routes document 201 and the three ending routes 204.
- **Automation:** `qa/api/F03/S01-runtime.spec.ts`

### TC-F03-S01-042 Every plan can be chosen in the console's plan pickers

- **Type:** functional
- **Priority:** medium
- **Covers:** AC-7
- **Performed as:** Synthetic Admin in the console
- **Preconditions:** More than 100 plans exist; one plan older than the 100 newest. Added at feature-verify with BUG-F03-006.
- **Steps:**
  1. Open Create salon: the picker pages beyond 100 plans; search for the older plan and choose it
  2. In a salon's Move plan sheet, search for it, choose it and move the salon
- **Expected:** The older plan can be found and chosen in both pickers, and the salon is created on it or moved to it (checked through the API).
- **Automation:** `qa/web/F03/S01-console-screens.spec.ts` (automated at BUG-F03-006's re-verification; it arranges at least 100 plans newer than its own)
