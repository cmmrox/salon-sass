# Operator console and provisioning

F03 technical policy, established while planning F03-S01 (decision 042). The requirement register
owns scope; `multi-tenancy.md` owns isolation. These policies govern platform access and initial
provisioning without granting support access to salon records.

## Authority and hostname

The console is served only at `console.<SALON_PLATFORM_DOMAIN>`: `console.localhost` for the
default local environment, `console.stack.localhost` for the local container stack. Its root
redirects to `/console/salons`; unauthenticated entry redirects to `/console/sign-in`. The bare
platform host keeps its status page. Other reserved names do not become console aliases.

API routes under `/api/v1/operator` and browser routes under `/console` and `/operator-web` require
the exact console hostname, ignoring case and a valid port. Reject another host with 404
`not_found` before authentication or side effects, including salon hosts, bare API addresses,
lookalike suffixes and nested labels. Host forwarding follows the existing trusted-edge policy;
caller-supplied forwarded headers cannot choose console authority. Tenant endpoints on the console
host still resolve no salon. The API is the security boundary, regardless of web routing.

Operator users, sessions, MFA challenges, rate counters, plans, provisioning receipts and platform
audit entries are platform records without `tenant_id`. No salon identity can authenticate as an
operator, even if identifiers/passwords coincide. Operator tokens have kind `operator`, issuer
`salon-operator`, audience `salon-operator-api`, no tenant authority, and independent signing,
challenge-hashing and encryption secrets. They cannot authenticate staff/customer endpoints.
Missing or invalid operator sessions answer 401 `invalid_session`; failed password/MFA verification
uses 401 `invalid_credentials`. Insufficient
operator permissions answer 403 `permission_denied`.

| Permission | `OPERATOR_ADMIN` | `OPERATOR_SUPPORT` |
| --- | --- | --- |
| `operator.session.manage` (own principal, renewal, sign-out) | yes | yes |
| `operator.salon.read`, `operator.salon.create` | yes | no |
| `operator.plan.read`, `operator.plan.create` | yes | no |

Support users get no salon management or support-session authority in S01. F03-S04 defines the
separate, expiring support grant. No operator token or role alone enters a workspace or reads its
staff, customers, audit, bookings or other tenant records.

## Operator authentication

Use the existing documented password/PBKDF2 and TOTP policies, including replay prevention, five
challenge attempts and five-minute challenge expiry, from `identity-and-access.md`. Reuse maintained
cryptographic primitives through shared code as appropriate; do not import identity internals or
reuse tenant identity tables. Every operator must complete MFA, enrolling after correct password
verification on first sign-in. There is no public registration, reset, MFA bypass or recovery in
this stage. Bootstrap is an explicit offline administrative command, never startup default accounts
outside `local`; accept credentials through protected input rather than command-line arguments.

Access lasts five minutes; sessions expire absolutely seven days after sign-in. Opaque refresh
tokens rotate once with family revocation on reuse, including races. Current account activity,
role, MFA completion and session revocation are checked on every protected action. Logout revokes
the current session. Disabled users and revoked sessions stop working immediately.

Password attempts are capped at 10 per normalized email and 30 per trusted address in fifteen
minutes, counting unknown users. Use the identity policy's address assertion design with the
operator-specific path and an independent proxy secret; the existing staff assertion cannot be
replayed here. Persist failures, counters and replay consumption even on denied responses. A
denied limit returns 429 `rate_limited` with positive `Retry-After`; expired buckets/challenges
are cleaned by a bounded platform job. Never expose account existence through sign-in errors.

The BFF holds operator authority only in host-only HttpOnly cookies named
`salon_operator_{access,refresh,expires,session,challenge,challenge_id}`, with the existing
SameSite/Secure-local-HTTP policy. Apply exact Origin/Fetch Metadata checks to every mutation,
including sign-in; serialize credential changes across tabs using a distinct
`salon:identity:operator` lock. Broadcast only nonsecret change events. No token/challenge credential
is returned to client components, storage, HTML, URLs or logs. Authenticator enrollment instructions
have the same transient display exception as staff enrollment. BFF upstream calls share the existing
three-second budget; no fresh budget per chained call. No customer browser flow is introduced.

## Platform database boundary and atomic bootstrap

Introduce a separately configured platform runtime database role and bounded pool, used explicitly
by operator/platform services. It is non-owner, non-superuser and has no `BYPASSRLS`. Grant only the
required platform-table operations; the ordinary tenant runtime role retains no platform-table
grants. Keep the default tenant datasource/transaction manager and existing narrow registry reads
unchanged. Platform runtime receives neither migration credentials nor general tenant-table access.
Environment setup creates roles; append-only migrations grant to placeholders. Do not create roles
in migrations or silently fall back to another role when configuration is missing.

Provision in one database transaction on the platform connection: tenant registry/profile, immutable
plan assignment, first Owner, minimal published name/neutral colour for existing workspace routing,
idempotency receipt, and platform creation audit. No partially created salon can be resolved by
another request. Roll back everything on a failed bootstrap step. No network/provider call occurs
inside this transaction. The initial password is validated and hashed before storage and never
stored in a receipt, audit or response.

Identity and branding each expose a narrow public provisioning facade; platform never imports
their internals or writes their tables. Those facades may use module-owned `SECURITY DEFINER`
bootstrap functions on the same platform transaction connection. Grant execution only to the
platform role; revoke PUBLIC and tenant-role execution. Pin search paths, qualify object names,
limit operations to inserting the initial Owner/brand for the newly created tenant, reject a second
bootstrap, and restore any transaction tenant setting before return. Keep forced RLS enabled;
bootstrap sets one explicit tenant within the function, never bypasses RLS or adds generic read,
write, arbitrary SQL, or impersonation functions. No alternate HTTP owner-provisioning endpoint.
Shared transaction participation must be demonstrated by a forced-bootstrap-failure rollback test;
separately committed tenant transactions do not satisfy this policy.

The platform audit is append-only with database protection, actor id, action, subject id, time and
trace id. Record sign-in outcomes, limits, MFA/session events, plan creation and salon creation.
Allowlist nonsecret changed fields; no passwords, hashes, MFA secrets, tokens, contact/profile
values or raw addresses. S01 does not expose a platform audit browsing UI.

## Profile, plans and retry rules

A new salon has a trimmed business/display name (1–120 characters), optional contact email and
phone, optional plain-text address (up to 500 characters), country (supported ISO two-letter code),
currency (supported ISO three-letter code), IANA time zone and default language (`en`, `si`, `ta`).
Optional email follows identity normalization; phone, when supplied, follows existing Sri Lankan
mobile validation. Email/phone/address bounds are respectively 254/32/500 characters before
normalization; optional blanks clear to null. Reject markup only as unsafe rendering, not by
silently interpreting it; display stored text as text. The platform profile owns provisioning/contact
metadata; F04 adds subsequent profile editing in the owning platform service and brand editing in
branding, without a second editable profile source or a reverse branding-to-platform dependency.
Existing synthetic tenants remain unchanged; newly added profile/assignment
columns are nullable for them and shown as unassigned until explicitly configured.

Use the existing database slug grammar/reserved list, lowercase normalized before uniqueness.
The first Owner needs a name, email and initial password conforming to the existing identity
policy. Provision an active Owner without an enrolled secret or session; its first salon sign-in
must enroll MFA. Show the hostname and owner email as the result, never echo the initial password
or sign the operator in as Owner. Minimal brand setup is a neutral routing prerequisite, not a
public salon site or brand editor. The owning module defines its one neutral token in code.

Plans are operator-defined immutable configurations: unique lowercase key (same 3–30 character
grammar as a slug, without the hostname reserved-name rule), name (1–120), staff seat limit and
branch limit (integers 1–1,000,000), message allowance (integer 0–1,000,000), and explicit booleans
for custom-domain and dedicated-app availability. These upper bounds are input/storage safeguards,
not purchased commercial tiers. Require every field; no implicit unlimited/null/negative limits,
numeric coercion or arbitrary feature keys. New definitions get new ids/keys; S01 has no plan edit,
delete or assignment-change operation. Assignment references the exact immutable definition.
Entitlement enforcement/usage is F03-S02; feature flags alone deliver no custom domain or app.
No price, trial, billing cadence or named commercial tier is inferred while D-06 remains open.

Creation of both plans and salons requires an `Idempotency-Key` (UUID), scoped to operator, action
and key. Store an HMAC request fingerprint (including credential-sensitive input) and the nonsecret
result in the same transaction; never store plaintext request bodies or contact/Owner profile
values. Salon creation returns only `{id,slug,hostname,planId,createdAt}`; its separate protected
detail read supplies the profile. Use a secret independent
from signing/encryption keys for fingerprints. Same key and normalized payload replay the original
201/id with no second audit or side effect; changed payload returns 409 `idempotency_conflict`.
Keep successful receipts for the life of their subject in S01; failed transactions reserve no key.
Parallel keys targeting the same slug/plan key yield one record and 409 `slug_taken`/`plan_key_taken`
for losers. Reusing the same key concurrently yields one result. Unknown plan ids return 404
`not_found` and cause no writes. Authentication/permission/host checks precede receipt lookup.

Lists use `{items,page,size,total}`, page 0–100,000, size 1–100 (default 20), sorted by creation
time descending then id; optional name/slug or name/key `q` is trimmed, literal case-insensitive
substring matching up to 120 characters. No dynamic SQL sorting. Salon detail returns only the
provisioning profile, status, nullable plan summary and initial-owner contact reference, never
tenant business records or credentials.

## Local operation and handoff

Local hooks under `/local/operator` are loopback-only, `local`-profile-only and excluded from OpenAPI.
They seed explicit synthetic Admin/Support accounts with supplied credentials, reset their MFA/rate
fixtures, and support a controlled bootstrap failure for rollback checks. Fixture reset is limited
to hook-created synthetic records; preserve unrelated salons and existing Alpha/Beta/Gamma data.
No production profile seeds operator credentials. Use ignored restricted files for local keys and
accounts. Extend secret scanning to all operator and existing identity keys without printing them.

F03-S01's lead handoff adds actual variable names, role/pool setup, bootstrap/fixture commands and
console URLs to the environment document after the implementation reports them. Local startup
and the local stack must provision required roles/secrets without resetting retained data. Nothing
in this policy creates a real environment, sends a real message or approves commercial terms.
