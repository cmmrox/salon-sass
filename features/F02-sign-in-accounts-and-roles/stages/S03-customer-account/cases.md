# F02-S03 Customer profile and rights: acceptance cases

Expected outcomes come from the final specs, feature requirements and Read first documents. The feature metadata and recorded runner reports hold execution status; stage QA status remains unchanged during whole-feature verification.

Environment: local only, fixed synthetic alpha/beta/gamma. Credentials are random and kept in memory. Authentication uses real password/TOTP/code APIs and local message capture. Restore the application clock after each use.

## Cases

### TC-F02-S03-001 a new customer reads its own profile with explicit safe defaults

- **Type:** api
- **Priority:** high
- **Covers:** AC-1
- **Performed as / preconditions:** Fresh customers at alpha and beta, signed in through real code verification.
- **Steps:** Read /customers/me and compare its id and verified mobile with the current customer principal.
- **Expected:** The response is the current customer only: empty name, language en, null email and birthday, all three operational notification channels true, marketing consent false, and an explicit version.
- **Automation:** `qa/api/F02/S03-customer-profile.spec.ts` (registered for feature QA).

### TC-F02-S03-002 each privacy endpoint rejects foreign salon and staff authority

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-1, AC-4, AC-5
- **Performed as / preconditions:** Two different alpha customers, the same mobile at beta, and one staff account per role. All existing profiles are proven readable by their owner.
- **Steps:** Table-drive GET, PUT, DELETE /customers/me and GET /customers/me/export using mismatched salon tokens, forged or absent tokens, all staff roles, and injected customer or tenant selectors. Read the rightful profiles afterward.
- **Expected:** Foreign salon credentials return 404 not_found; forged or absent access returns 401 invalid_session; staff access returns 401 invalid_session as wrong-kind authentication. No other customer profile, export or credential is exposed or changed.
- **Automation:** `qa/api/F02/S03-customer-profile.spec.ts` (registered for feature QA).

### TC-F02-S03-003 complete mutable profile updates normalize persist and clear optional fields

- **Type:** api
- **Priority:** high
- **Covers:** AC-2
- **Performed as / preconditions:** A signed-in alpha customer with a current profile version.
- **Steps:** Save the full mutable profile with padded name, mixed-case padded email, birthday, each supported language, channel preferences and explicit consent. Read it back. Then omit or null the optional email and birthday, and use blank optional email.
- **Expected:** The name is trimmed, email trimmed and lowercased, version increases, and chosen preferences persist. Verified mobile never changes. Omitted or null optional fields clear; blank optional email also clears. Channel preference does not imply marketing consent.
- **Automation:** `qa/api/F02/S03-customer-profile.spec.ts` (registered for feature QA).

### TC-F02-S03-004 invalid or injected profile fields leave saved data unchanged

- **Type:** api
- **Priority:** high
- **Covers:** AC-2
- **Performed as / preconditions:** A saved alpha profile, its current version, and a fixed application clock.
- **Steps:** Table-drive blank or overlong names, invalid email, future or malformed birthday, unsupported language, wrong JSON types, missing required preferences or consent, and unknown mobile/id/tenant fields. Read the profile after each refusal; exercise accepted name boundaries of 1 and 120 code points.
- **Expected:** Invalid updates return 400 bad_request with named fields and no echoed personal values. The saved profile and version remain unchanged. Accepted boundary values persist.
- **Automation:** `qa/api/F02/S03-customer-profile.spec.ts` (registered for feature QA).

### TC-F02-S03-005 birthday future validation follows salon local date across midnight

- **Type:** data-integrity
- **Priority:** high
- **Covers:** AC-2
- **Performed as / preconditions:** Alpha uses Asia/Colombo and gamma uses Europe/London; each has a signed-in customer.
- **Steps:** Set the application clock to a UTC evening where those salons have different local dates. Submit each salon's current date and next date as birthday. Read the profile and restore the clock.
- **Expected:** The current salon-local date is accepted and tomorrow is refused with a birthday field error. Validation does not use the host machine date or one shared UTC date.
- **Automation:** `qa/api/F02/S03-customer-profile.spec.ts` (registered for feature QA).

### TC-F02-S03-006 stale and concurrent edits preserve exactly one accepted version

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-3
- **Performed as / preconditions:** One alpha customer; two different valid full profile payloads use the same current version.
- **Steps:** Release both PUT requests together. Count outcomes, read the final profile and version, and replay the original stale version.
- **Expected:** Exactly one request succeeds and one returns 409 conflict. The complete saved profile matches the successful response, its version increments once, and stale replay changes nothing.
- **Automation:** `qa/api/F02/S03-customer-profile.spec.ts` (registered for feature QA).

### TC-F02-S03-007 concurrent salon profile reads cannot carry a foreign customer

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-3
- **Performed as / preconditions:** Distinct named alpha and beta customer profiles, each proven readable.
- **Steps:** Release alternating own-profile reads and exports with matching salon credentials; compare ids, verified mobiles, profile values and device metadata.
- **Expected:** Every response belongs to its own customer and salon. No pooled request context crosses. Application-role SQL already covers the profile record; the developer gate separately proves the two persistence mechanisms.
- **Automation:** `qa/api/F02/S03-customer-profile.spec.ts` (registered for feature QA).

### TC-F02-S03-008 an exported JSON copy contains only the customer allowlist and records audit

- **Type:** security
- **Priority:** critical
- **Covers:** AC-4
- **Performed as / preconditions:** An alpha customer has a completed profile and two devices; a second alpha customer and a beta customer exist. An Owner can read audit.
- **Steps:** Request /customers/me/export, inspect the JSON allowlist, download filename, content type, no-store header and generated time. Compare profile and own device ids, then inspect the new subject audit entry.
- **Expected:** The file contains only the requesting customer's profile, preferences and device metadata. No credentials, hashes, MFA, challenge, code, audit internals or other person appear. The export records an opaque-subject rights audit event.
- **Automation:** `qa/api/F02/S03-customer-profile.spec.ts` (registered for feature QA).

### TC-F02-S03-009 invalid deletion confirmation changes no personal data or credential

- **Type:** security
- **Priority:** critical
- **Covers:** AC-5
- **Performed as / preconditions:** An authenticated alpha customer with a saved profile, live devices and associated challenge material.
- **Steps:** Delete with false, absent, string or null confirmation and an unknown selector. After every refusal read profile/devices and use the existing access credential.
- **Expected:** False confirmation returns 400 confirmation_required; malformed or unknown fields return 400 bad_request. Each names its field; data and credentials remain unchanged and no deletion event is recorded.
- **Automation:** `qa/api/F02/S03-customer-profile.spec.ts` (registered for feature QA).

### TC-F02-S03-010 confirmed deletion erases identity material while preserving other salon and abuse counters

- **Type:** data-integrity
- **Priority:** critical
- **Covers:** AC-5
- **Performed as / preconditions:** Alpha and beta have separate customers for the same number. Alpha has a completed profile, two devices, refresh history, capture/challenge material and a known rate-counter baseline.
- **Steps:** Confirm alpha deletion. Try all old access and refresh credentials, inspect owned profile/session/history/challenge/outbox/capture material, compare hashed rate rows, read beta and request another code at the exhausted number ceiling.
- **Expected:** Deletion returns 204 and erases identifying and credential material atomically. Old sessions fail immediately, beta is unchanged and local captures are absent. Ordinary hashed abuse counters survive, so the old ceiling still returns 429. The retained salon_device marker grants no account access.
- **Automation:** `qa/api/F02/S03-customer-profile.spec.ts` (registered for feature QA).

### TC-F02-S03-011 reverification after deletion creates a fresh customer without old profile

- **Type:** security
- **Priority:** critical
- **Covers:** AC-5
- **Performed as / preconditions:** An alpha customer has been deleted; its old id and saved profile are known. The ordinary code window allows a new request.
- **Steps:** Verify a fresh code for the same number using the retained device marker; inspect its profile, devices and export, and read beta's same-number account.
- **Expected:** The new opaque id has fresh defaults and only a new session. No old profile or device history returns; beta retains its original account.
- **Automation:** `qa/api/F02/S03-customer-profile.spec.ts` (registered for feature QA).

### TC-F02-S03-012 deletion during queued or in-flight delivery never resurrects personal state

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-5
- **Performed as / preconditions:** An authenticated alpha customer has a same-number challenge queued or sending under a controllable provider delay; beta is unaffected.
- **Steps:** For queued and in-flight rows, wait for the observable outbox state, confirm deletion, then release the provider or advance time. Inspect capture, message material, old credentials and customer identity.
- **Expected:** No delivery after deletion captures or resurrects identity, challenge, message or session state. Pending work is cancelled or removed transactionally, old captures are cleared and beta remains unchanged.
- **Automation:** `qa/api/F02/S03-customer-profile.spec.ts` (registered for feature QA).

### TC-F02-S03-013 deletion racing refresh or verification leaves no authority from the deleted identity

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-5
- **Performed as / preconditions:** An alpha customer has a current access/refresh family and a valid unused same-number challenge.
- **Steps:** Independently release confirmed deletion with refresh, and deletion with verification. Record each response and the final ordering. If refresh has already invalidated the supplied DELETE access generation and DELETE returns 401, verify the successful refresh, old access denial and intact account; then confirm deletion with the renewed credential. Inspect all credentials, owned material and pre-deletion challenge afterward.
- **Expected:** A 401 deletion is recorded as refused, never as successful erasure. Whenever deletion returns 204, all overlapping credentials fail and identifying/session/history/challenge/message material is erased. Two successful response statuses are acceptable only if those final deletion postconditions hold. A pre-deletion challenge cannot authorize fresh registration after erasure; registration needs a newly requested challenge. No old data or authority survives a committed deletion.
- **Automation:** `qa/api/F02/S03-customer-profile.spec.ts` (registered for feature QA).

### TC-F02-S03-014 profile export and deletion audit retain only opaque nonsecret rights metadata

- **Type:** security
- **Priority:** critical
- **Covers:** AC-6
- **Performed as / preconditions:** An alpha profile has distinct name, email, birthday and mobile values. Owner and Manager audit readers exist.
- **Steps:** Update, export and delete the profile; inspect subject audit after deletion, including actor, time, changed field names and versions. Attempt an audit update inside a rolled-back transaction.
- **Expected:** Immutable rights entries retain only opaque actor/subject, action/time and safe field-name/version metadata. Deleted personal values and credentials never appear. Owner/Manager can read, lower roles cannot, and API errors remain stable safe problem responses.
- **Automation:** `qa/api/F02/S03-customer-profile.spec.ts` (registered for feature QA).
