# F02-S04 Web accounts: draft acceptance cases

Expected outcomes come from the final stage specs, feature requirements and read-first documents. Not executed; no manual result, deferral, evidence or pass recorded. Automation is registered as drafts; the final handoff freezes the centralized browser map before execution.

Environment: local only, fixed synthetic alpha/beta/gamma. Credentials are random and kept in memory. Authentication uses real password/TOTP/code APIs and local message capture. Restore the application clock after each use.

## Cases

### TC-F02-S04-001 staff password and authenticator flows reach account only after complete verification

- **Type:** functional
- **Priority:** high
- **Covers:** AC-1
- **Performed as / preconditions:** Staff fixtures include an unenrolled Owner, enrolled Owner and non-Owner at a salon hostname.
- **Steps:** Complete password-only sign-in, required Owner enrollment and TOTP verification, and voluntary staff enrollment from account. Compare the visible account with API principal.
- **Expected:** Only completed verification reaches workspace account. Translated forms clear sensitive values on success; setup key is transient and available only under authorized enrollment.
- **Automation:** `qa/web/F02/S04-functional.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-002 staff authentication refusals preserve useful input and expose recovery

- **Type:** functional
- **Priority:** high
- **Covers:** AC-1
- **Performed as / preconditions:** Staff sign-in and MFA forms with a known identifier, local clock and controlled rate state.
- **Steps:** Exercise wrong password/code, expired challenge and rate limit. Use back and refresh during MFA, then recover when eligible.
- **Expected:** The screen gives translated field errors, expiry and retry instructions. Useful identifier input remains, credentials clear safely, and incomplete MFA cannot open a protected page.
- **Automation:** `qa/web/F02/S04-functional.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-003 customer SMS WhatsApp and resend screens preserve the number and explain proof lifetime

- **Type:** functional
- **Priority:** high
- **Covers:** AC-2
- **Performed as / preconditions:** An anonymous alpha customer, local captures and the public salon shell.
- **Steps:** Browse signed out. Request SMS and WhatsApp, try wrong/expired/resent proof, resend and complete a valid code flow.
- **Expected:** Public browsing remains available. The branded flow explains retry and expiry, retains number and delivery choice on error, and reaches the own account without account-existence messages or a customer password.
- **Automation:** `qa/web/F02/S04-functional.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-004 web permissions protect every page and BFF action for all roles and kinds

- **Type:** permissions
- **Priority:** critical
- **Covers:** AC-3
- **Performed as / preconditions:** All five alpha staff roles, an alpha customer, beta identities and a revoked session; final BFF route handoff.
- **Steps:** Table-drive workspace account/staff/audit and customer account URLs and direct BFF actions. Owner administers staff/grants, Manager reads staff/audit, other roles use own account controls. Include wrong kind, salon, revoked and signed-out rows.
- **Expected:** Server permissions follow the exact role matrix. Typing a URL or invoking BFF cannot bypass a hidden control. Refusals give friendly denial or own sign-in recovery and expose or change no foreign data.
- **Automation:** `qa/web/F02/S04-functional.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-005 web devices password rotation and logout revoke server authority immediately

- **Type:** functional
- **Priority:** high
- **Covers:** AC-4
- **Performed as / preconditions:** Staff and customer have two devices and paginated own device lists.
- **Steps:** Identify current device, revoke another or all, sign in again, change password with old-password proof, and sign out. Verify each visible result through API state.
- **Expected:** Own devices paginate and identify current session. Revocation is immediate, password rotates credentials, and sign-out clears cookies and server authority. Other account/kind devices are unaffected.
- **Automation:** `qa/web/F02/S04-functional.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-006 staff and customer cookies coexist and independent signout preserves the other account

- **Type:** security
- **Priority:** critical
- **Covers:** AC-4
- **Performed as / preconditions:** One alpha browser has both authenticated staff and customer families.
- **Steps:** Open both account surfaces, sign out staff and use customer; sign in staff, sign out customer and use staff. Inspect cookie families and API principal.
- **Expected:** Distinct host-only cookie families coexist. Each sign-out clears/revokes only its own kind, with the other session still valid.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-007 simultaneous tabs coordinate renewal once for each principal kind

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-4
- **Performed as / preconditions:** Two tabs share a browser session for each principal kind; access is expired using the application clock.
- **Steps:** Release protected renewal flows together for staff and customer rows. Observe renewal count and current family afterward.
- **Expected:** The per-kind Web Lock serializes honest renewal and rechecks server state after acquiring the lock. At most one fresh rotation occurs and both tabs recover without accidental replay revocation or extra devices.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-008 replay revocation and unknown refresh outcome cannot restore signed-out authority

- **Type:** security
- **Priority:** critical
- **Covers:** AC-4
- **Performed as / preconditions:** A browser family and a controlled lost or delayed renewal response; a second tab can sign out or delete.
- **Steps:** Exercise replay revocation, a future-expiry 401 and an unknown refresh outcome. Try another protected action; sign out or delete while a response is delayed.
- **Expected:** The affected kind clears cookies and requires fresh sign-in. Spent credentials are never retried and a delayed response cannot restore authority. The other principal kind is unaffected.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-009 unsupported Web Locks disable credential changes with a clear explanation

- **Type:** functional
- **Priority:** high
- **Covers:** AC-4, AC-8
- **Performed as / preconditions:** A new browser context lacks Web Locks; an already authenticated profile is arranged separately.
- **Steps:** Open credential flows and try sign-in, logout, password change, device revocation, renewal and deletion. Use profile read/update and export.
- **Expected:** Credential-changing controls are disabled with a translated support explanation. No unsafe fallback runs; authenticated profile actions and export remain usable.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-010 customer profile screens save every mutable field and recover stale edits

- **Type:** functional
- **Priority:** high
- **Covers:** AC-5
- **Performed as / preconditions:** An alpha account and two tabs that initially read the same profile version.
- **Steps:** Save all mutable fields and preferences, including explicit consent. Save tab A, then submit stale tab B; use refresh and back. Check UI error presentation without repeating S03's API input matrix.
- **Expected:** Verified mobile is read-only, consent is distinct from notifications, and success matches persisted API values. Stale edits show conflict and recovery without silent overwrite or lost useful form input.
- **Automation:** `qa/web/F02/S04-functional.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-011 data download and deletion confirmation clearly complete or cancel the customer request

- **Type:** functional
- **Priority:** high
- **Covers:** AC-6
- **Performed as / preconditions:** Alpha has a customer profile and beta has a separate same-number account; browser downloads are enabled.
- **Steps:** Download JSON and inspect filename/content. Open deletion explanation, cancel and verify unchanged state; then confirm, use another device and read beta.
- **Expected:** The file is the own allowlisted copy. Irreversible salon-specific effects are clear before confirmation. Cancel changes nothing; deletion clears customer authority, returns to salon sign-in and revokes all its devices while beta remains.
- **Automation:** `qa/web/F02/S04-functional.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-012 audit screens expose useful identity alerts without personal or credential values

- **Type:** functional
- **Priority:** high
- **Covers:** AC-6
- **Performed as / preconditions:** Owner and Manager audit has known identity and volume-alert rows.
- **Steps:** Arrange identity and volume-alert entries through actual APIs with a fresh synthetic UTC day and daily preparation ceiling of 5, restored afterwards. Read and paginate audit, inspect actor/action/time and safe role/grant transitions; arrange the empty-list presentation at the browser boundary without removing real audit.
- **Expected:** Authorized readers see useful secret-free audit with salon-zone dates and clear empty guidance. Other salon or personal/credential values are absent; role refusals are covered by the BFF matrix.
- **Automation:** `qa/web/F02/S04-functional.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-013 all browser mutation routes reject CSRF before any side effect

- **Type:** security
- **Priority:** critical
- **Covers:** AC-7
- **Performed as / preconditions:** A final inventory of all BFF mutations and baseline API state/cookies for signed-in and signed-out contexts.
- **Steps:** Table-drive missing/wrong Origin, wrong scheme/port and cross-site Fetch Metadata across sign-in, MFA, codes, logout, password, staff, profile, export, deletion and renewal. Inspect counters, records and cookies afterward.
- **Expected:** Every mutation rejects CSRF before an API side effect or credential issuance, including login CSRF. Forwarded host/protocol cannot redefine trusted Origin.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-014 browser cookies and every client surface exclude session and challenge authority

- **Type:** security
- **Priority:** critical
- **Covers:** AC-7
- **Performed as / preconditions:** Authenticated staff/customer flows plus authorized Owner enrollment; final cookie inventory and synthetic secret values.
- **Steps:** Inspect cookie flags, HTML, client JSON, URLs, browser storage, console and client chunks. Check transient setup instructions separately. Recursively scan actual ignored `.next/standalone` output, including symlinked dependencies, for environment/local-key copies and retained four secret values in text or decoded bytes. Assert a nonempty artifact inventory and report only paths/counts, never secret values.
- **Expected:** Distinct session cookies are host-only HttpOnly SameSite with Path=/ and local HTTP Secure exception. Access/refresh/challenge authority never reaches a readable client surface. Authorized setup instructions remain transient, unlinked and unstored. Real TLS is outside local scope.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-015 redirect and hostname input cannot choose the bearer destination or another salon

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-7
- **Performed as / preconditions:** Alpha and beta browser identities plus controlled redirect, hostname and foreign-id input.
- **Steps:** Try external, protocol-relative and backslash redirect targets, body/query/cookie tenant selectors, forwarded host/protocol and foreign ids. Follow results and read beta afterward.
- **Expected:** Redirects stay fixed and same origin; bearers go only to configured API. Validated Host selects the salon. No credential leaves its origin and no foreign record is revealed or changed.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-016 responsive layouts keep every control reachable across six widths

- **Type:** responsive
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** Final screen inventory with full, empty, error and loading rows; shared presentation states checked once.
- **Steps:** At 360, 390, 768, 1024, 1280 and 1440 inspect staff sign-in/MFA/account/staff/audit and customer sign-in/account/privacy/devices. Use long lists and names; measure overflow, reachable actions and 44-point targets.
- **Expected:** Every distinct screen remains usable with no horizontal overflow, clipping or overlap. Customer phone and workspace tablet paths work; shared components do not create duplicate state tests.
- **Automation:** `qa/web/F02/S04-presentation.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-017 all screens pass automated accessibility checks and announce errors

- **Type:** accessibility
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** Distinct final screens in filled, error and dialog states; all three customer brands.
- **Steps:** Run Axe scans and inspect semantic labels, error associations, announcements and focus-hiding overlays.
- **Expected:** No serious or critical Axe violations; labels, error association and live announcements are present. Manual keyboard/focus checks complement the automated scan.
- **Automation:** `qa/web/F02/S04-presentation.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-018 keyboard journeys have visible focus sensible order and readable contrast

- **Type:** accessibility
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** A headed browser or inspected timed screenshots; no recording of credentials; all affected user personas.
- **Steps:** Manually traverse staff MFA, customer codes/profile/devices/privacy and Owner administration by keyboard, including errors and dialog cancellation. Inspect focus order and text contrast across dark/light brands.
- **Expected:** Every action is keyboard-operable with visible ordered focus and no traps. Contrast and targets meet design standards. Human judgment is required; a performed date is recorded only after actually checking.
- **Automation:** Manual judgment required; declared with performed_on null, not performed or deferred. Final review will record the actual performed date and masked visual evidence.

### TC-F02-S04-019 customer screens use the correct salon brand while workspace stays neutral

- **Type:** branding
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** Published alpha, beta and gamma brand data and each customer account screen.
- **Steps:** Compare customer sign-in/account/privacy/devices with each salon's name, title and theme, including long names and dark/light contrast. Inspect workspace screens for neutral styling.
- **Expected:** Customer screens show only their own correct salon identity with readable text; workspace stays neutral and long names fit.
- **Automation:** `qa/web/F02/S04-presentation.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-020 API outage and slow BFF work preserve form values and present safe retry within budget

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** Prepared local runtime, sign-in/account forms and a dependency control that proves API down and silent behavior.
- **Steps:** Once for each API condition, submit nonsensitive identifier/mobile/profile data, observe retry and request timing across meta plus action, then restore API and retry safely. Record client/server/control marks.
- **Expected:** The original shared three-second upstream cancellation budget spans metadata/mutation without resetting; the existing five-second end-to-end unavailable-response limit holds. Record upstream disconnect, server serialization, network, client and control timing separately rather than equating finish logging with exact3000ms. Useful input remains and translated retry is available. No hung request or unsafe retry of an unknown credential outcome occurs; web recovery requires no restart. PostgreSQL outages are not duplicated.
- **Automation:** `qa/web/F02/S04-resilience-address.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.

### TC-F02-S04-021 translation and test-id inventories cover every new screen state

- **Type:** build
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** Final build/source, locale JSON and a known nonempty screen/control inventory.
- **Steps:** Inspect stable test ids and translation references for every new interactive/result element. Check centralized translation keys and render English states. Verify en/si/ta preferredLanguage persistence in TC-F02-S04-010; the English-first design does not require a global locale chooser in F02.
- **Expected:** No missing strings, unresolved translation keys or missing stable handles. The check proves there were screens/controls to inspect and can fail on a missing contract.
- **Automation:** `qa/web/F02/S04-presentation.spec.ts`; draft uses centralized mapped handles pending final handoff freeze, not executed or deferred.


### TC-F02-S04-022 trusted client address assertions cannot alter tenant authority or evade abuse limits

- **Type:** security
- **Priority:** critical
- **Covers:** AC-7
- **Performed as / preconditions:** Local BFF/API with the final fourth-key assertion contract, real socket peer, empty local trusted-proxy list and controlled synthetic code limits.
- **Steps:** Submit forged forwarded headers and incoming assertions to the BFF. Inspect how real browser callers share or differ in durable address buckets. Test the API's present malformed, bad-HMAC, wrong host/method/path, duplicate/list/IP-format and expired/future assertion rows; verify an absent assertion uses the actual peer. For a valid bounded assertion use the API application Clock and synthetic address, with no bearer or tenant privilege manufactured.
- **Expected:** BFF strips incoming assertion/address authority and signs only the trusted socket-derived canonical values, within its total request budget. API refuses every invalid present assertion before any counter or enqueue side effect. A valid address assertion changes only rate accounting, never principal, permission or salon selection. Forwarded/body address values cannot reset direct-client ceilings.
- **Automation:** `qa/web/F02/S04-resilience-address.spec.ts`; mapped v1 assertion protocol draft pending final handoff freeze, not executed or deferred.
