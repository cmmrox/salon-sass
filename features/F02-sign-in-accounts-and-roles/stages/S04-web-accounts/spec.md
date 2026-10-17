# F02-S04 Staff workspace and account screens

## Objective

CR-010 supersedes customer web scope in the original stage and CR-007. Salon owners and relevant
staff use a responsive web backend. Customers use only the later React Native app, after every
M1-web feature is released. Existing S02/S03 Java APIs remain backend foundations.

## Read first

- `docs/architecture/identity-and-access.md`
- `docs/architecture/multi-tenancy.md`
- `docs/architecture/tech-stack.md`
- `docs/architecture/repository-structure.md`
- `docs/engineering/backend.md`
- `docs/operations/environments-and-releases.md`
- `docs/qa/test-plan.md`
- `docs/engineering/web.md`
- `docs/design/prototype/index.html`
- `docs/design/ui-ux-standards.md`
- `docs/changes/CR-012-salon-login-design.md`
- `docs/changes/CR-013-one-salon-theme-and-direct-entry.md`

## Requirements covered

Staff web delivery: IAM-06 to IAM-09 and IAM-11. Customer IAM-01/IAM-03/IAM-04/IAM-05/IAM-10
remain API foundations covered by S02/S03, with their customer interface deferred to F15.
NFR-21 applies using Salon mode in the prototype, never Customer mode for web screens.

## Scope

Staff password sign-in, required Owner/optional staff MFA, account password/security/devices,
Owner staff management, Owner/Manager audit and a staff-only server BFF. Fixed same-host legacy
aliases prevent the old customer links from exposing customer functionality. Remove obsolete
customer web components, navigation and BFF actions. Do not remove customer Java APIs or data.

## Out of scope

Customer web application, customer browser OTP/profile/privacy/export/deletion, native app
implementation, future business dashboards, operator console (F03), real messaging and deployment.

## Acceptance criteria

- AC-1: On a salon hostname staff sign in with email or mobile/password, enroll required Owner or
  voluntary staff MFA, verify a valid code and reach their workspace account. Invalid/expired
  credentials, MFA and rate limits give translated recovery without bypassing MFA. (IAM-06, IAM-09)
- AC-2: Tenant `/` redirects to fixed same-host `/workspace`; unauthenticated entry reaches
  `/workspace/sign-in`. Legacy `/sign-in` and `/account` redirect to staff sign-in. No customer
  OTP, account, booking site or customer/staff switch is reachable or linked. (APP-06, NFR-21)
- AC-3: Protected pages and BFF actions check the current principal/permission: Owner manages
  staff roles/removal and Professional grant, Manager reads staff/audit, others see only permitted
  controls. Wrong-kind, wrong-role, foreign-salon and revoked credentials reveal no protected data.
  (IAM-03, IAM-07, IAM-08, IAM-09, IAM-11)
- AC-4: Staff device lists identify the current device, paginate and revoke one/all devices.
  Logout revokes immediately; password change verifies the old password and rotates credentials.
  Coordinated cross-tab renewal and replay revocation cannot restore a signed-out session. (IAM-09)
- AC-5: Every former `/identity-web/customer/{action}` GET/POST returns no-store 404 without
  calling an upstream API, issuing customer cookies, requesting a code or exposing profile data.
  Direct customer Java APIs retain existing security and compatibility for later mobile. (APP-06)
- AC-6: Owner/Manager audit reads remain secret-free. Refused device/staff mutations show clear
  accessible errors and retain refused state; real role changes immediately update navigation
  and server permissions. (IAM-07, IAM-09, IAM-11)
- AC-7: Staff credentials use host-only HttpOnly SameSite cookies, Secure outside explicit local
  HTTP. HTML/client/storage/URLs never contain tokens or challenges; transient authorized MFA
  enrollment may show its setup URI/key. All BFF mutations enforce exact Origin and Fetch Metadata;
  resolved Host selects tenant, and configured API destination alone receives credentials. (IAM-09)
- AC-8: Screens have applicable loading/empty/error/slow/success states, translated text, stable
  test ids, announced errors, keyboard focus, 44-point targets and no horizontal overflow at
  360/390/768/1024/1280/1440. Salon names remain readable/accessible.
  API loss provides safe retry within the existing cancellation budget. (IAM-06, NFR-21)
- AC-9: Staff sign-in matches the owner's CR-012 mockup: full-screen warm salon interior,
  API-resolved salon name/initial, centered translucent card, welcome headings, labelled email/mobile
  and password fields, accessible password visibility toggle, functional administrator-contact
  recovery guidance, primary action and secure-team footer. No workspace rail/header on sign-in.
  Password/MFA, errors and small-screen states remain usable. Protected workspace screens retain
  warm palette, loaded Poppins, pills, rounded fields/cards and responsive Salon rail/tab layout.
  Customer's 430px canvas is not a web reference. (NFR-21)
- AC-10: (CR-013) Sign-in and workspace share one theme, the prototype's final tokens (canvas
  `#F3F0EB`, secondary `#EAE5DC`, lines `#DDD6CA`, white cards, ink `#111827`, muted `#6B7280`).
  Wherever the prototype uses its accent (`--ac`, `--acs`, `--onac`): the sign-in action, initial
  ring, links and field focus, and the workspace's primary actions, selected navigation item,
  selected chips and focus accents use the salon's API brand colour, its soft tint and its computed
  readable foreground. A salon without a valid colour gets `#D6086A`. Changing a salon's colour
  changes both screens with no rebuild; the CR-012 taupe palette and the black accent are gone.
  Checked with a dark and a light salon colour and the default. (NFR-21, BRD-01)
- AC-11: (CR-013) Loading `/`, `/workspace` or any protected workspace URL without a usable session
  arrives at `/workspace/sign-in` with no intermediate frame: no generic "Salon" name, no "Loading
  your account…" card or skeleton is painted on the way. A signed-in member's first painted frame is
  their workspace with the salon's resolved name. A loading state remains only where data is truly
  pending after the page is shown, and it carries the salon's identity and theme. (NFR-21, IAM-09)

## Design notes

Extend Salon mode's form/card/table/drawer grammar for account screens. Show only available staff
routes. Keep existing Web Locks and nonsecret BroadcastChannel coordination; unsupported browsers
show the translated support explanation for credential changes. Expiry metadata never authorizes
access; unknown-outcome refresh is not retried. Preserve the three-second shared API cancellation
budget, trusted client-address assertions, explicit local HTTP configuration, host-only routing,
server permissions, field allowlists and generated API contract. `/identity-web/staff/*` stays
outside `/api/*` for edge routing. No API/data migration is required by this correction.

## API and data changes

No Java API, schema or generated contract changes. Remove the customer BFF surface only; retain
staff request signing, permission checks, cookie and exact-Origin boundaries.

## UI and UX

`/workspace` enters `/workspace/account`; `/workspace/sign-in`, `/workspace/staff` and
`/workspace/audit` retain their existing permissions. The two legacy customer URLs are fixed staff
sign-in aliases; `/salon-site` remains externally 404. No customer navigation is shown.

## Risks and test focus

Verify changed root/legacy routing, customer BFF rejection without upstream side effects, tenant
isolation/status pages, staff password/MFA, permissions, sessions/CSRF and responsive accessibility.
Independent scoped QA retains unaffected API/backend evidence under CR-009, updates obsolete web
expectations, and records exactly what ran. No unrequested regression or staging checks.

## Verification commands

```bash
cd apps/api && ./mvnw -q verify
pnpm -r lint
pnpm -r typecheck
pnpm -r test
pnpm --filter @salon/web build
pnpm contracts:check
```

## Done criteria

- The developer checks every acceptance criterion against the running product and records it in handoff.
- Every verification command passes; generated contracts/client are current and committed.
- Lead records actual endpoint/hook/configuration shapes and any documented discrepancies before closure.
- No application secrets, real data, paid sending or unverified completion enters the handoff.

## CR-013 theme and entry

One theme for every salon screen: the prototype's final tokens with the salon's brand colour as the
accent (`brandRootRule` already computes `--color-brand`, `--color-on-brand`, `--color-brand-soft`);
the default is `#D6086A`. Retire the `--color-login-*` colour tokens in favour of the shared ones
and use the brand where the workspace now uses `--color-primary` as an accent. Decide entry on the
server (session cookie/principal), so the browser never paints a generic frame before a redirect.
The operator console keeps the neutral platform theme.

Text drawn in the brand colour (links, the recovery control) uses it only when it reaches 4.5:1
against its background; otherwise it is ink `#111827`, which may keep a brand underline or
indicator. Fills, the initial ring and the field focus ring use the brand itself; the keyboard
`:focus-visible` outline stays the prototype's `#1D5FD0` everywhere, for visibility on any brand; anything drawn on a fill uses the
computed foreground. The phone's selected tab is a soft brand tint behind the icon with ink text.
For tests, `infra/scripts/local-salon.sh colour <slug> <#RRGGBB>` sets one local salon's stored
colour (local only, owning role, nothing else changed; `local-reset.sh` restores the seed).

## CR-012 login mapping

The owner's `docs/design/references/staff-sign-in.png` overrides the HTML grammar only for staff
sign-in. Salon identity comes from hostname resolution; the shared photo is not a published cover.
Recovery explains contacting the administrator without claiming to reset a password.
