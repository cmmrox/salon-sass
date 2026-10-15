# F02-S04 Staff web: acceptance cases

Authority: approved CR-010, CR-012 and revised spec. Local synthetic alpha/beta/gamma only; private random credentials stay in memory. Customer React Native UI is deferred until M1-web release; retained customer Java API cases in S02/S03 remain authoritative. Obsolete customer-browser OTP/profile/privacy/coexistence cases are removed, not silently deferred. Unaffected passing API and feature evidence is retained under CR-009.

### TC-F02-S04-001 staff password and authenticator flows reach account only after complete verification

- **Type:** functional
- **Priority:** high
- **Covers:** AC-1
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Seed enrolled/unenrolled Owners and non-Owner; sign in with password and actual required TOTP. Enroll a Receptionist from account.
- **Expected:** Protected workspace appears only after full verification; API principal matches; transient setup vanishes.
- **Automation:** `qa/web/F02/S04-functional.spec.ts`

### TC-F02-S04-002 staff authentication refusals preserve useful input and expose recovery

- **Type:** functional
- **Priority:** high
- **Covers:** AC-1
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Toggle password visibility twice and open/close administrator-contact guidance, checking that neither sends an identity request. Submit wrong password/code, expire a challenge, then reach password attempt ceiling.
- **Expected:** Visibility reflects its accessible pressed/name state; guidance announces contact with the salon administrator without a reset request. Useful identifier survives; password/code clears; translated recovery, visible retry delay and incomplete-challenge denial hold.
- **Automation:** `qa/web/F02/S04-functional.spec.ts`

### TC-F02-S04-003 removed customer browser endpoints return404 without API or cookie side effects

- **Type:** security
- **Priority:** critical
- **Covers:** AC-5
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Keep an API-issued customer credential cookie. Stop API and use a recording stand-in; GET/POST former customer principal/sessions/profile/staff/audit/code/sign-in/logout/revoke/revoke-all/export/delete/refresh/clear/unknown BFF operations. Restore API.
- **Expected:** Every operation returns no-store404, recording stand-in receives zero calls and cookie digest is unchanged. Direct Java customer API principal still works afterward. This new critical boundary prevents accidental customer web delivery or real message requests.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`

### TC-F02-S04-004 web permissions protect every page and BFF action for all roles and kinds

- **Type:** permissions
- **Priority:** critical
- **Covers:** AC-3
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Loop every staff role across account/staff/audit pages and BFF actions; try customer-kind, foreign-salon and revoked credentials. Retain beta profile readback as isolation sentinel. Self-demote a real Owner while another Owner remains.
- **Expected:** Only current permissions permit protected content; wrong authority cannot expose data. Live self-demotion removes stale staff/audit navigation and denies both BFF lists immediately.
- **Automation:** `qa/web/F02/S04-functional.spec.ts`

### TC-F02-S04-005 web devices password rotation and logout revoke server authority immediately

- **Type:** functional
- **Priority:** high
- **Covers:** AC-4
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Revoke another staff device, paginate actual sessions, change password, inspect old credential, then log out.
- **Expected:** Current marker is correct; revoked authority is refused; rotated credentials replace old access; logout revokes immediately.
- **Automation:** `qa/web/F02/S04-functional.spec.ts`

### TC-F02-S04-006 salon web entry and legacy account paths expose only staff access

- **Type:** functional
- **Priority:** high
- **Covers:** AC-2
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Open tenant root with authenticated staff. Open legacy /sign-in and /account. Open protected staff account with only customer API cookies.
- **Expected:** Root reaches staff account; aliases reach staff password sign-in. No customer OTP/profile/canvas renders. Customer cookies cannot authorize staff workspace.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`

### TC-F02-S04-007 simultaneous staff tabs coordinate renewal once

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-4
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Open two staff tabs, advance actual API access expiry and reload simultaneously. Inspect refresh request/history counts.
- **Expected:** One rotation serves both honest tabs, one principal/session remains and clock restores.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`

### TC-F02-S04-008 replay revocation and unknown refresh outcome cannot restore signed-out authority

- **Type:** security
- **Priority:** critical
- **Covers:** AC-4
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Replay spent refresh; drop committed refresh response; race delayed renewal with logout. Preserve unrelated customer API cookies and read direct API authority.
- **Expected:** Revoked access cannot renew, unknown outcome is attempted once and affected staff cookies clear. Logout cannot restore authority. Unrelated API foundations remain intact; customer BFF remains404.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`

### TC-F02-S04-009 unsupported Web Locks disable credential changes with a clear explanation

- **Type:** functional
- **Priority:** high
- **Covers:** AC-4, AC-8
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Disable Web Locks at browser boundary; inspect sign-in and staff security controls.
- **Expected:** Translated browser support explanation appears and credential mutations are disabled.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`

### TC-F02-S04-011 recoverable staff confirmation refusals keep feedback and recovery accessible

- **Type:** functional
- **Priority:** high
- **Covers:** AC-6
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Intercept staff revoke-all with503 and staff removal with409. Confirm action, inspect modal/announced feedback and cancel recovery. Read current principal/target through actual APIs.
- **Expected:** Feedback is accessible inside modal or after dismissal; refused authority and active staff target remain unchanged. No unsafe automatic retry.
- **Automation:** `qa/web/F02/S04-functional.spec.ts`

### TC-F02-S04-012 audit screens expose useful identity alerts without personal or credential values

- **Type:** functional
- **Priority:** high
- **Covers:** AC-6
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Create synthetic identity/volume-alert audit with a fresh UTC day and preparation ceiling5; restore defaults. Read Owner/Manager audit and pagination; arrange empty presentation without deleting audit.
- **Expected:** Useful actor/action/time rows and volume alert remain secret-free, paginated and salon-zoned; empty guidance is present.
- **Automation:** `qa/web/F02/S04-functional.spec.ts`

### TC-F02-S04-013 all browser mutation routes reject CSRF before any side effect

- **Type:** security
- **Priority:** critical
- **Covers:** AC-7
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Across every staff BFF mutation submit missing/wrong Origin, wrong scheme/port, cross-site Fetch Metadata and forged forwarding. Compare database/cookie digests before/after.
- **Expected:** 403 request_rejected precedes all API/cookie side effects, including sign-in. Removed customer routes have the stricter404 boundary in003.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`

### TC-F02-S04-014 browser cookies and every client surface exclude session and challenge authority

- **Type:** security
- **Priority:** critical
- **Covers:** AC-7
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Exercise actual Owner enrollment/sign-in. Inspect host-only cookie flags, HTML/client JSON/storage/URLs/console/chunks and standalone artifact inventory.
- **Expected:** HttpOnly SameSite staff credentials never appear in client-readable surfaces; transient setup is unstored/unlinked; standalone contains no retained secret/key files. Local HTTP Secure exception only.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`

### TC-F02-S04-015 redirect and hostname input cannot choose the bearer destination or another salon

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-7
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Send external/protocol-relative/backslash return targets and forged tenant query/cookie/forwarded fields. Attempt foreign staff removal.
- **Expected:** Navigation and bearer destination remain fixed same origin; validated Host selects tenant and beta profile stays intact.
- **Automation:** `qa/web/F02/S04-browser-security.spec.ts`

### TC-F02-S04-016 responsive layouts keep every control reachable across six widths

- **Type:** responsive
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Inspect four staff routes and enrollment at360/390/768/1024/1280/1440 with long beta salon name. The CR-012 standalone login and MFA states use the complete available width with reachable controls.
- **Expected:** All controls stay reachable with44-point targets and no horizontal scrolling, clipping or overlap.
- **Automation:** `qa/web/F02/S04-presentation.spec.ts`

### TC-F02-S04-017 all screens pass automated accessibility checks and announce errors

- **Type:** accessibility
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Scan staff routes across three salons, error and confirmation dialog states with Axe.
- **Expected:** No serious/critical violations; labels and error announcements remain semantic.
- **Automation:** `qa/web/F02/S04-presentation.spec.ts`

### TC-F02-S04-018 keyboard journeys have visible focus sensible order and readable contrast

- **Type:** accessibility
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Manually traverse the changed CR-012 login on phone/tablet/desktop, including password visibility, administrator guidance, errors and MFA; inspect ordered visible focus and taupe action contrast. Retain unchanged staff account/Owner staff/audit confirmation evidence.
- **Expected:** Every changed login action is keyboard-operable with visible ordered focus and no trap. Prior2026-10-14 unaffected staff confirmation evidence is retained; changed login needs current inspection before pass.
- **Automation:** Manual judgment; actual performed date and evidence in stage.yaml.

### TC-F02-S04-019 staff workspace identifies only its salon and keeps the neutral palette

- **Type:** branding
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Open staff routes for alpha/beta/gamma; inspect the API-resolved login salon name/initial, full protected salon header name, neutral workspace canvas and absence of customer shell.
- **Expected:** Only current salon identity appears. Login name remains readable, including beta long name; protected header retains approved accessible ellipsis. All protected workspaces keep their neutral warm palette.
- **Automation:** `qa/web/F02/S04-presentation.spec.ts`

### TC-F02-S04-020 API outage and slow BFF work preserve form values and present safe retry within budget

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Stop then suspend API while submitting staff password form; retain identifier and inspect bounded503. Use slow metadata plus hanging mutation recording stand-in.
- **Expected:** No hang; useful identifier remains and password clears. Five-second end-to-end unavailable limit and shared three-second upstream budget remain; restored API needs no web restart.
- **Automation:** `qa/web/F02/S04-resilience-address.spec.ts`

### TC-F02-S04-021 translation and test-id inventories cover every new screen state

- **Type:** build
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Inspect nonempty staff component/translation/test-id inventory and rendered staff routes.
- **Expected:** No hardcoded UI text, missing translation or unstable handle; every form control has a stable handle. Removed customer UI strings do not create delivered screens.
- **Automation:** `qa/web/F02/S04-presentation.spec.ts`

### TC-F02-S04-022 trusted client address assertions cannot alter tenant authority or evade abuse limits

- **Type:** security
- **Priority:** critical
- **Covers:** AC-7
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Call retained Java customer API with malformed/HMAC/host/method/path/IP/time assertion variants, one valid assertion and foreign-host forgery.
- **Expected:** Invalid present assertions produce400 without rate/enqueue state changes; valid proof changes only address accounting, never authority. Removed browser customer code-rate exercise is deleted under CR010.
- **Automation:** `qa/web/F02/S04-resilience-address.spec.ts`

### TC-F02-S04-023 staff identity screens follow supplied Salon layout with accessible confirmation overlays

- **Type:** responsive
- **Priority:** high
- **Covers:** AC-9
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Map CR-012 sign-in to the supplied1536x1024 mockup and protected staff screens to the HTML Salon reference. Inspect six widths, loaded Poppins, standalone photo/initial/name/translucent card/welcome/fields/toggle/recovery/taupe action/footer. Compare full-view and focused screenshot pairs manually. Protected screens retain cards/fields/pills, responsive rail/tabs and revoke-all drawer/sheet. Escape/cancel and inspect focus return. Save empty or masked-input screenshots.
- **Expected:** Sign-in matches CR-012 composition at1536x1024 and remains usable across all widths without workspace header/rail. Protected Salon web grammar holds without customer430px canvas/navigation; its header keeps full DOM/title under approved ellipsis. Overlay keyboard behavior and real permission-filtered routes remain correct.
- **Automation:** `qa/web/F02/S04-presentation.spec.ts`
