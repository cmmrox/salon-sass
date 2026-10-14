# F02 Sign-in, accounts and roles: product review

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
| SUG-F02-01 | should | Place and focus confirmation near the action that opened it | CR-006 |
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
