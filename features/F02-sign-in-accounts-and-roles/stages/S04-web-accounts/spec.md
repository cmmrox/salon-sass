# F02-S04 Staff workspace and customer account screens

## Objective

Staff and customers can complete F02 from responsive web screens on their salon's hostname. Authentication credentials stay on the server and independent staff/customer sessions coexist, with useful error recovery and accessible controls.

## Owner-approved design correction (CR-007)

The supplied HTML's final cascade and `docs/design/ui-ux-standards.md` are the visual authority.
Repair shared tokens/type, customer and workspace shells, forms/cards/rows/actions and foundation
status/error pages. Preserve the business/security acceptance criteria below. New screens inherit
the reference grammar. Do not invent future capabilities or change the prototype.

- AC-9: Source-defined visual checks and rendered comparison evidence show the warm palette,
  loaded Poppins, pill actions, rounded fields/cards, 430px customer canvas and responsive salon
  rail/tab layouts at the width matrix, with readable brands, labels and focus. Independent QA
  records reference mapping/screenshots; unexplained visual departures fail. (NFR-21)

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

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| IAM-01, IAM-03 to IAM-11 | F02 authentication, profile, privacy, roles, devices, abuse controls and audit requirements | web journeys over closed S01-S03 APIs |

## Scope

Translated responsive staff sign-in/MFA enrollment and verification, workspace account/security/devices, Owner staff access management and Owner/Manager audit views; branded customer sign-in/code/resend/account/profile/privacy/devices; a server BFF using generated client, safe scoped cookies, exact-origin CSRF checks, credential renewal and friendly denial/offline states; API/infra support for authenticated trusted-client-address assertions and an independent proxy key; developer component/security tests.

## Out of scope

Business dashboards/calendar/services/revenue screens in later features, operator console (F03), booking details integration (F07), MFA recovery/disable, mobile app, arbitrary permission editing, real messaging and deployment. Existing public shell stays browseable without sign-in.

## Acceptance criteria

- AC-1: On a salon hostname staff can sign in by email or mobile/password, enroll required Owner or voluntary staff authenticator verification, verify a valid code and reach their workspace account. Wrong credentials/codes, expiry and rate limits give translated recovery instructions and field errors; no incomplete MFA challenge reaches a protected page. (IAM-06, IAM-09)
- AC-2: Customers can request SMS/WhatsApp verification, enter a single-use code, resend safely with a visible retry/expiry explanation and reach their own branded account. Wrong/expired/resent codes and delivery failures keep the mobile choice for retry without revealing whether an account exists; public browsing needs no sign-in. (IAM-01, IAM-03, IAM-10)
- AC-3: Protected web pages and BFF actions check the current server principal/permission: Owner manages staff roles/removal and the specific Professional grant, Manager can list staff/audit only, and other roles see only their permitted account controls. Wrong-kind, wrong-role, foreign-salon and revoked credentials cannot reveal protected data by typing a URL or calling BFF directly. (IAM-03, IAM-07, IAM-08, IAM-09, IAM-11)
- AC-4: Staff/customer own device lists identify the current device, paginate, revoke another or all devices, and sign out with immediate server revocation; password change verifies the old password and rotates credentials. Separate staff/customer cookies on one host allow both sessions without either sign-out disturbing the other, and expiry/renewal returns safely to the intended same-origin flow. Renewal coordination covers simultaneous browser tabs for each principal kind; a replay-revoked family clears cookies and requests fresh sign-in without retrying the spent credential. (IAM-06, IAM-09)
- AC-5: The customer account loads and updates all mutable profile fields, read-only verified mobile, language/preferences and explicit marketing consent with translated field validation, success and stale-version conflict recovery; changes never affect another customer/salon, and refresh/back/second-tab does not silently overwrite saved data. (IAM-03, IAM-04)
- AC-6: The customer can download an allowlisted personal-data JSON file and confirm account deletion after a clear explanation. Cancelling changes nothing; successful deletion clears customer cookies, returns to the salon sign-in and invalidates every device while another salon's account remains. Owner/Manager can read identity and volume-alert audit entries without secrets. (IAM-03, IAM-05, IAM-10, IAM-11)
- AC-7: All web credentials use distinct host-only HttpOnly SameSite staff/customer cookies, Secure outside explicitly local HTTP. Access/refresh/challenge authority is absent from HTML/client responses/storage/URLs; authenticator enrollment alone may transiently display its setup key and URI after valid enrollment authority, without storing it or making a navigation URL; every BFF mutation including sign-in rejects wrong/missing Origin and cross-site Fetch Metadata, tenant comes only from resolved host, and user redirect input cannot send credentials to another origin. (IAM-03, IAM-06, IAM-09)
- AC-8: Every new screen has loading/empty/error/slow/success states where applicable, translated strings, stable test ids, semantic labels, announced errors, visible keyboard focus, 44-point targets and no horizontal overflow at 360/390/768/1024/1280/1440. Customer screens apply alpha/beta/gamma brands with readable contrast/long names; workspace remains neutral. API loss preserves nonsensitive entered data and provides safe retry within the existing three-second limit. (IAM-01, IAM-04, IAM-06)

## Design notes

Follow the identity/browser policies and established components/tokens. The prototype has no actual sign-in/MFA/device/privacy screens; use its form/card/navigation visual grammar and the existing shell, with neutral staff and salon-branded customer surfaces. Reuse one responsive form/device/error presentation where possible. Browser credential mutations use a Web Lock per principal kind across tabs; after acquiring it, recheck the server principal before renewal, and carry only nonsecret events through BroadcastChannel. Hold the same lock for sign-in/logout/password rotation/current-session revocation/deletion to prevent delayed honest responses restoring signed-out state. Without Web Locks, disable all credential-changing actions and show a translated browser-support explanation; already-authenticated profile reads/updates and exports remain available. Unknown-outcome refresh is not retried. Use per-kind HttpOnly issued accessExpiresAt metadata and strict API meta.time to decide eligibility for one renewal only when access is missing/expired; future-expiry 401 clears that kind without renewal. Expiry metadata never authorizes access. BFF credentials never cross the client boundary, mutating requests require exact hostname/port Origin, and only configured API destination receives bearer credentials. Generated types remain the API truth; list chosen route/cookie names in handoff.

## API and data changes

Carry the existing API salon timeZone through the public salon pick/header so device/audit dates use salon time zone. Use Next.js same-origin BFF route handlers or server actions under a fixed auth/account namespace, exclusively forwarding explicit allowlisted fields and the principal-kind cookie to the generated S01-S03 API client. Origin scheme comes only from the explicit local HTTP setting (http locally, https otherwise), matching validated Host and port exactly; never derive authority from nextUrl or forwarded headers. BFF export is a CSRF-checked POST forwarding the API GET that records rights audit. Report any missing API contract to lead rather than inventing it. Implement safe host-derived requests through the existing forward-hostname utility and configurable-base client. BFF sets/clears host-only cookies on token issuance/rotation/revocation; direct client JSON strips access/refresh credentials and enrollment authority. For rate-limited mutations, capture the real client address at Node request diagnostics (socket directly locally; one Caddy-overwritten forwarded address only from an explicit trusted-proxy CIDR allowlist), strip incoming assertion/forwarded headers, and sign a bounded assertion over canonical host/method/path/address/issued instant using a fourth independent 32-byte proxy key. Obtain the issued instant from existing API meta.time within the same total three-second request budget, so the injected API Clock/test clock governs the thirty-second assertion window. API accepts the rate address only after strict parsing and constant-time HMAC validation; a present invalid assertion fails before rate accounting or side effects, while an absent assertion uses the real servlet peer address, never a body/header-provided address. Canonical format is versioned and binds normalized host without port, uppercase method, exact fixed pathname, one valid normalized IP (IPv4-mapped IPv6 consistently normalized) and ISO issued instant. Reject lists/ports/brackets/zone identifiers and invalid addresses; local trusted-proxy list is empty. Assertions grant no tenant or principal authority. Wire retained local proxy key without replacing existing keys, explicit nonlocal API/BFF proxy key and web trusted-proxy settings, and the explicit local HTTP cookie flag through existing startup/stack files. Use `/identity-web/*` outside `/api/*` because the stack edge routes /api directly to Java. Every BFF call uses only configured API destination.

## UI and UX

Routes: `/workspace/sign-in`, `/workspace` account entry, `/workspace/account` (password/MFA/devices), `/workspace/staff` (Owner management; Manager read-only), `/workspace/audit` (Owner/Manager), `/sign-in` (customer code flow) and `/account` (profile/devices/export/delete). Link customer account and staff sign-in from the public shell without copying business navigation for unavailable future features. Enrollment shows the authenticator URI or QR plus a copyable setup key only during enrollment; never persist it to browser storage or URLs. Sensitive form fields clear on successful submission; disable duplicate submissions. Loading skeletons, empty device/audit/staff lists, permission denial, field errors, offline retry, rate wait and confirmed success are observable.

## Risks and test focus

Functional journeys, every-role BFF permissions, tenant isolation and kind separation, CSRF and cookies/credential secrecy, browser refresh/back/tabs/renewal races, account deletion/export, accessibility and all responsive widths/brands, resilience under API/provider outage. Developer uses unit/component tests and running-browser checks; independent QA later verifies all F02 stages and cross-stage journeys.

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
