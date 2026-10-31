# F02-S04 Staff web: acceptance cases

Authority: approved CR-010, CR-012, CR-013 and revised spec. Local synthetic alpha/beta/gamma only; private random credentials stay in memory. Customer React Native UI is deferred until M1-web release; retained customer Java API cases in S02/S03 remain authoritative. Obsolete customer-browser OTP/profile/privacy/coexistence cases are removed, not silently deferred. Unaffected passing API and feature evidence is retained under CR-009.

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
- **Steps:** Manually traverse the changed CR-012 login on phone/tablet/desktop, including password visibility, administrator guidance, errors and MFA; inspect ordered visible focus and the brand-accent action, initial ring, link and field focus for alpha (default `#D6086A`), beta (dark) and gamma (light) (CR-013). Retain unchanged staff account/Owner staff/audit confirmation evidence.
- **Expected:** Every changed login action is keyboard-operable with visible ordered focus and no trap; focus and the brand accent stay readable on dark and light brands. Prior2026-10-14 unaffected staff confirmation evidence is retained; the CR-013 login and workspace accent need current inspection before pass.
- **Automation:** Manual judgment; actual performed date and evidence in stage.yaml.

### TC-F02-S04-019 staff screens identify only their own salon on the shared warm canvas

- **Type:** branding
- **Priority:** high
- **Covers:** AC-8
- **Performed as / preconditions:** Synthetic salon staff or unauthorized browser; committed local build and healthy seeded API before faults.
- **Steps:** Open staff routes for alpha/beta/gamma; inspect the API-resolved login salon name/initial, full protected salon header name, workspace canvas and absence of customer shell.
- **Expected:** Only current salon identity appears. Login name remains readable, including beta long name; protected header retains approved accessible ellipsis. All protected workspaces share the warm `#F3F0EB` canvas; their accent is the salon's brand (CR-013, TC-F02-S04-024), so "workspace stays neutral" no longer holds.
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
- **Steps:** Map CR-012 sign-in to the supplied1536x1024 mockup and protected staff screens to the HTML Salon reference. Inspect six widths, loaded Poppins, shared theme tokens, standalone photo/initial/name/translucent card/welcome/fields/toggle/recovery/action/footer. Accent colours belong to TC-F02-S04-024 (CR-013 retired the CR-012 taupe). Compare full-view and focused screenshot pairs manually. Protected screens retain cards/fields/pills, responsive rail/tabs and revoke-all drawer/sheet. Escape/cancel and inspect focus return. Save empty or masked-input screenshots.
- **Expected:** Sign-in matches CR-012 composition at1536x1024 and remains usable across all widths without workspace header/rail. Protected Salon web grammar holds without customer430px canvas/navigation; its header keeps full DOM/title under approved ellipsis. Overlay keyboard behavior and real permission-filtered routes remain correct.
- **Automation:** `qa/web/F02/S04-presentation.spec.ts`

### TC-F02-S04-024 sign-in and workspace draw every accent in the salon's own brand colour with a readable foreground

- **Type:** branding
- **Priority:** high
- **Covers:** AC-10
- **Performed as / preconditions:** Signed-out browser and a Manager of each synthetic salon; committed local build and healthy seeded API. Rows: alpha `#D6086A` (the default colour), beta `#1F2937` (dark), gamma `#FDE68A` (light).
- **Steps:** For each salon open staff sign-in: read the welcome heading's ink and the resting identifier field's line, the action's background and text colour, the initial's ring, the identifier field's focus accent, the recovery control's text colour, and the keyboard focus outline on the password toggle; scan the sign-in for the retired taupe `#8A705E`. Sign in and open the account at 1280px and 390px: read the selected navigation item (desktop) or its tab tile (phone), the primary action (`password-save`) and a focused password field; scan the workspace's surfaces for the retired black accent `#141413`.
- **Expected:** The sign-in action, desktop selected navigation and workspace primary action are filled with exactly the salon's brand, their text at least 4.5:1 on it. The sign-in heading is ink `#111827` and its resting field line `#DDD6CA`, the workspace's own tokens. The initial ring and focused fields' inset ring draw the brand. The recovery control's text is the brand where the brand reaches 4.5:1 on white (alpha, beta) and ink otherwise (gamma). The keyboard focus outline stays the prototype's `#1D5FD0`. The phone tab tile is the brand's soft tint. No taupe on sign-in and no black accent surface in the workspace, for every salon; the shared tokens (canvas, secondary, lines, ink, muted) are also covered by TC-F02-S04-023.
- **Automation:** `qa/web/F02/S04-theme-entry.spec.ts`

### TC-F02-S04-025 a salon colour change shows on both screens on the next request and an invalid colour falls back to the default accent

- **Type:** branding
- **Priority:** high
- **Covers:** AC-10
- **Performed as / preconditions:** Signed-out browser and a signed-in Manager on alpha; the running web app is never restarted or rebuilt. Part 1 uses the local-only hook `infra/scripts/local-salon.sh colour alpha <#RRGGBB>` in the product checkout and restores alpha's original stored colour (read from `/api/v1/salon` first) even on failure. Part 2 stops the API and puts a stand-in on its port serving alpha's published brand with an invalid `brandColour` (the database cannot hold one), and meta; the API is restarted afterwards.
- **Steps:** Part 1: set `#0E7490` (dark), then `#A7F3D0` (light); after each, open sign-in and the workspace account at 1280px. Restore and read back the original. Part 2: first serve the valid `#0E7490` as the stand-in's control (it must render as itself), then `not-a-colour`, a style-injection value and a missing colour; each time open sign-in, confirming the brand was requested for that page.
- **Expected:** Each stored change appears on both screens on the very next request, as in TC-F02-S04-024. Each invalid or missing colour still renders the sign-in, with `#D6086A` and a readable foreground. Alpha ends on its original colour.
- **Automation:** `qa/web/F02/S04-theme-entry.spec.ts`

### TC-F02-S04-026 entering without a usable session reaches sign-in with no intermediate frame

- **Type:** functional
- **Priority:** high
- **Covers:** AC-11
- **Performed as / preconditions:** Alpha browser with no session cookies, then with the cookies of a Manager whose sessions were all revoked through the API (`/me` answers 401 `invalid_session`). A recorder samples every frame of every document just before paint from navigation start, and records the main-frame document responses. Before the product, the recorder is shown to catch a synthetic page that paints a "Salon" header and loading card before a client redirect, and to pass a server redirect.
- **Steps:** Open `/`, `/workspace`, `/workspace/account`, `/workspace/staff`, `/workspace/audit`, `/account` and `/sign-in` in turn.
- **Expected:** Every hop before `/workspace/sign-in` is a server redirect and only that one document renders. No recorded frame shows `account-loading`, the account loading message, a workspace shell/header/navigation, or any salon name other than "Alpha Studio"; the first non-blank frame is the sign-in with the salon's name.
- **Automation:** `qa/web/F02/S04-theme-entry.spec.ts`

### TC-F02-S04-027 a signed-in member's first painted frame is their workspace with the salon's resolved name

- **Type:** functional
- **Priority:** high
- **Covers:** AC-11
- **Performed as / preconditions:** A signed-in Manager of alpha and of beta (long name); the frame recorder of TC-F02-S04-026.
- **Steps:** Alpha: open `/`, `/workspace`, `/workspace/account`, `/workspace/staff`, `/workspace/audit`. Beta: open `/`.
- **Expected:** Redirect hops are server redirects and only the landing page renders (`/` and `/workspace` land on `/workspace/account`). The first non-blank frame is the workspace with the salon's full name; no frame shows the sign-in, a generic "Salon" or another salon's name, and any loading state appears only inside the workspace already showing the salon's name.
- **Automation:** `qa/web/F02/S04-theme-entry.spec.ts`

### TC-F02-S04-028 text on a mid-tone brand fill stays readable on the sign-in action, primary button and selected navigation

- **Type:** accessibility
- **Priority:** high
- **Covers:** AC-8, AC-10
- **Performed as / preconditions:** Signed-out browser and a signed-in Manager on alpha; the running web app is never restarted. Alpha's stored colour is set with the local-only hook `infra/scripts/local-salon.sh colour alpha <#RRGGBB>` in the product checkout and restored to its original (read from `/api/v1/salon` first) even on failure. Rows: `#808080` and `#C34BB4`, valid brand colours where neither white nor ink `#111827` reaches 4.5:1 (white 3.95 / ink 4.49 for `#808080`; at most 4.21 for `#C34BB4`).
- **Steps:** For each row, open staff sign-in and read the action's background and text colour; open the workspace account at 1280px and read the primary action (`password-save`) and the selected navigation item.
- **Expected:** Each surface is filled with exactly the brand, and its text reaches at least 4.5:1 against it, for every valid brand colour a salon can choose. Every row and surface is measured before the verdict, so a failure names every unreadable pairing. Alpha ends on its original colour.
- **Automation:** `qa/web/F02/S04-theme-entry.spec.ts`


## CR-016 cases (reconciled 2026-10-17)

CR-016 rebuilds the staff screens from shadcn/ui and Tailwind with no intended visual or behaviour change (AC-12, AC-13). TC-F02-S04-001 to -028 keep their meaning and are rerun; their shared design helper reads the prototype's tokens under shadcn/ui's variable names (F01-S03 AC-13), values unchanged. TC-F02-S04-018 is re-performed on the rebuilt screens.

### TC-F02-S04-029 staff screens and the confirmation overlay are shadcn/ui components with the overlay's dismissal and focus behaviour

- **Type:** functional
- **Priority:** high
- **Covers:** AC-12
- **Performed as / preconditions:** A synthetic Owner of alpha signed in through the API, with a Professional in the salon so the staff list carries its grant checkbox; a signed-out browser for sign-in. shadcn/ui components mark their root with `data-slot`.
- **Steps:** Open sign-in, account, staff and audit and list the rendered components and every button, input, select, textarea, table, dialog, checkbox and switch; refuse a sign-in with a wrong password. On the staff list read the grant checkbox. At 390 and 1280 open the sign-out-all confirmation three times and close it by Escape, by the backdrop and by Cancel; each time Tab and Shift+Tab three times, and record when the overlay leaves the document and when focus reaches the opener.
- **Expected:** Sign-in is a Card, Avatar, Label, Input and Button; account Card, Label, Input, Button; staff also NativeSelect and Checkbox; audit Card and Button; every control belongs to a shadcn/ui component, no native checkbox or `<dialog>`; the refusal is the Alert with `role=alert`. The checkbox is named by its label, which is a 44px target. The overlay is the Sheet with its overlay: a bottom sheet at most 90% high on a phone, a 460px full-height right drawer on a desktop; focus starts on Cancel and stays inside however far a keyboard user tabs; Escape, the backdrop and Cancel each close it, nothing is confirmed, and focus returns to the opener (the measured delay is recorded in `CR016-focus-return.json`).
- **Automation:** `qa/web/F02/S04-shadcn-composition.spec.ts`. Seen failing on the pre-CR-016 build for the intended reason (no shadcn/ui components on sign-in): `qa/reports/20261017-CR016-manual/failure-proof-TC029.log`.

### TC-F02-S04-030 every staff screen and state renders as the accepted CR-016 build did, apart from the changes CR-017 lists

- **Type:** responsive
- **Priority:** high
- **Covers:** AC-13
- **Performed as / preconditions:** QA operator; synthetic Owners, Managers and a Professional seeded through the local hook (credentials in memory). The pre-CR-016 product (`fefc4fd`) is built in a scratch worktree outside the repository and served on a second port against the same API; the same sessions and data serve both builds.
- **Steps:** Capture from both builds back to back: sign-in, recovery guidance and refused sign-in, MFA challenge and Owner MFA enrolment (key masked), account with devices, staff with Owner controls, audit and the confirmation overlay, for alpha, beta and gamma at the six widths (refusal and MFA states at 390 and 1280). Compare pixel by pixel, plus test-id sets, visible text and overflow.
- **Expected:** Pairs pixel-identical except what AC-12 requires (the shadcn/ui Checkbox in place of the native box) and data that differs between captures; same test ids, translations and states; no overflow. Each difference is classified.
- **Manual:** performed 2026-10-17; a one-time migration judgment that needs a second build of an earlier revision. Evidence `qa/reports/20261017-CR016-manual/`.

## CR-017 cases (reconciled 2026-10-18)

CR-017 (AC-14) replaces the role `<select>` with the shadcn/ui Select, and adds Field, Input Group, Input OTP, Item, Badge, Empty, Pagination, Alert, Spinner and Separator. Updated, with expected results unchanged in meaning: roles are chosen by opening the Select trigger and choosing the option (`account-role-option-<role>`), never `selectOption` (TC-F02-001, TC-F02-S04-004); the password field's surface and focus accent are its Input Group (TC-F02-S04-023, -024, -025); Radix's aria-hidden form select is not a control and a check box's target is its own label (TC-F02-S04-016, -021, -029); TC-F02-S04-029 expects the widened component map's slots; the header is exactly 60px from 900px because the fixed rail starts there (TC-F02-S04-023). TC-F02-S04-030 is re-performed against the accepted CR-016 build: every difference must be one CR-017 lists. TC-F02-S04-018 is re-performed.

### TC-F02-S04-031 sign-in and account screens use the shadcn/ui component for every job, operable by keyboard and screen reader

- **Type:** functional
- **Priority:** high
- **Covers:** AC-14
- **Performed as / preconditions:** Synthetic beta Manager (wrong password), Owner with MFA, Owner and Professional; an alpha Receptionist; signed out and signed in through the API.
- **Steps:** Sign-in: read the identifier and password fields, Tab to the visibility toggle and press Enter twice; submit a wrong password while the answer is briefly held. MFA challenge: read the code field, type `12ab34` then `5678`. Account: read both password fields and their toggles, the role, the device list and pager, the MFA notice. Staff: read the list, row badges, Separator and the Professional grant; on the create form's role Select press Enter, read the list and options, press Escape; press Enter, ArrowDown, Enter. As the Receptionist open the staff page.
- **Expected:** Identifier an Input in a Field; password an Input Group control in a Field, its toggle inside the group with `aria-pressed`, the 3px `#1D5FD0` outline, Enter switching the type. A busy sign-in shows the Spinner in the button (`aria-busy`), the refusal is an Alert with `role="alert"`. The code is an Input OTP of six slots, named, digits only, at most six. Account toggles named and pressed-state aware; the role a Badge; devices Items in an ItemGroup (`listitem`) with the current-device Badge; the pager a Pagination named "Pages" with the current page marked; MFA enabled an Alert. Staff rows Items with Badges; the Separator; the grant a named Checkbox. The role Select is a combobox named "Role" with the focus outline; Enter opens a list of at least five named options at least 44px tall; Escape closes it unchanged with focus back on the trigger; Enter, ArrowDown, Enter chooses the next option, closes the list, returns focus and the trigger shows the choice. No visible native select. The Receptionist gets the denied Alert (`role="alert"`) and no rows.
- **Automation:** `qa/web/F02/S04-component-map.spec.ts`. Seen failing on the CR-016 build for the intended reason (sign-in not built from Field): `qa/reports/20261018-CR017-manual/failure-proof.log`.

### TC-F02-S04-032 every sign-in and account screen and state uses the shadcn/ui component the web.md map names for each job

- **Type:** functional
- **Priority:** high
- **Covers:** AC-14
- **Performed as / preconditions:** QA operator, at the owner's request; rendered states of the CR-017 product at six widths for three brands.
- **Steps:** For every sign-in and account state, list each element by job and the shadcn/ui component it renders (`data-slot`), and any control not inside a shadcn/ui component; compare with `docs/engineering/web.md`, "Styling", rule 1.
- **Expected:** Each job uses the component the map names; no native or hand-built control and no Native Select; plain HTML only where the map allows (headings, plain-text lists, the setup key and URI outputs).
- **Manual:** performed 2026-10-18; judging an element's job against the map needs a person. Evidence `qa/reports/20261018-CR017-manual/pairs/facts.json`.

## CR-023 update (2026-11-01)

CR-023 (decision 070) adopts the expanded design prototype, which keeps CR-010's Salon-first presentation and adds a third design view. TC-F02-S04-023's prototype check now expects three buttons in the "Design reference platform" group, in the order Customer mobile design (later), Salon web, Platform console. At 360, 768 and 1440px: Salon web is pressed and shown by default with Customer and Platform console not pressed; the deferred React Native note is kept; no pair of the three buttons overlaps; each lies inside the viewport with no clipped text; the identity does not overlap the group; Platform console can be chosen (its view shows) and Salon web chosen again; the Customer switch behaves as before. Its other expected results are unchanged.
