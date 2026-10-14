# F02-S01 Staff access: test cases

Expected outcomes come from the final specs, feature requirements and Read first documents. The feature metadata and recorded runner reports hold execution status; stage QA status remains unchanged during whole-feature verification.

Environment: local only, alpha/beta/gamma. Every test resets only synthetic identity fixtures, generates credentials in memory, authenticates through real APIs, and restores the application clock. No stored developer secrets are copied. Runtime controls run only after all stages close and the lead dispatches F02 QA.

The feature cases document the finalized approach and cross-stage journeys. S01 needs API/security/tenant-isolation/permissions/concurrency/data-integrity/resilience/build; responsive/branding/accessibility belong to S04. No mobile/payment-integrity applies; no regression/smoke/staging run is requested.

## Endpoint and role tables

Tenant mismatch covers GET /me,/sessions,/staff,/audit; POST /staff/sign-in,/staff/mfa/challenges,/staff/mfa/enroll,/staff/mfa/verify,/sessions/refresh,/staff; PATCH and DELETE /staff/{id}; DELETE /sessions/{id},/sessions; PUT /staff/password. ID variants use present beta and random missing ids; list/search variants first prove beta records exist.

|Protected action|Owner|Manager|Receptionist|Professional|Accountant|Signed out|
|---|---|---|---|---|---|---|
|Own me/devices/password/enrollment/revocation|Allow|Allow|Allow|Allow|Allow|401|
|Staff/audit list|Allow|Allow|403|403|403|401|
|Create/change/remove staff/grant|Allow|403|403|403|403|401|

Account-kind refusals will be added through final S02 customer fixtures; no customer bearer is manufactured. Future business endpoints (calendar/refund/revenue) are not fabricated; S01 tests their documented permission/scope contract only.

## Cases

### TC-F02-S01-001 normalized staff identifiers establish bounded credentials

- **Type:** api
- **Priority:** high
- **Covers:** AC-1
- **Performed as / preconditions:** Anonymous staff at alpha; a fresh Manager is seeded.
- **Steps:** Sign in with uppercase email, local mobile and +94 mobile using the same correct password. Read the principal with each returned access token.
- **Expected:** Each returns authenticated signed credentials for this Manager; access lasts five minutes and refresh ends seven days after sign-in.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-002 wrong unknown and disabled credentials expose the same denial

- **Type:** security
- **Priority:** critical
- **Covers:** AC-1
- **Performed as / preconditions:** Alpha Owner and Manager; Owner removes the Manager after its successful sign-in.
- **Steps:** Submit a wrong Owner password, an unknown identifier and the disabled Manager's correct password.
- **Expected:** Every outcome is 401 invalid_credentials without credentials, account-existence fields or a disabled-account distinction.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-003 identifier limits survive failed outcomes and normalization

- **Type:** security
- **Priority:** critical
- **Covers:** AC-1
- **Performed as / preconditions:** Unknown synthetic identifier at alpha and real test clock.
- **Steps:** Make ten wrong-password attempts, alternating identifier case; request an eleventh. Move the clock 901 seconds and repeat.
- **Expected:** First ten are 401 invalid_credentials, eleventh is 429 rate_limited with positive Retry-After; a fresh fifteen-minute window permits an ordinary invalid-credentials answer.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-004 trusted address limits cannot be bypassed with forwarded headers

- **Type:** security
- **Priority:** critical
- **Covers:** AC-1
- **Performed as / preconditions:** Anonymous at alpha with reset synthetic rate state.
- **Steps:** Make thirty invalid attempts using distinct unknown identifiers and distinct X-Forwarded-For values; send a thirty-first with another header.
- **Expected:** Thirty-first is 429 rate_limited and positive Retry-After; spoofed address headers cannot reset the trusted socket-address bucket.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-005 an Owner enrollment challenge never authorizes a session before TOTP

- **Type:** security
- **Priority:** critical
- **Covers:** AC-2
- **Performed as / preconditions:** Alpha Owner without MFA, seeded with synthetic password.
- **Steps:** Password sign-in; call MFA enroll with its challenge. Attempt /me with the challenge credential; calculate RFC6238 TOTP and verify.
- **Expected:** Enrollment-only authority, no session before correct verification; secret/otpauth appears only under the correct enrollment challenge. Verification produces an MFA-enabled Owner principal.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-006 invalid expired consumed or exhausted MFA challenges issue no second session

- **Type:** security
- **Priority:** critical
- **Covers:** AC-2
- **Performed as / preconditions:** Alpha enrolled Owner and application clock.
- **Steps:** Alter a challenge credential, make five wrong guesses, try a correct guess after exhaustion. Expire another challenge through the clock. Consume a fresh challenge and submit it again.
- **Expected:** Wrong/expired/exhausted/consumed outcomes are 401 invalid_credentials and issue no second credentials; the legitimately completed session remains usable.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-007 simultaneous TOTP reuse establishes exactly one session

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-2
- **Performed as / preconditions:** One alpha Owner challenge with an unused current TOTP step.
- **Steps:** Release eight correct-code verifications together. Read devices through the successful session. Request another challenge for the same account and reuse the code.
- **Expected:** Exactly one succeeds, seven return401; exactly one session exists and a second challenge cannot reuse that account's consumed step.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-008 voluntary MFA enrollment replaces pre-enrollment authority

- **Type:** security
- **Priority:** critical
- **Covers:** AC-2
- **Performed as / preconditions:** Signed-in alpha Professional without MFA.
- **Steps:** Create an authenticated enrollment challenge, enroll and complete TOTP verification. Use old access and attempt another password sign-in.
- **Expected:** New principal is MFA-enabled, old access stops immediately, subsequent password sign-in requires verify_mfa and issues no unauthenticated credentials.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-009 hostile access claims and expiry cannot authenticate

- **Type:** security
- **Priority:** critical
- **Covers:** AC-3
- **Performed as / preconditions:** Signed-in alpha Manager with a valid signed JWT.
- **Steps:** Mutate signature, algorithm and claims without a valid signature; submit to /me. Advance application clock beyond access expiry and use the original token.
- **Expected:** Every hostile/expired access receives401 invalid_session; no principal is exposed. Tests never mint valid bearer credentials.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-010 all identity endpoints honor tenant and hostname boundaries

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-3
- **Performed as / preconditions:** Authenticated alpha and beta Owners; both principals proven to exist.
- **Steps:** Send every endpoint in the tenant matrix on beta with alpha access. Check both original principals afterward. Use reserved/unknown hosts; use bare API for signed /me and public auth/opaque refresh.
- **Expected:** Every host/token disagreement returns404 not_found without mutation. Bare signed protected access resolves only the verified salon; reserved/unknown hosts and bare hostname-required auth routes remain404.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-011 foreign ids lists and MFA credentials reveal no foreign records

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-3, AC-6
- **Performed as / preconditions:** Alpha and beta Owners, beta staff/session/challenge present and readable as beta.
- **Steps:** As alpha, patch/remove beta staff, revoke beta devices, search lists/audit, use beta MFA challenge/refresh. Repeat id-bearing calls with random nonexistent ids. Read beta afterward.
- **Expected:** Foreign ids are indistinguishable404, lists/audit omit beta; foreign MFA/refresh fails without data; beta records remain unchanged.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-012 concurrent salon requests never carry another principal

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-3
- **Performed as / preconditions:** Signed-in alpha and beta Managers.
- **Steps:** Release24 alternating /me reads concurrently using the matching salon/token. Compare each returned id with its caller.
- **Expected:** All24 replies belong to the correct salon/principal; no pooled context crosses.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-013 device lists paginate and identify only one's current session

- **Type:** api
- **Priority:** high
- **Covers:** AC-4
- **Performed as / preconditions:** Alpha Manager with two sessions and a different Manager with one.
- **Steps:** Read own devices at page0/1,size1 then full list; try negative page, size0/101,page100001; submit an81-character device label.
- **Expected:** Distinct paginated rows, total2 and exactly one current session; other user absent. Invalid bounds return400 bad_request, label error names deviceLabel.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-014 renewal rotates credentials and cannot extend the absolute session

- **Type:** security
- **Priority:** critical
- **Covers:** AC-4
- **Performed as / preconditions:** Signed-in alpha Manager.
- **Steps:** Advance clock past access expiry; verify denial; refresh; compare credentials and original refresh expiry. Advance beyond absolute refresh expiry and renew.
- **Expected:** Expired access fails; live refresh rotates once and keeps absolute seven-day endpoint; post-absolute renewal is401 invalid_session.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-015 simultaneous refresh permits one rotation and replay revokes its family

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-4
- **Performed as / preconditions:** One alpha Manager refresh credential.
- **Steps:** Release eight renewals together; inspect the single successful response, try its access and refresh after the losing replay, then sign in anew and read devices.
- **Expected:** One successful rotation, seven denials; replay revokes the entire winning family, no credential from it authorizes further use, fresh sign-in works.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-016 single and all-device revocation is immediate and owner-scoped

- **Type:** security
- **Priority:** critical
- **Covers:** AC-4
- **Performed as / preconditions:** Alpha Manager with two sessions and another Manager.
- **Steps:** Try revoking other user's id. Revoke own second device, test its access/refresh; revoke all own devices, check self/other user.
- **Expected:** Other user's id is404 and unaffected; own deletion is204, revoked access/refresh is401, all-device deletion stops self immediately while the other account stays valid.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-017 each role receives exactly its permitted identity actions

- **Type:** permissions
- **Priority:** critical
- **Covers:** AC-5, AC-6, AC-7, AC-8
- **Performed as / preconditions:** One account per role at alpha, fresh Professional and Manager targets for each row.
- **Steps:** Run protected-action matrix below for every role; compare exact documented permission keys; verify refused writes leave target access/scope unchanged. Change own password/revoke current device; try matrix signed out.
- **Expected:** Exactly the documented role matrix,403 permission_denied for restricted actions,401 invalid_session when signed out. Own management works for every role; denied actions change nothing.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-018 live role and Professional grant changes replace cached permissions

- **Type:** permissions
- **Priority:** critical
- **Covers:** AC-5, AC-6
- **Performed as / preconditions:** Alpha Owner, Professional own scope and Manager.
- **Steps:** Grant/revoke Professional salon-wide visibility using fresh version. Demote Manager to Receptionist and re-use its existing access for /me and audit.
- **Expected:** Scope and financial permission change immediately; Professional staffId is its own id. Demoted live access has current role/permissions; audit now403.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-019 staff creation accepts either identifier and refused edits preserve accounts

- **Type:** api
- **Priority:** high
- **Covers:** AC-6, AC-9
- **Performed as / preconditions:** Alpha Owner and an account created through POST/staff.
- **Steps:** Create email-only and mobile-only accounts with valid passwords; validate normalized supplied/nullable omitted identifiers in each StaffView and the nonempty PageStaffView; sign in using each supplied identifier.  Table-drive invalid identifiers/no identifier/role/password/boolean, normalized duplicate and stale patch after a valid role update; compare staff state.
- **Expected:** Creation201, valid patch200; invalids400 field errors, duplicate/stale409 conflict; rejected calls add/change nothing.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-020 removal and Owner promotion end every existing credential

- **Type:** security
- **Priority:** critical
- **Covers:** AC-6
- **Performed as / preconditions:** Alpha Owner and Manager with multiple sessions; another Manager without MFA.
- **Steps:** Remove first Manager and use each token/refresh. Promote second to Owner with current version, use old access then password sign-in.
- **Expected:** All removed sessions immediately invalid; promotion invalidates old sessions and password sign-in returns enroll_mfa with no session.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-021 concurrent demotion and removal retain an active Owner

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-6
- **Performed as / preconditions:** Two alpha Owners, each proven active with MFA.
- **Steps:** For removal and demotion separately, each concurrently acts on the other. Read surviving Owners. Attempt removal/demotion of the last Owner.
- **Expected:** Exactly one state mutation succeeds, one active Owner remains; last-Owner operations are409 conflict and cannot remove the security administrator.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-022 password policy and old-password proof rotate current and revoke other sessions

- **Type:** security
- **Priority:** critical
- **Covers:** AC-7
- **Performed as / preconditions:** Alpha Manager with two sessions.
- **Steps:** Try wrong old password; new password14/129 code points and common password. Change to a15-code-point Unicode/space password, use old and current credentials and old/new password.
- **Expected:** Wrong old401, weak new400 names newPassword; valid Unicode/space password accepted, current credentials rotate, old/current-prechange and other sessions stop, old password fails and new signs in.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-023 sensitive identity actions write paginated secret-free before-after audit

- **Type:** security
- **Priority:** critical
- **Covers:** AC-8
- **Performed as / preconditions:** Alpha MFA Owner and Professional; grant transition performed.
- **Steps:** Read subject audit, its one-row pagination and invalid page/size values; compare before/after; scan returned JSON against supplied secret values.
- **Expected:** Nonempty actor/time/action/id audit with nonsecret before/after, no password/full phone/TOTP/access/refresh. Pagination works; invalids400. Role reader restrictions are case017.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-024 new identity records fail closed at the database and persist no raw credentials

- **Type:** data-integrity
- **Priority:** critical
- **Covers:** AC-9, AC-8
- **Performed as / preconditions:** Alpha/beta Owners authenticated, both refreshed; all seven record types exist. Docker/psql observation available.
- **Steps:** For each table prove beta count>0, missing-context zero and alpha cannot see beta under application role; check enabled/forced RLS. Inspect ciphertext/hash and attempt rolled-back foreign-tenant/reference writes and audit alteration.
- **Expected:** All seven types isolated, all secrets absent in raw form, foreign writes/links refused and audit immutable. Existing developer447-test gate independently proves Hibernate-only and RLS-only mechanisms; QA does not duplicate an inside integration harness.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-025 contract and local fixtures expose no accidental auth surface

- **Type:** build
- **Priority:** high
- **Covers:** AC-9, AC-10
- **Performed as / preconditions:** Committed generated OpenAPI and local loopback API.
- **Steps:** Inspect stable error enum and201/204 response declarations, ensure hooks absent. Idempotently seed all roles in all three salons, reset identities and compare immutable audit. Try bare-host reset.
- **Expected:** Contract describes public statuses/errors, hooks absent; each role seeded once, credentials never returned, reset affects synthetic identities only and preserves audit; missing salon404.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-026 malformed identity input is refused with field errors and no mutation

- **Type:** api
- **Priority:** high
- **Covers:** AC-1, AC-2, AC-4, AC-6, AC-7, AC-8
- **Performed as / preconditions:** Alpha Owner and baseline staff list.
- **Steps:** Table-drive wrong JSON types/empty values across sign-in, creation,patch,password,MFA and refresh. Invalid staff/audit pagination.
- **Expected:** 400 bad_request names the offending field, no credential or staff mutation; staff list unchanged.
- **Automation:** `qa/api/F02/S01-staff-access.spec.ts` (registered for feature QA)

### TC-F02-S01-027 API restart preserves local keys sessions and failed-attempt limits

- **Type:** security
- **Priority:** critical
- **Covers:** AC-1, AC-10
- **Performed as / preconditions:** Prepared final local artifact and runtime root; Manager and10 failed attempts on an unknown identifier.
- **Steps:** Record key-file permissions/fingerprint, restart API with existing scripts, use old signed access, retry exhausted identifier, scan API log against supplied secrets.
- **Expected:** Key file0600 and unchanged, session still valid, limit still429, no credentials/full phone in log. Control marks record restart separately.
- **Automation:** `qa/api/F02/S01-runtime.spec.ts` (registered for feature QA)

### TC-F02-S01-028 stopped database answers identity requests within five seconds and recovers

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-10
- **Performed as / preconditions:** Prepared local runtime; alpha/beta Managers authenticated and API process recorded.
- **Steps:** Stop only local PostgreSQL via existing script; GET/me for both actors; restore in finally. Prove SELECT1 availability, then measure successful protected reads and PID.
- **Expected:** 503 service_unavailable within5s while down; same principals within5s of confirmed database availability, same API process. Existing CR003 endpoint deadline, not an invented exact3s HTTP socket budget.
- **Automation:** `qa/api/F02/S01-runtime.spec.ts` (registered for feature QA)

### TC-F02-S01-029 silent database answers in-flight identity requests and recovers within five seconds

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-10
- **Performed as / preconditions:** Prepared local runtime; alpha/beta Managers authenticated.
- **Steps:** Hold identity_staff lock in rollback transaction; observe two API requests actually waiting. Pause PostgreSQL, inspect in-flight/during-silence responses; resume and release lock in finally. Prove SELECT1 availability and time reads.
- **Expected:** Every in-flight/during outage answer503 within5s, recovery correct within5s after actual availability, API never restarted. Client/control timing marks retained; lock and paused state restored.
- **Automation:** `qa/api/F02/S01-runtime.spec.ts` (registered for feature QA)

### TC-F02-S01-030 nonlocal startup requires configured independent keys and exposes no local identity hooks

- **Type:** build
- **Priority:** high
- **Covers:** AC-10
- **Performed as / preconditions:** Built final API jar, JDK25 and local database; primary product stays running.
- **Steps:** Start separate local-machine API using qa-nonlocal profile,ephemeral port,Flyway disabled and empty keys; check exit/refusal. Supply three independent throwaway keys and check healthy process denies local identity GET/POST/DELETE hooks; terminate only own process.
- **Expected:** Missing keys refuse startup with named setting; configured process healthy, local hooks404, primary product unchanged, no real remote environment touched.
- **Automation:** `qa/checks/F02/S01-nonlocal-secrets.sh` (registered for feature QA)
