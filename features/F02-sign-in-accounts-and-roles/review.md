# F02 Sign-in, accounts and roles: product review

## Current CR-010 independent scoped review (2026-10-15)

The web application now serves salon administrators and relevant staff only. Tenant root and legacy account links reach staff access; customer OTP/profile/privacy screens are absent. Every former customer BFF GET/POST returns no-store404 with zero upstream API calls and unchanged cookies, including when the API is unavailable. The Customer HTML preview is explicitly a later React Native design; Salon web is the default. Mobile implementation waits until M1-web is released.

Final tested source: `3f6ec78b25d3445fc3fe6f4c31247c748c61e506`. F01 scoped report `qa/reports/20261015-095342-F01-delta-bf8315318646` passed3/3groups (12shell callbacks, one cross-stage journey and300concurrent entry iterations, zero wrong salons/errors). F02 scoped report `qa/reports/20261015-095550-F02-delta-76392eb5c4d7` passed5/5groups/24callbacks. Combined37Playwright callbacks plus the300iteration scenario, distinct from8automation groups. Final developer-command report `qa/reports/20261015-094736-F01-commands-cba54c3ab5ea` passed10/10. Original full F01/F02 and PR5two-fix evidence were independently checked intact and retained; this is base-plus-delta verification, not a new full feature pass. Exact path impact, source/log digests and limits are in `features/F02-sign-in-accounts-and-roles/scoped-qa.json`.

Chosen types: functional staff journeys; tenant isolation/permissions and security for every authority boundary; concurrency for staff tabs and shared tenant routing; resilience for API down/silent/shared-budget behavior; responsive/accessibility/branding/build for the changed staff shell and prototype. Unchanged Java customer identity/privacy API, data integrity/provider/load and foundation evidence is retained. No payment or native mobile feature exists in this repair; no regression/smoke or remote work was requested.

Cases were migrated under approved CR010: removed obsolete customer browser OTP/profile/privacy/coexistence journeys; retained S02/S03 Java customer API cases; repurposed S04-003 to zero-upstream customer boundary and S04-006 to staff-only aliases. Current automated scope contains every changed criterion. Original S04-018 staff manual evidence is retained only where unaffected; current human judgment is separate in `qa/reports/CR010-final-manual/judgment-observations.json`.

Manual review used actual keyboard password/Tab/Enter/navigation/logout and confirmation cancellation at360/768/1440, with19focus observations (3px solid outlines), six current staff screenshots, final prototype screenshots and current reference-mapped staff/confirmation frames. Inspected current768staff entry,360staff account,1440long-name workspace and360/1440prototype. Warm Poppins/cards/pills and desktop rail/compact bottom tabs match Salon reference; no customer canvas/navigation appears. The compact prototype label overlap is repaired. Comparison is with actual locally rendered HTML and source contract; no competitor refresh or new mobile traversal is claimed. Existing competitor findings below remain historical.

Bugs found: BUG-F02-008 major (proxy reached tenant API before rejecting customer BFF) and BUG-F02-009 minor (prototype logo covered the mobile label at360px); both independently verified after fixes. Original diagnostic report `qa/reports/20261015-094132-F02-delta-19fef4dae9b3` remains a failed report, never closure evidence. Final shared proxy changed after diagnosis, so all8affected browser groups were legitimately reverified on final source; unchanged API suites were not repeated.

Environment/test repairs: preserved persistent service sessions and private keys. An earlier documented reset completed API/database but refused an unmanaged existing web port; current build/health was checked and retained. Final documented reset exited0, rebuilt/restarted exact source, retained key bytes/mode and every stack volume, and removed only synthetic local data before F02 clock checks. Fixture evidence: `qa/reports/CR010-final-fixture-refresh.json`. Repaired test-only stale streamed redirect observations and an obsolete removed staff/profile row; no retries, thresholds or authority assertions weakened. Manual harness404preparation and duplicate hidden streamed DOM observations were corrected without product changes.

Ranked suggestions remain proposals: F02role capability preview and authenticator timing copy (should, CR006); F01operator diagnostics (should, CR004), oversized exceptional status heading (idea/backlog). No new suggestion was adopted as a requirement here. F01real hosting/TLS case remains deferred to explicit staging authorization; F02has no deferred case. Local repair QA passes; owner PR merge and release/deployment remain separate.

## Historical review before CR-010

The following review describes the superseded customer-web interpretation and original evidence. It is retained for provenance and does not describe current delivered web scope.

Reviewed again on 2026-10-15 as a customer on 360/390px phones, a receptionist on a 768px tablet, a Professional between clients, and an Owner provisioning access. The current full F02 pass contains 89 declared cases: 88 automated and the preserved manual TC-F02-S04-018 performed on 2026-10-14 against unchanged presentation source. That manual evidence records 38 keyboard controls, 27 masked viewport captures and two native dialog details; combined with nine F01 status frames the shared manual review inspected 38 images. Original pre-repair manual observations below remain historical where explicitly identified.

Compared with public primary documentation from Fresha, Booksy and Vagaro, refreshed 2026-10-14. No competitor account or real personal data was used.

## Verdict

The repaired account screens follow the supplied Customer and Salon design grammar, including responsive phone sheets and workspace desktop drawers. Authentication, every-role access, devices, profile, privacy and recovery passed the complete independent F02 run `qa/reports/20261015-000506-F02-3e38935bf776` on `e73d99b0bbb38ec4db6b9cc837ea635286a1fed0`: all 13 registered groups, zero failures, 696.493 seconds. All current required criteria are covered; no blocking F02 bug remains. Two optional explanation proposals remain for the owner.

## Current verification and evidence

The test types remain API/security, tenant isolation/permissions, one-use/concurrent credentials and edits, data integrity/build, local dependency resilience, functional journeys, six-width responsive/branding/accessibility, and one bounded local read baseline. These protect the actual identity and personal-data harms; no new case was added during this continuity pass. Payment, native mobile, regression, smoke, remote deployment, real providers and penetration testing were not run. F02 has no deferred case; local SMS/WhatsApp capture certifies the delivery integration behavior, without certifying real sending.

All six developer commands passed in `qa/reports/20261015-000038-F02-commands-16ecb2ae3ce2`. Root prepared a fresh synthetic local reset after closing F01, preserving private keys and every historical stack volume, then rebuilt/restarted the exact source before QA. This removes F01's future scheduler occurrences without weakening the F02 clock guard. During and after the full pass the source remained clean, declared configuration bytes and modes stayed identical, all 13 log digests and the local integrity receipt remained intact, and local metadata/health reported the tested SHA/UP with the real clock. The credit interruption did not interrupt the runner: it completed, so no duplicate run was started. No new environment fault or product defect occurred in this run.

The ten-user/20-second read baseline used 50 synthetic customers and five staff roles per salon, with retained audit counts Alpha 737, Beta 220 and Gamma 171. Temporary preparation address ceiling 200 was restored to defaults 3/10/20/1000 before measurement. It completed 20,324 HTTP requests with zero failures: principal p95 16.35 ms, devices 17.18 ms, profile 14.91 ms, staff 33.33 ms and audit 33.75 ms, each below the unchanged 300 ms bound. Semantic tenant/allowlist checks all passed. This is a local Apple M4/16 GiB baseline, not production-scale certification.

Reference mapping: Customer compact header/circular identity, 430px canvas, anchored icon tabs, white 24px cards, 20px fields and pill actions; Salon neutral full-width header, 200/236px responsive rail, phone/tablet tabs and reference form/list grammar; confirmations inherit Customer bottom sheets and Salon 460px desktop drawers. Current `design/` captures cover the six implemented routes at 360/390/768/1024/1280/1440 plus customer/staff confirmations, and automation verifies loaded Poppins 400/500/600, exact source-defined tokens/geometry, contrast and accessibility. Independent review inspected current customer 360, workspace 1440 and both confirmation frames; the known background-password screenshot mask overlaps desktop dialog buttons, so the preserved native dialog-detail frame was also inspected. Actual controls and keyboard Cancel focus/containment/return are proven by automation and the preserved performed manual case. The supplied reference preview was refused by browser policy; this is source-defined comparison with rendered product evidence, without a claimed side-by-side reference preview.

All five earlier major F02 defects remain verified and their regression cases passed again: BUG-F02-001 audit schema, BUG-F02-002 privacy metadata, BUG-F02-003 lost-renewal recovery, BUG-F02-004 stable handles and BUG-F02-005 optional staff identifiers. The case definitions and manual inventory were not changed after the full pass.

## What works well

- The customer's own salon remains clear, including Beta's long dark-brand name and Gamma's light brand. Staff workspaces stay neutral.
- Error messages preserve the identifier, clear passwords and provide a next step. Code screens explain resend and expiry; a slow request disables the action with visible feedback.
- Separate staff/customer sessions, immediate device revocation and explicit salon-specific deletion consequences give useful control without exposing credentials.
- Keyboard targets inspected in the actual traversal had 3px solid focus indicators. Automated responsive/Axe checks covered all six required widths and the three brands; masked screenshots were inspected for readability and layout.

## Observed usability assessment

| Dimension | Assessment | Actual evidence |
| --- | --- | --- |
| Effort and fit | Good | Real keyboard staff/customer flows, WhatsApp choice, saved Sinhala preference and consent; readable 360/390px phones and 768px tablet |
| Clarity and confidence | Good with proposed improvements | Explicit success/error feedback and salon-specific irreversible consequences; role preview and authenticator timing would help first-time users |
| Recovery | Good | Incorrect-password correction, cancellation, stale edit handling, revocation and both-kind lost-renewal recovery work |
| Speed as felt | Good | Timed pending request disables its action and gives visible feedback before the settled code screen |
| Confirmation placement | Good after CR-007 | Native modal opens a phone bottom sheet or desktop drawer, starts on Cancel, contains Tab/Shift+Tab, closes with Escape/cancel and returns focus |

## Suggestions

Ranked original proposals. CR-007 addressed confirmation placement under the owner's design correction; the role-preview and authenticator-copy proposals remain in CR-006. The lead fills Disposition.

| Id | Class | Suggestion | Disposition |
| --- | --- | --- | --- |
| SUG-F02-01 | should | Place and focus confirmation near the action that opened it | CR-007 implemented |
| SUG-F02-02 | should | Preview the fixed role's capabilities before an Owner creates or changes staff access | CR-006 |
| SUG-F02-03 | should | Distinguish authenticator code rotation from verification-session expiry in the instructions | CR-006 |

### SUG-F02-01 Confirmation near the current action

Current verification: addressed by approved CR-007. The current sheet/drawer receives Cancel focus, contains keyboard traversal and returns focus after Escape/cancel. TC-F02-S04-023 and the performed manual case passed. The original observation below is preserved as the reason for the proposal.

- **Observed:** The small-phone customer page is roughly 2500px tall. Delete is at the bottom; activating it inserts the confirmation above permissions/profile while focus remains on Delete. All controls remain reachable by keyboard; reaching Cancel took seven further Tab presses around the page. The clear explanation is visible when that earlier section is inspected.
- **Why it matters:** A customer acting one-handed may initially miss the confirmation and repeat the tap or assume nothing happened.
- **Instead:** Move focus to a nearby confirmation region, bring it into view, and return focus to the originating control on cancellation. Preserve the existing clear salon-specific consequences and cancellation behavior.
- **Evidence:** `qa/reports/20261014-F02-manual-complete/manual/phone-delete-confirmation.png` and `keyboard-timing.json`. [Vagaro's customer closure guidance](https://support.vagaro.com/hc/en-us/articles/1260806204150-Close-Your-Vagaro-Account-for-Customers-of-a-Vagaro-Business) uses a specific account-management confirmation sequence. Later booking/payment prerequisites are outside F02.

### SUG-F02-02 Explain the fixed role before saving it

- **Observed:** Owner creation presents a role dropdown. The individual account screen explains current scope and permissions, and the Professional grant is clearly worded, but the creation dropdown provides no concise capability preview.
- **Why it matters:** An Owner deciding between Receptionist, Professional and Accountant must know the effect before granting access; a busy front desk benefits from plain capability descriptions.
- **Instead:** Show a short read-only summary of the selected fixed role's allowed areas and own/salon scope. Keep the current Owner-only management rule; editable custom roles are not proposed here.
- **Evidence:** `qa/reports/20261014-F02-manual-complete/manual/tablet-owner-staff.png`. [Fresha](https://www.fresha.com/help-center/knowledge-base/team/100687-create-permission-roles) groups permissions by business areas and shows role access summaries. [Booksy](https://support.booksy.com/hc/en-gb/articles/16535688940946-How-do-I-manage-staff-permissions) explains the capabilities of its five levels. Their broader Manager powers do not change this product's policy.

### SUG-F02-03 Clarify authenticator timing

- **Observed:** The authenticator screen reuses the customer wording that a code expires after five minutes and a new code replaces the previous one. The verification challenge lasts five minutes, while authenticator codes rotate on 30-second steps with the documented clock allowance. Entry itself worked; this is a wording proposal, with no acceptance failure.
- **Why it matters:** A first-time Owner may confuse the time available to complete verification with the life of a displayed authenticator code.
- **Instead:** Explain that the verification session expires at the shown time and ask for the code currently displayed by the authenticator app. Keep customer SMS/WhatsApp expiry and resend wording separate.
- **Evidence:** `qa/reports/20261014-F02-manual-complete/manual/tablet-authenticator.png`. [Vagaro verification guidance](https://support.vagaro.com/hc/en-us/articles/115005413687-Two-Factor-or-Two-Step-Authentication) distinguishes verification steps and completion. This wording proposal adds no new code lifetime or authentication mechanism.

## Compared with other products

| Aspect | Ours observed | Primary documented comparison | Fit for these users |
| --- | --- | --- | --- |
| Permissions | Fixed roles, live own/salon scope and a clear Professional grant | Fresha area summaries; Booksy explicit role capability descriptions | Safe fixed policy; their explanation before selection would help an Owner |
| Verification | Password/authenticator for staff; SMS/WhatsApp for customers; expiry/resend feedback | Vagaro documents code verification and completion | WhatsApp choice suits the local context; authenticator timing copy can be clearer |
| Privacy | Own JSON download and explicit synchronous salon-specific account erasure | Vagaro profile/account-management deletion and further information-erasure guidance | Direct data control is useful; improve confirmation placement without adding later-feature restrictions |

## Bugs filed during verification and review

BUG-F02-001 audit nullability,002 privacy audit metadata, BUG-F02-003 lost-renewal sign-in recovery, BUG-F02-004 required test-id naming, and BUG-F02-005 optional staff identifier nullability; all major. Their exact states and verification evidence remain in the bug files.005 was found by the actual keyboard Owner path and independently reproduced through both native API identifier variants. Its extended S01-019 creation/list/schema/real-sign-in checks and fresh assertion-failure witness passed after the fix. The completed manual Owner path also passed. All five major bugs are now independently verified on the final complete pass, with evidence in their files and `qa/reports/20261014-115732-F02`. No required defect remains open.

## PR5 review follow-up (2026-10-15)

The earlier complete pass remains historical evidence, but independent bounded browser reproduction confirmed two missed existing-criterion defects: BUG-F02-006 (major), failure feedback hidden behind an open confirmation after three recoverable refusal variations; BUG-F02-007 (major), live self-demotion updates authorization correctly but leaves stale staff/audit navigation links. F02 is reopened with qa_status failing. Existing S04 cases 011/004 now include these variations in their registered functional file. No future capability or visual redesign is proposed; full feature QA waits for committed fixes and prepared frozen source.

## Owner-directed proportional verification after PR5 fixes (2026-10-15)

The owner explicitly directed: do not repeat QA for unchanged files; reuse valid prior results. The actual product diff from the previous complete F02 source `e73d99b0bbb38ec4db6b9cc837ea635286a1fed0` to fixed runtime source `0b3f1d91315705727d76087b19bb57120a086629` contains only `AccountScreen.tsx`, `Confirmation.tsx` and new developer `AccountRecovery.test.tsx`. The changed production components are imported through AccountPage and do not enter F01's status, plain error or public salon foundation routes. Tenant/backend/stack code and F01 automation did not change.

The prior complete F01 report `qa/reports/20261014-232942-F01-2f5e0e96dfa2` remains 32/32 on `e4e254f4463a274fdfd055df67b3056e83a249f0`; complete F02 `qa/reports/20261015-000506-F02-3e38935bf776` remains 13/13 on `e73d99b0bbb38ec4db6b9cc837ea635286a1fed0`. Their local integrity receipts and every entry's log digest were independently checked intact. Their original source/fixture contexts remain recorded; resets are not represented as the identical database generation. Old full `qa_revision`/`evidence` fields were not rewritten to claim a new complete pass.

New independent selected browser evidence on actual fixed 0b3f1d: both original regressions passed 2/2 in 7.7 seconds. The three recoverable confirmation variations (customer delete 503, staff revoke-all 503, staff-remove 409) now expose accessible failure feedback and preserve refused state. Actual Owner self-demotion with another Owner retained now removes Staff access/Activity log links while principal and direct 403 permission controls hold. BUG-F02-006/007 are independently verified. The developer's 16 related component checks cover permission updates from initial load/mutation/broadcast, unchanged-permission stability and confirmation focus/recovery; the complete recorded 10/10 command collection `qa/reports/20261015-010423-F01-commands-55f23c47f02f` passed on product-equivalent d94b166. Developer checks remain separately labelled.

The repeated F01 collection `qa/reports/20261015-011303-F01-3bcb75c887d4` completed 11/32 entries before interruption; its runner was already gone when the owner directed no repeated unchanged scope. The original stale running summary is preserved without editing or treating it as a pass. Separate interruption note: `.local/cr007-review-repeat-interruption.json`. Scoped source, report/log digests, old identities, exact coverage and limits: `.local/cr007-owner-scoped-qa-assessment.json`. Fixed browser execution log: `qa/reports/20261015-PR5-fixed-reproduction/reproduction.log`.

Limits are explicit: no new complete feature result on 0b3f1d, whole accessibility/width matrix, full account suite, native manual traversal or independent broadcast multi-tab browser variation is claimed after these fixes. Prior related results and the developer's focused checks are retained under the owner's proportional-scope exception. No regression/smoke/remote scope was added, and no merged-full report was synthesized. Lead closure must preserve this distinction in its scoped evidence policy.

## CR-012 independent tenant-login verification (2026-10-15)

The tenant login now follows the supplied mockup with the current salon name/initial, a full-screen warm interior and a centered standalone form. Independent affected-scope QA passes on `08eccfeca6b1e93177345bfd64508c324ac7417c`: five unique registered F02 groups and 24 Playwright callbacks. The original full-feature `e73d99b` report remains intact; this is a base-plus-delta assessment, not a new full-feature pass. Source/receipts/selection/dependencies are recorded in `cr012/scoped-qa.json`. F01 routing was unchanged by CR-012; its previous grouped assessment remains historical, without a new F01 run or certification.

Chosen types: functional for password/MFA/logout and local recovery controls; security, tenant isolation and permissions for changed login transitions and retained session/BFF boundaries; responsive/accessibility/branding for six widths and Alpha/Beta/Gamma; build for loaded font/translations/handles and secret-free artifacts; resilience for API down/silent form recovery. The existing Owner provisioning/removal journey checks the transition into protected workspace. No new API/schema/infrastructure change warranted repeating direct backend/database/provider/load proof. No regression, smoke, stage pass, mobile, remote deployment or TLS run was requested.

Current execution evidence:

- `qa/reports/20261015-135815-F02-delta-479ab81874f1`: completed overall **FAIL**, preserved. Accept only presentation (6 callbacks), resilience/address (2), and journey (1) passing entries. Its security case read a visible setup output before the asynchronous enrollment response populated its value; the test expectation now waits for the actual 32-character key. No product defect was inferred and no assertion/timeout/retry was weakened.
- `qa/reports/20261015-140300-F02-delta-d4d55515c741`: **PASS**, functional 7 and browser security 8 callbacks, 112.4 seconds. Functional re-execution was required by an added existing-case variation: enrolled and unenrolled Owners authenticate through MFA, then logout returns the initial password form with no retained code/setup state. Security includes client/cookie/standalone-artifact secrecy, CSRF, host isolation, renewal/replay and removed customer BFF boundaries.
- `qa/reports/20261015-135642-F02-delta-462a695fbd8e`: preparation diagnostic presentation **PASS**, 6 callbacks, 48.8 seconds. Its masked initial screenshot was replaced by an empty-field unmasked capture in the final presentation entry so borders and icons could be judged accurately. It is not additional closure coverage.

The developer's separate `qa/reports/20261015-134702-F02-commands-fd058793a3dd` passes all six product gates at `11e9955`. Later product source is unchanged; the exact three changed QA specs are inventoried for command reuse. Lead-owned tooling now distinguishes handoff and manual reason/date bookkeeping from actual commands, cases and automation definitions; its own refusal tests passed. Final API/web readiness is 200, API reports the product-equivalent QA-only `08eccfe`, real clock is restored and retained key mode is 0600. Registered local fixture hooks exercised fixed synthetic identities; no stack volumes or real accounts were touched.

### Current design and keyboard judgment

Authority: `docs/design/references/staff-sign-in.png`, source 1536×1024. Actual empty initial login: `qa/reports/20261015-135815-F02-delta-479ab81874f1/design/CR012-login-alpha-1536.png`, 1536×1024 at CSS 1536×1024, device scale factor 1. No density normalization was needed. Both artifacts were opened together; persistent paired full-view evidence is `design/CR012-comparison-full.png` (3072×1056 including 32px labels), and focused card evidence is `design/CR012-comparison-card.png` (both cropped from x506/y231 to x1030/y867). Judgment is saved in the same report's `CR012-manual-judgment.json`.

| Required fidelity surface | Observed result |
| --- | --- |
| Fonts and typography | Loaded Poppins 400/500/600, the approved family. Its slightly heavier/wider shape than the mockup's sans is expected; title/welcome hierarchy remains clear. Beta's complete long salon identity wraps readably. |
| Spacing and layout | Centered 524px card, x506/top230 vs reference top231, height634.5 vs636. Fields/action54px, circular initial62px, no workspace rail/header. Phone/tablet/desktop states fit without horizontal overflow; enrollment naturally scrolls vertically on a small phone. |
| Colors and tokens | Warm translucent white card/interior retained. The approved taupe action #8A705E is darker than the mockup for white contrast4.605:1. Guidance/error/focus states remain readable; focus records show3px outlines. |
| Image and icon quality | Approved shared generated interior preserves mirror/chair/plant/light composition with a sharper decorative crop. It contains no rasterized UI. Local official Feather eye/lock assets are clear and aligned. Logo/cover publishing remains F04. |
| Copy and content | Welcome, field, action and secure-team text match. API-resolved Alpha Studio/A intentionally replaces Salon/S. Forgot password explains administrator contact and performs no reset/message request. |

No actionable P0/P1/P2 visual mismatch remains; design result **passed**. No new visual product fix was requested after the comparison. Comparison history: the first paired evidence masked fields, so it was insufficient to inspect borders/icons; the final capture asserts both fields empty and saves them unmasked. This is an evidence correction, not a design iteration.

Current TC-F02-S04-018 judgment inspected phone/tablet/desktop guidance/focus states, all six masked enrollment widths and three-salon error frames. Fifteen ordered keyboard focus observations at360/768/1536 all show3px visible outlines; Enter toggles password visibility and administrator guidance, preserving focus. Axe found no serious/critical issue in tested initial/error/guidance and protected dialog states. Unchanged protected account/Owner/confirmation manual evidence is retained explicitly. CUA's in-app browser was unavailable and fallback Chrome timed out on its CDP transport; actual registered Playwright sequences, timings and screenshots were inspected instead. No live native-browser manual traversal is claimed.

### Findings and proposals

No new product bug was filed; `qa_status: pass`. No new suggestion is needed for this bounded repair. Existing ranked proposals remain: first explain fixed-role capabilities before saving (SUG-F02-02), then clarify authenticator rotation versus session expiry (SUG-F02-03), both under CR-006. Their historical competitor observations were not refreshed and are not presented as current. The approved administrator-contact recovery is clear about its present capability; a future reset service would require a separate owner decision.
