# F02-S02 Customer sign-in: draft acceptance cases

Prepared from final S02 spec on the primary checkout (e213c76) and identity policies. No execution, status, evidence, manual result or deferral is claimed. Automation is not registered until its actual files compile against the integrated final contract/handoff.

Local synthetic data only. Reuse shared real API authentication, generate numbers/credentials in memory and reset only synthetic identity data. Public shapes already generated in the primary checkout: `Challenge`, `CodeRequest`, `VerifyRequest1` and nullable `Principal`; source synchronization is still pending. Actual provisional hooks: `/local/identity/captures/{id}`, `/deliveries/{id}`, `PUT/providers/{channel}`, `DELETE/customers`; exact capture/settling behavior will be finalized from the developer handoff.

Types: api, security, tenant-isolation, permissions, concurrency, data-integrity, resilience, build. S04 owns screen/a11y/branding/width tests. S01 retains database down/silent coverage across the feature's same PostgreSQL dependency; S02 does not duplicate those outages. Load is one feature read baseline after S03 adds customer profiles, not another sign-in load scenario. Payment/mobile/remote penetration/regression/smoke remain outside this feature pass.

## Endpoint matrix

POST `/api/v1/identity/customers/challenges` and `/customers/sign-in`; common GET`/me`,`/sessions`,POST`/sessions/refresh`,DELETE`/sessions/{id}`,`/sessions`; local capture/delivery/control/reset paths. Use alpha/beta and missing ids; prove the other salon's record exists before counting zero leaks. Staff-only action matrix covers all five staff roles plus customer and signed-out callers. New public auth routes validate proof rather than trusting preexisting bearer identity.

## Cases

### TC-F02-S02-001 code requests reveal only a challenge and issue a protected server device cookie

- **Type:** api
- **Priority:** high
- **Covers:** AC-1
- **Performed as / preconditions:** Anonymous customer on alpha; fresh synthetic mobile and clean local capture.
- **Steps:** POST /identity/customers/challenges with {mobile,channel,deviceLabel}, once for sms and once for whatsapp on distinct numbers/devices. Inspect body,cookie and eventual local capture/delivery.
- **Expected:** 200 generated Challenge has exactly challengeId/expiresAt, no code/mobile/account existence. salon_device cookie is server-issued, host-only,HttpOnly,SameSite=Lax,Path=/; local HTTP omitsSecure. One encrypted outbox message produces six-digit code on requested channel.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-002 invalid challenge requests create no delivery and name invalid fields

- **Type:** api
- **Priority:** high
- **Covers:** AC-1
- **Performed as / preconditions:** Anonymous alpha requester and baseline delivery count.
- **Steps:** Table-drive blank/invalid/non-mobile number,invalid channel,wrong JSON types,empty/oversized deviceLabel through POST/challenges. Observe persisted/outbox count afterward.
- **Expected:** 400 bad_request names the field without echoing secret input; no extra challenge,message or customer exists. Device label maximum80 per identity policy, including boundary80 accepted.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-003 a correct code signs in a salon-specific customer with bounded credentials

- **Type:** security
- **Priority:** critical
- **Covers:** AC-2
- **Performed as / preconditions:** Fresh mobile,issuing salon_device cookie and successfully captured code.
- **Steps:** POST/customers/sign-in {challengeId,code} with issuing cookie. GET/identity/me on salon hostname and verified bare API. Repeat code flow with same number and fresh accepted challenge.
- **Expected:** Customer kind,null role/staffId,own scope,MFAfalse and documented customer permissions; five-minute access and thirty-day absolute refresh. Repeat verification reuses its same salon customer id without duplicating identity.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-004 the same mobile at two salons creates unrelated customer identities

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-2
- **Performed as / preconditions:** Same synthetic number receives independently captured challenges on alpha and beta; distinct host cookies.
- **Steps:** Verify each in its salon. Prove both principals exist with different ids. Use alpha credentials on beta and reverse; list devices and compare customer records.
- **Expected:** Distinct ids and tenant-bound sessions; mismatched signed salon request404 not_found, no shared profile/customer/session state.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-005 customer authority cannot perform any staff-only action

- **Type:** permissions
- **Priority:** critical
- **Covers:** AC-2
- **Performed as / preconditions:** Authenticated alpha customer plus all five staff-role fixtures and existing staff targets.
- **Steps:** With customer credential call staff list/create/patch/remove,audit,password and voluntary-MFA management. With staff credentials inspect common/me/session responses and attempt to bypass customer verification using token without correct device/code.
- **Expected:** Customer restricted staff actions403 permission_denied or documented kind refusal with no writes. Staff principal cannot become customer merely by an access token; common endpoints expose only the credential's own account kind. Test every staff role as a table,not a fabricated future profile endpoint.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-006 wrong guesses exhaust a challenge durably and cannot issue a session

- **Type:** security
- **Priority:** critical
- **Covers:** AC-3
- **Performed as / preconditions:** One accepted alpha challenge and correct captured code,issuing cookie.
- **Steps:** Submit five incorrect six-digit guesses that exclude the real code; then submit the real code and inspect customer/session counts.
- **Expected:** Wrong guesses fail invalid_code; exhausted correct attempt also refuses, no customer/session created. Failed responses do not roll back the attempt count.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-007 expiry resend and wrong-device verification cannot authorize an old challenge

- **Type:** security
- **Priority:** critical
- **Covers:** AC-3
- **Performed as / preconditions:** Accepted alpha challenge/code and issuing device; separate random/missing/tampered cookie.
- **Steps:** Try same challenge from each wrong-cookie row. Resend for same normalized number and try old code. Advance application clock beyond five-minute expiry of a fresh challenge and submit its correct code.
- **Expected:** No session for wrong device or replaced challenge (invalid_code); expired challenge returns code_expired. Resend invalidates earlier challenges; only current unexpired issuing-device proof can authenticate.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-008 concurrent valid-code reuse creates exactly one customer and session

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-3
- **Performed as / preconditions:** Fresh number with exactly one accepted,captured,unused alpha challenge.
- **Steps:** Release eight correct-code sign-ins simultaneously using issuing cookie. Count successful responses; read principal/devices and persisted identity count. Replay consumed proof.
- **Expected:** Exactly one success,all others invalid_code; one customer and one session exist, no orphaned records; consumed proof never creates another session.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-009 customer challenge and capture routes are isolated from other salons

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-3, AC-7, AC-8
- **Performed as / preconditions:** Beta challenge,code,capture and delivery proven present; alpha has unrelated challenge.
- **Steps:** Use beta challenge id/cookie/code on alpha signin; read beta capture/delivery through alpha hooks; repeat with missing ids. Public requests on reserved,unknown and bare hosts. Read beta original afterward.
- **Expected:** Foreign auth proof refuses without tokens,foreign hook ids indistinguishable404; no beta data revealed/changed. Hostname-required routes on platform/bare hosts404. Lists/SQL also omit foreign customer/challenge rows.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-010 each request limit prevents extra enqueue and resists forged selectors

- **Type:** security
- **Priority:** critical
- **Covers:** AC-4
- **Performed as / preconditions:** Reset synthetic rates; production default number3/device10/address20 per15minutes. For each dimension arrange other dimensions below thresholds using distinct numbers/devices and valid server-issued cookies.
- **Steps:** Submit admitted number/device/address rows through exact limit,then one extra. Forge body deviceId,forwarded headers and random cookies while preserving targeted number/address. Read outbox counts before/after denial and positive Retry-After.
- **Expected:** Excess429 rate_limited before queue mutation,positive Retry-After; accepted count bounded by targeted dimension. Client-chosen selectors never bypass number/address/salon ceilings. Unknown body fields follow strict API validation rather than supplying authority.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-011 simultaneous requests cannot cross the number or salon daily ceiling

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-4
- **Performed as / preconditions:** Number scenario: one fresh number,eight distinct issuing devices,address below20. Salon scenario: lead-controlled local daily ceiling5 with other dimensions nonbinding,production defaults preserved.
- **Steps:** Release eight requests together for each invariant separately; count accepted responses and newly queued messages,inspect durable counters.
- **Expected:** Number admits at most3; salon admits at most5. Every loser429 with Retry-After; queued count exactlyaccepted count,no charged excess work. One race per invariant,no several-size copies.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-012 API restart preserves accepted code counters and issuing-device verification

- **Type:** security
- **Priority:** critical
- **Covers:** AC-4
- **Performed as / preconditions:** Alpha number reaches accepted-request ceiling3; another valid captured challenge and protected server-issued cookie. Prepared final local runtime.
- **Steps:** Restart API through existing script after recording control marks; resend exhausted number and use pre-restart unexpired device/challenge.
- **Expected:** Number still429 before enqueue; server device signature and unexpired proof remain usable with retained keys; restart cannot reset message-cost protection.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-013 the unusual-volume alert is once per salon UTC day and visible only to authorized readers

- **Type:** security
- **Priority:** critical
- **Covers:** AC-5
- **Performed as / preconditions:** Controlled local salon daily ceiling5,other dimensions nonbinding; alpha/beta Owners and lower roles; initial clock shortly before UTC midnight.
- **Steps:** Accept four alpha requests,inspect identity.code_volume_alert and tenant-tagged meter. Accept fifth,deny sixth,inspect queued count. Cross UTC midnight and reach threshold again; inspect beta/role readers and unchanged rolling15-minute limits.
- **Expected:** Exactly one secret-free alert at80% per UTC day and meterincrement; no enqueue fromdenied requests. New UTC day resets daily ceiling only; number/device/address windows still hold. Owner can read own alert,beta never sees it; unauthorized roles cannot read audit.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-014 customer session ownership and principal-kind separation survive renewal and revocation

- **Type:** security
- **Priority:** critical
- **Covers:** AC-6
- **Performed as / preconditions:** Two customers and one staff member in alpha; first customer has two sessions.
- **Steps:** List paginated first-customer devices,current flag; inspect no second/staff row. Revoke own device/all,try foreign customer/staff ids. Advance access expiry,renew live family and absolute30day expiry; one refresh-race row exercises customer family.
- **Expected:** Own pagination/current flag correct; foreign ids404 and unchanged; revoked/expired access fails immediately,renewal rotates once and never extends absolute30days. Replay revokes customer family. Hostname required for opaque refresh,bare verified access allowed.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-015 customer and challenge storage is tenant-bound encrypted and absent from logs and public errors

- **Type:** data-integrity
- **Priority:** critical
- **Covers:** AC-7
- **Performed as / preconditions:** Alpha/beta customer identities,challenges,code attempts,outbox messages and refresh history created through API; beta rows proven present.
- **Steps:** As app database role observe each new customer/challenge/counter type with alpha,beta and missing context. Inspect hash/ciphertext against known mobile/code/refresh. Scan trace-local API log and authorized audit/public errors against supplied sensitive values.
- **Expected:** Forced RLS and absent-context zero,foreign rows/references inaccessible; code/recipient encrypted or irreversibly hashed and refresh hash-only. No full phone/code/token/challenge credential/cookie in logs,audit or public errors. Generated DTOs exclude auth internals except intended public bearer token response. Mechanism layer separation stays developer gate; outside SQL/API independently exercise records.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-016 failed and silent delivery retries the existing request and recovers without duplicates

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** Local provider controls,clock,capture/delivery hooks; independent fresh request on each sms/whatsapp channel.
- **Steps:** Table-drive failure and delay beyond10-second provider limit. Observe retryable delivery; clear control,advance clock to retry due and observe completion. Do not send another public challenge request.
- **Expected:** Failure/silence leaves exactly the accepted request retryable,no second enqueue; caller can see recoverable status; provider recovers through outbox to one logical delivered code,no manual product repair or restart.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-017 an expired challenge is never delivered after provider recovery

- **Type:** security
- **Priority:** critical
- **Covers:** AC-8
- **Performed as / preconditions:** Accepted queued request with provider failure/delay; no successful capture before expiry.
- **Steps:** Advance application clock beyond five minutes,restore provider and cross retry due; inspect capture,message terminal state and correct-code verification.
- **Expected:** No expired code iscaptured/sent; delivery cancels without resurrection; expired verification code_expired, no session.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.

### TC-F02-S02-018 provider controls are local-only and nonlocal unconfigured sending refuses honestly

- **Type:** build
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** Final built API artifact and local-machine isolated nonlocal profile with configured independent keys; local capture proves hooks exist.
- **Steps:** Inspect committed OpenAPI excludes captures/deliveries/providers/customerreset. On separate nonlocal-profile local process try hooks and POST/customer challenge without configured adapter. Terminate only own process.
- **Expected:** Local hooks404 outsidelocal and absentfromcontract; unconfigured sender503 provider_unavailable,never claims a message was sent or enqueues undeliverable paidwork. No real staging/provider touched.
- **Automation:** implementation draft pending source/handoff; no execution or deferral recorded.
