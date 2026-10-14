# F02 Sign-in, accounts and roles: product review

Reviewed as a customer on360/390px phones, a receptionist on768px tablet, a Professional between clients, and an Owner provisioning access. Observations are from actual real-authentication browser flows, keyboard Tab/type-ahead/Enter interactions and masked timed screenshots. The final manual case remains pending the independently reproduced BUG-F02-005 fix; this review is not a QA pass.

Compared with public primary documentation from Fresha, Booksy and Vagaro, refreshed2026-10-14. No competitor account or real personal data was used.

## Verdict

The core sign-in, role, device and privacy behavior held in the fixed-source automated checks, but the valid single-identifier staff response contract still prevents certification. The screens are readable on small phones and tablets, with clear error/success feedback and visible keyboard focus. The most useful usability improvement is to bring confirmation controls into the user's current interaction context.

## What works well

- The customer's own salon remains clear, including Beta's long dark-brand name and Gamma's light brand. Staff workspaces stay neutral.
- Error messages preserve the identifier, clear passwords and provide a next step. Code screens explain resend and expiry; a slow request disables the action with visible feedback.
- Separate staff/customer sessions, immediate device revocation and explicit salon-specific deletion consequences give useful control without exposing credentials.
- Keyboard targets inspected in the actual traversal had3px solid focus indicators. Automated responsive/Axe checks covered all six required widths and the three brands; masked screenshots were inspected for readability and layout.

## Suggestions

Ranked proposals; no implementation is authorized by this review. The lead fills Disposition.

| Id | Class | Suggestion | Disposition |
| --- | --- | --- | --- |
| SUG-F02-01 | should | Place and focus confirmation near the action that opened it | |
| SUG-F02-02 | should | Preview the fixed role's capabilities before an Owner creates or changes staff access | |
| SUG-F02-03 | should | Distinguish authenticator code rotation from verification-session expiry in the instructions | |

### SUG-F02-01 Confirmation near the current action

- **Observed:** The small-phone customer page is roughly2500px tall. Delete is at the bottom; activating it inserts the confirmation above permissions/profile while focus remains on Delete. All controls remain reachable by keyboard; reaching Cancel took seven further Tab presses around the page. The clear explanation is visible when that earlier section is inspected.
- **Why it matters:** A customer acting one-handed may initially miss the confirmation and repeat the tap or assume nothing happened.
- **Instead:** Move focus to a nearby confirmation region, bring it into view, and return focus to the originating control on cancellation. Preserve the existing clear salon-specific consequences and cancellation behavior.
- **Evidence:** `qa/reports/20261014-F02-manual-review/manual/phone-delete-confirmation.png` and `keyboard-timing.json`. [Vagaro's customer closure guidance](https://support.vagaro.com/hc/en-us/articles/1260806204150-Close-Your-Vagaro-Account-for-Customers-of-a-Vagaro-Business) uses a specific account-management confirmation sequence. Later booking/payment prerequisites are outside F02.

### SUG-F02-02 Explain the fixed role before saving it

- **Observed:** Owner creation presents a role dropdown. The individual account screen explains current scope and permissions, and the Professional grant is clearly worded, but the creation dropdown provides no concise capability preview.
- **Why it matters:** An Owner deciding between Receptionist, Professional and Accountant must know the effect before granting access; a busy front desk benefits from plain capability descriptions.
- **Instead:** Show a short read-only summary of the selected fixed role's allowed areas and own/salon scope. Keep the current Owner-only management rule; editable custom roles are not proposed here.
- **Evidence:** `qa/reports/20261014-F02-manual-review/manual/tablet-owner-staff.png`. [Fresha](https://www.fresha.com/help-center/knowledge-base/team/100687-create-permission-roles) groups permissions by business areas and shows role access summaries. [Booksy](https://support.booksy.com/hc/en-gb/articles/16535688940946-How-do-I-manage-staff-permissions) explains the capabilities of its five levels. Their broader Manager powers do not change this product's policy.

### SUG-F02-03 Clarify authenticator timing

- **Observed:** The authenticator screen reuses the customer wording that a code expires after five minutes and a new code replaces the previous one. The verification challenge lasts five minutes, while authenticator codes rotate on30-second steps with the documented clock allowance. Entry itself worked.
- **Why it matters:** A first-time Owner may confuse the time available to complete verification with the life of a displayed authenticator code.
- **Instead:** Explain that the verification session expires at the shown time and ask for the code currently displayed by the authenticator app. Keep customer SMS/WhatsApp expiry and resend wording separate.
- **Evidence:** `qa/reports/20261014-F02-manual-review/manual/tablet-authenticator.png`. [Vagaro verification guidance](https://support.vagaro.com/hc/en-us/articles/115005413687-Two-Factor-or-Two-Step-Authentication) distinguishes verification steps and completion. This wording proposal adds no new code lifetime or authentication mechanism.

## Compared with other products

| Aspect | Ours observed | Primary documented comparison | Fit for these users |
| --- | --- | --- | --- |
| Permissions | Fixed roles, live own/salon scope and a clear Professional grant | Fresha area summaries; Booksy explicit role capability descriptions | Safe fixed policy; their explanation before selection would help an Owner |
| Verification | Password/authenticator for staff; SMS/WhatsApp for customers; expiry/resend feedback | Vagaro documents code verification and completion | WhatsApp choice suits the local context; authenticator timing copy can be clearer |
| Privacy | Own JSON download and explicit synchronous salon-specific account erasure | Vagaro profile/account-management deletion and further information-erasure guidance | Direct data control is useful; improve confirmation placement without adding later-feature restrictions |

## Bugs filed during verification and review

BUG-F02-001 audit nullability,002 privacy audit metadata,003 lost-renewal sign-in recovery,004 required test-id naming, and005 optional staff identifier nullability; all major. Their exact states and verification evidence remain in the bug files.005 was found by the actual keyboard Owner path and independently reproduced through both native API identifier variants. A final verdict and performed manual date follow its fix and the complete F02 run.
