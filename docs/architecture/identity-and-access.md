# Identity and access

F02's technical policies. Requirements remain in `docs/brd/requirements.yaml`; isolation and
transactions follow the architecture and backend documents. Decision 035 owns these choices.

## Credentials and tenant binding

Staff accounts and customer identities belong to one salon. Normalize emails to lowercase and
Sri Lankan mobile numbers to international form; staff may have email, mobile or both, at least
one. Each identifier is unique within its account kind and salon. A customer never has a password.
Tenant tables use Hibernate filtering, forced RLS and composite foreign keys. Principal kind is
checked explicitly: a customer credential cannot authenticate a staff action or vice versa.

Use Spring Security's maintained password encoding and JWT primitives, without enabling unrelated
default form-login behavior. Passwords are 15 to 128 Unicode code points, allow spaces and password
managers, and reject a small documented common-password list; no forced character mixture.
Use PBKDF2-HMAC-SHA256, random 16-byte salt and 600,000 iterations with an identified encoding.
These choices follow [Spring password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
and [NIST authentication guidance](https://pages.nist.gov/800-63-4/sp800-63b.html), interpreted as
technical design guidance, rather than a certification claim.

JWT access tokens live five minutes and carry issuer, audience, tenant id, subject, principal kind,
session id, issued time and expiry. Validate signature, fixed algorithm, issuer, audience, time and
kind; never accept an unsigned claim. The verified salon must match a salon hostname; mismatch
answers 404 `not_found`. An authenticated native client on the bare API address may resolve its
salon from the verified token, via a verifier interface in `shared` implemented in `identity`.
Reserved platform hostnames never become a salon. Invalid credentials answer 401 with a registered
stable error code; insufficient permissions answer 403; foreign ids answer indistinguishable 404.
Sign-in, MFA challenge calls and opaque refresh renewal require the selected salon hostname.
The bare API address supports protected requests using signed access tokens; it cannot resolve a
salon from an opaque refresh token or a client-supplied tenant id.

Every protected use case verifies current session and account state in the database, including
account activity, MFA requirement and current role/grant. Do not trust permissions cached in JWTs.
Expired access cannot authorize a request; refresh may renew an otherwise live session.

## Staff two-step verification and management

Use authenticator-app TOTP (RFC 6238): random 20-byte secret, six digits, 30-second step, SHA-1,
one adjacent step tolerance. Store the secret encrypted with authenticated encryption. A successful
step cannot be reused for the same account, including simultaneous requests.

Staff password sign-in returns either tokens for staff without MFA or a five-minute, single-use
challenge: `verify_mfa` for an enrolled person, `enroll_mfa` for an Owner without MFA. Enrollment
returns a secret and `otpauth` URI only after correct password verification, then requires a valid
code before issuing a session. Wrong/expired/consumed challenges issue no tokens. Non-Owners can
enroll voluntarily from an authenticated session. Do not implement MFA disabling/recovery without
a separate reviewed recovery policy. New Owners with no secret must enroll at their next sign-in;
promotion invalidates their existing sessions. Never allow demotion/removal of the last active Owner.

Owner provisions staff with a strong initial password; account administration is salon-specific.
F03 will provision the first Owner of a real salon. F02 seeds local synthetic Owners only; no
public Owner registration or production default credentials. Staff may change their own password
after supplying the old password; revoke other sessions and rotate the current one immediately.

Password sign-in is limited to 10 attempts per normalized identifier and 30 per trusted address
within 15 minutes, including unknown identifiers. MFA challenges allow five attempts. Failed
attempts, consumption, limits and security audit entries commit even when the HTTP outcome is an
error; throwing inside a transaction must not undo these controls.
Staff rate windows start on the first attempt and last fifteen minutes; a denied request returns
`Retry-After: 900`. The `identity-security-cleanup` job runs every fifteen minutes to remove expired
staff rate/MFA challenges and consumed TOTP steps outside the accepted tolerance. Disabled staff
accounts retain their identifiers; removal does not invent reactivation or recovery behavior.

The current common-password list rejects `passwordpassword`, `password123456789`,
`123456789012345`, `qwertyuiopasdfgh`, `letmeinletmeinletmein` and `welcome123456789`.

## Sessions

Refresh credentials use at least 256 random bits; store hashes only. A staff session expires after
seven days and a customer session after 30 days, absolute from sign-in. Refresh rotates atomically;
reuse revokes its session family and returns 401. A simultaneous refresh yields at most one
successful rotation; a replay cannot produce another valid family. Device labels are plain text,
bounded to 80 characters, never trusted for security. Device ids are random server-issued opaque
values; API requests receive a host-only HttpOnly device cookie when needed. A caller-supplied body
device id or forwarded address cannot bypass a server rate limit.

List only one's own sessions, paginated; revoke one or all one's sessions. Staff removal, account
deletion, Owner promotion or password change ends affected credentials immediately. Logout clears
browser cookies and revokes the server session. Staff and customer sessions coexist independently.

## Role permissions

Check permission values in use cases and expose an `IdentityFacade` for later modules. The current
principal response exposes permissions and a data scope (`salon` or `own`) and the staff id for
Professional filtering. Later calendar/revenue/customer modules must enforce that scope; F02 does
not fabricate their data or endpoints. The Owner's Professional grant is a specific boolean for
salon-wide calendar/customer/figures access (IAM-08), not arbitrary role customization (IAM-13).

| Permission | Owner | Manager | Receptionist | Professional | Accountant |
| --- | --- | --- | --- | --- | --- |
| Own session/password/MFA management | yes | yes | yes | yes | yes |
| Staff list | yes | yes | no | no | no |
| Staff creation, role/grant changes, removal | yes | no | no | no | no |
| Audit list | yes | yes | no | no | no |
| Refund, price/policy change, customer export | yes | yes | no | no | no |
| Salon calendar/customer access | yes | yes | yes | own; salon if granted | no |
| Salon financial reports | yes | yes | no | only if granted | yes |

The API permission keys for these rows are `session.manage`, `password.change`, `mfa.enroll`,
`staff.list`, `staff.manage`, `audit.list`, `payment.refund`, `catalogue.price.change`,
`policy.change`, `customer.export`, `calendar.read`, `customer.read` and `financial.read`.
All staff receive the first three own-account permissions; the table determines the rest.
The customer principal has kind `customer`, null role/staff id, own scope, MFA false and
`session.manage`; the customer privacy stage adds its own-profile/data-rights permissions.

Keep pagination on staff, sessions and audit lists. Record successful/failed sign-ins, session
revocation, password/MFA change, staff creation/removal, role/grant changes and customer rights
actions in F01's immutable audit trail. Audit reads use explicit DTOs without secret or profile
values and are permission-checked. Sensitive business actions in later features will call this
same audit writer. Operators and support sessions are F03.
Role, grant and active-state changes include nonsecret before/after values; profile rights actions
include changed field names and versions only, never personal field values.

## Customer codes and abuse controls

Request a six-digit cryptographically random code for a normalized mobile number and chosen `sms`
or `whatsapp` channel. The accepted response contains only a random challenge id and expiry,
never account existence, number or code. A code expires in five minutes, allows five verification
attempts, and consumes atomically once. A resend invalidates earlier challenges for that number.
Bind the challenge to salon, purpose, number and server-issued device. Create the customer only
after verification, with a per-salon unique normalized mobile constraint. Parallel verification of
one code creates at most one customer and one session. Counter rows survive process restarts.

Defaults: at most three requests per number in 15 minutes, ten per device in 15 minutes, twenty
per trusted address in 15 minutes, and 1,000 per salon per UTC day. Reject excess with 429 and
`Retry-After` before enqueueing. All dimensions are updated atomically, including concurrent calls.
These counters count accepted send requests only. Check all four dimensions before inserting or
incrementing any counter; a denied request writes its safe security audit but creates no empty
counter row or chargeable outbox work. `Retry-After` is the positive ceiling of the longest remaining
blocked window, including the time until UTC midnight for the daily ceiling.
At 80% of the salon ceiling, write one secret-free `identity.code_volume_alert` audit entry per
UTC day and increment a tenant-tagged security meter, visible to the Owner through audit. This is
the local unusual-volume alert; real outbound operational notification awaits a provider decision.
Configuration exposes each limit for controlled tests without lowering production defaults.

Messages join F01's transactional outbox with an encrypted recipient/code envelope; encrypt before
persistence. Local `sms`/`whatsapp` providers decrypt only for a capture, support controlled
failure/delay, and never send a real message. Outside `local`, an unconfigured adapter refuses code
delivery and returns a clear service-unavailable response rather than pretending to send. Provider
failures retry through the outbox; an expired challenge is not delivered. No raw number, password,
code, secret, cookie, refresh credential or JWT appears in logs, public errors or audit values.
Outbox cancellation is an explicit `CANCELLED` outcome. The shared `Outbox.cancel(id, instant)`
facade joins the caller's writable tenant transaction, clears pending/sending/dead work and its
lease, and conditional finalization cannot resurrect it. Sent or already-cancelled work stays
unchanged. Resend, consumption and provider-expiry cancellation record `identity.code_cancelled`
when a row changed; scheduled expiry cleanup and synthetic fixture reset do not add a duplicate
identity action. The local provider rechecks a challenge after delay and before capture.

## Profile and privacy

`/customers/me` is the authenticated customer's own profile: name (1 to 120 trimmed characters),
verified mobile (read-only), optional valid email, optional nonfuture birthday, preferred language
(`en`, `si`, `ta`), notification preferences for SMS/WhatsApp/email and explicit marketing consent
(default false). New customers have an empty name until they complete their profile. Profile
updates use optimistic concurrency, returning conflict for a stale version. Customers cannot name
another customer in profile, export or deletion requests. Staff credentials are refused here.

New profile defaults are language `en`, operational channel preferences
`{sms:true,whatsapp:true,email:true}` and marketing consent `false`; email and birthday are null.
Enabling a channel preference neither supplies an address nor grants marketing consent. Updates
send the full mutable representation and version, with every channel preference and marketing
consent required; omitted or null optional email/birthday clears it. Blank/whitespace optional email
also clears it; a nonblank email must be valid. Trim names and trim/lowercase email. Reject unknown
update fields, including mobile and account/tenant selectors. Customer rights
use `profile.manage`, `data.export` and `account.delete`, alongside `session.manage`.

Export is a JSON download of the current customer's profile, preferences and signed-in-device
metadata with a generated time; allowlist fields, never include credentials, other customers or
audit internals. The synchronous export is the data-copy request at this feature's size. Later
modules extend exports through their public facades when customer-owned data exists.

Deletion requires a current customer sign-in and explicit confirmation. Remove the profile and
identifying data, encrypted challenges/messages for its number and all sessions/refresh history
in one transaction; no old token works afterwards. Re-verifying the same number may create a fresh
identity with a new id and no old profile. Immutable audit rows keep only the old opaque subject id
and action/time, no phone, name or profile. Later legal booking/payment retention is planned by the
owning feature; no such records exist in F02. Invalid confirmation changes nothing.
Retain hashed anti-abuse number/device/address counters until their ordinary window expires;
deletion and re-registration do not reset code ceilings. Deletion cancels queued deliveries and
clears local captures. A delivery already in flight rechecks challenge existence before capture
or delivery completion and cannot recreate deleted identity, challenge or message state.
Retain the `salon_device` rate-tracking cookie after deletion. It grants no account authority;
all signed-in-device session credentials are revoked and a fresh code is needed to register again.

## Browser boundary

Use a Next.js server BFF with generated API client types. Host-only staff and customer access and
refresh cookies are separate, HttpOnly, SameSite=Lax, Path=/, Secure outside local HTTP. Local HTTP
alone omits Secure (CR-005); use no Domain cookie. Tokens are returned to the BFF only, never in
HTML, client JSON, URLs, JavaScript-readable storage or client components. Public API bearer
responses remain available to future mobile clients.
Authenticator enrollment alone may transiently show its setup secret and `otpauth` instructions
after correct password or authenticated enrollment authority. Never put those instructions in a
browser navigation link or persistent storage; this exception does not expose session tokens or
the challenge credential to client components.

Every BFF mutation requires an exact trusted Origin matching this hostname/port and rejects
cross-site Fetch Metadata, including unauthenticated sign-in (login CSRF). Do not trust arbitrary
forwarded host/protocol. Refresh is explicit and serialized per browser session; reuse cannot
restore a revoked session. BFF returns only allowlisted public result fields and field errors.
Coordinate renewal across tabs for each principal kind, not only within one form or component.
If a competing replay revokes the family, clear that kind's cookies and require a fresh sign-in;
never retry a spent refresh credential or restore a family from a delayed response.
Redirect targets are fixed same-origin paths; errors keep useful form values except credentials.
Auth failures redirect a protected page to its own sign-in; authorization failures show a friendly
denial. Customer screens use salon branding and staff screens the neutral platform theme.

Per-kind HttpOnly expiry metadata comes from issued `accessExpiresAt`. Compare it with strictly
validated API `meta.time` to decide whether missing/expired access with a refresh credential is
eligible for one renewal. A 401 with future expiry clears that kind without renewal; expiry
metadata never authorizes access. Origin scheme is `http` only with the explicit local HTTP
setting and `https` otherwise, matching validated Host and port exactly. Do not derive it from
forwarded headers or `nextUrl`. BFF export uses a CSRF-checked POST because its underlying API GET
records a rights audit. If Web Locks are unavailable, show a translated browser-support message
and block credential-changing actions, including sign-in, sign-out, renewal, password/session
changes and deletion. Profile reads/updates and exports remain available when already authenticated.

The BFF namespace is `/identity-web/*`; the edge routes `/api/*` directly to Java. Address limits
must reflect the browser connection without trusting browser forwarding headers. Node request
diagnostics records the socket peer and a monotonic three-second request deadline; it accepts one
valid Caddy-overwritten forwarded address only if the peer matches explicit
`SALON_WEB_TRUSTED_PROXIES` CIDRs. Default local trust is empty. Normalize IPv4-mapped IPv6, reject
lists, ports, brackets and zone identifiers, and never log or return the resulting address.

For staff password sign-in and customer code requests, strip incoming assertion headers and sign
a bounded, versioned HMAC assertion using a fourth independent proxy key shared by API and BFF.
It binds the canonical API salon hostname without port, uppercase method, exact API pathname,
normalized address and issued instant. Obtain that instant from the existing API metadata `time`
field with strict ISO-instant validation; never substitute an unsynchronized/cached timestamp.
Proxy lookup, metadata lookup and mutation share the original three-second request budget.
API validates fields and MAC in constant time and accepts within thirty seconds of its injected
Clock. A present invalid assertion fails before rate accounting or side effects; an absent
assertion uses the trusted servlet peer address for direct/native callers. This assertion grants
no tenant, principal or permission authority. Local startup retains the fourth random key without
replacing the first three; nonlocal API/BFF startup requires its explicit configuration.

## Local hooks and secrets

Identity's hooks live under `/local/identity`, are loopback-only with `@Profile("local")`, excluded
from OpenAPI, and tenant-bound. Provide idempotent synthetic account seeding, an outgoing-message
capture keyed by challenge id, controllable provider failure/delay and reset of synthetic identity
records/rate state. Reject reset on salons outside alpha/beta/gamma. The test clock remains F01's.
Seed one account per staff role per synthetic salon; runtime credentials and TOTP seeds stay in
ignored local/QA environment files. Seed endpoints accept credentials from these files, never
publish them in responses or commit them. Tests may create their own random synthetic credentials.

JWT signing, challenge hashing and authenticated encryption keys are independent random values,
configured secrets. Local startup may generate and retain an ignored file with restrictive
permissions, preserving sessions across restarts. Tests supply throwaway keys. Nonlocal startup
requires configured keys; never embed a fallback or generated local key in container images.
Document the environment variables and hooks when each stage hands them over; gates and QA must
coordinate artifact rebuilds and service restarts as in the environment document.

## Implemented staff API contract

The generated [OpenAPI document](../api/openapi.json) is the detailed request/response contract.
`POST /api/v1/identity/staff/sign-in` and `/staff/mfa/challenges` return
`{state,challengeId,challengeToken,expiresAt,credentials}`. Successful password sign-in nests the
tokens in `credentials`; MFA verification, renewal and password change return tokens directly:
`{state,sessionId,accessToken,refreshToken,accessExpiresAt,refreshExpiresAt}`. Access expires at the
same second-precision instant encoded in the signed JWT. Its fixed algorithm is HS256, issuer
`salon-platform` and audience `salon-api`.

Principal kinds are lowercase (`staff`); role names are uppercase. A list returns
`{items,page,size,total}` with page 0 to 100,000 and size 1 to 100. Device entries identify the
current session. Staff changes require the current `version`; audits expose nonsecret before/after
values. All MFA routes require the salon hostname. Protected principal, staff, session, password
and audit calls additionally support a verified signed tenant on the bare API address.
Staff creation answers 201 and staff/session deletion answers 204; generated success-response
statuses must match these actual HTTP outcomes. Nullable/omitted challenge and customer-principal
fields must likewise match the generated schema rather than relying on client casts.

## Implemented customer sign-in contract

`POST /api/v1/identity/customers/challenges` takes mobile, `sms` or `whatsapp` channel and
deviceLabel, answering 200 `{challengeId,expiresAt}` only. A new verified device receives
`salon_device`: a random HMAC-authenticated, salon-bound host-only cookie, HttpOnly, SameSite=Lax,
Path=/, Max-Age 31536000, Secure outside local HTTP. A malformed, duplicate or foreign-salon cookie
does not identify a verified device. `POST /customers/sign-in` takes challengeId/code and the
issuing cookie, answering the same token structure as staff. Both routes require the salon host.
Customer principal role/staffId are nullable and scope is `own`; sessions remain kind-separated.

Code limit settings are positive integers under `salon.identity.codes`, supplied by
`SALON_IDENTITY_CODE_NUMBER_LIMIT`, `SALON_IDENTITY_CODE_DEVICE_LIMIT`,
`SALON_IDENTITY_CODE_ADDRESS_LIMIT` and `SALON_IDENTITY_CODE_DAILY_LIMIT` (3/10/20/1000 defaults).
Nonlocal customer challenge requests return 503 `provider_unavailable` until a real sender is
configured; local captures certify the API/delivery behavior, not live SMS or WhatsApp activation.
