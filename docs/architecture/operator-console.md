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

Supported countries are the JDK's ISO 3166-1 alpha-2 codes and supported currencies the active
ISO 4217 codes with exactly two minor digits, held in one platform constant (decision 050, NFR-13).
The create form defaults to LK, LKR, `Asia/Colombo` and English. The first Owner's name is kept in
identity's nullable `identity_staff.name`.

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
from signing/encryption keys for fingerprints (`SALON_OPERATOR_FINGERPRINT_KEY`). Same key and normalized payload replay the original
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

## Local test hooks (F03-S02 to S04)

Added under `/local/operator` with the same loopback/`local`/OpenAPI exclusions, so feature QA can
reach every boundary from outside without editing code. The developer names the exact paths and
bodies in the stage handoff; the lead copies them into `docs/operations/environments-and-releases.md`.

| Hook | Stage | Does |
| --- | --- | --- |
| Run job now | S02, S03 | Runs one pass of the billing, export or deletion job synchronously at the test clock's instant and returns what it did |
| Interrupt job | S02, S03 | Arms a one-shot failure at a named point (after invoice issue before charge record; export after N tables; deletion after N batches), so the next run must resume |
| Fake provider | S02 | Sets the charge outcome (success/failure) per salon, applying to every later charge until changed; signs an arbitrary event body with the local provider key, for replay and tamper tests. Events are JSON `{eventId,invoiceId,attemptKey,outcome,occurredAt}` with an HMAC-SHA256 signature header; the key is a local secret variable, never committed |
| Email capture | S02 | Lists captured `email` deliveries by recipient (subject, kind, body links) and clears them |
| Unbilled salon | S02 | Creates a synthetic legacy salon with no subscription and null new profile fields, so billing a legacy salon never consumes Alpha, Beta or Gamma |
| Disable operator | S02 | Disables or re-enables a hook-created fixture operator (S01 AC-5 check) |
| Salon background state | S03 | For one salon, regardless of its state: counts of waiting, delivered and cancelled outbox messages and of its due, skipped and run jobs, with no message content |
| Local reset | S03 | Removes every salon created after the synthetic baseline (tenant data through the erasers, then its platform rows, tombstone, invoices and audit), resets hook-created operators, returns the test clock to real time and reschedules job occurrences it pushed ahead. Alpha, Beta and Gamma and their data are untouched, so F01/F02 fixture guards and regression run unchanged afterwards |

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

## Subscriptions, invoices and entitlements (F03-S02)

The owner set these billing terms on 2026-10-18 (decision 049). They are operating terms for the
platform's own subscription billing, not published commercial tiers: D-06 (pricing and packaging)
stays open, and no tier, list price or discount is inferred from the BRD's commercial hypothesis.

**Price.** Plans stay immutable and unpriced. Each subscription carries an operator-entered monthly
amount in the salon's currency, as an integer in minor units (0–100,000,000; LKR has two minor
digits like every supported currency). The amount is required when a salon is created from S02
onwards and when a subscription is started for an existing salon. A changed amount applies from
the next invoice; an issued invoice never changes.

**Cycle.** A new salon starts a 14-day trial at its creation instant. The first invoice is issued
at trial end, and one invoice per calendar month after that, anchored to the trial-end instant in
the salon's time zone (a 31st anchor falls on the month's last day). Billing is monthly only.
Every invoice is due 7 days after issue. Existing synthetic salons without a subscription are
`unbilled`: fully usable, shown as having no subscription, and billed only after an Admin starts
one (which begins its own 14-day trial).

**States.** A subscription is `trialing`, `active` (no unpaid invoice past due), `past_due` (an
invoice unpaid after its due instant) or `read_only` (an invoice still unpaid 14 days after its
due instant). Paying every overdue invoice returns `past_due` or `read_only` to `active` at once.
State changes are applied by an idempotent platform job that compares instants with the
application clock, and also immediately after a payment is recorded. No transition is skipped or
repeated when the job runs late, twice or concurrently.

**Payment.** A subscription's payment method is `card` (a recurring charge through the platform
billing provider) or `bank_transfer`. The billing provider is an interface with a local fake whose
outcome a loopback hook can set; no real provider is chosen while D-06 is open, and nothing charges
a real card. A charge is attempted when an invoice is issued, and retried on each reminder day
while unpaid; each attempt carries a stable idempotency key, so a repeated or late provider result
pays an invoice at most once. Provider results arrive as signed events with unique ids; a repeated
event id is acknowledged without effect, and an event for an unknown or already-paid invoice is
recorded without changing it. An Admin records a bank transfer against one open invoice with its
reference, the exact amount and the received date (not in the future); partial or excess amounts
are refused. Recording requires an `Idempotency-Key` as in salon creation.

**Reminders.** The salon's Owner contact (the provisioned Owner email) receives an email through
the outbox's `email` channel: a failed-charge notice after each failed charge, and overdue
reminders on the due date (if unpaid), 3 days and 10 days after it; then the read-only notice at
14 days. Each reminder is sent once per invoice and kind, even if the job repeats. Locally the
`email` channel is a capturing fake, as for `sms` and `whatsapp`; nothing is sent for real.

**Invoices.** An invoice has a platform-wide sequential number (`INV-000001`), the salon, the plan
key and name at issue, the billing period, amount and currency, issue and due instants, status
(`open`, `paid`) and its payments (method, reference or provider event, amount, paid instant).
Invoices and payments are platform records; they are kept after a salon is closed and deleted.

**Read-only.** A `read_only` salon keeps sign-in, MFA, session renewal, sign-out, password change
and customer privacy rights, and every read. Every other tenant mutation answers 403
`salon_read_only` before any change. The tenant registry carries the access state, changed only
by billing and lifecycle transitions and read on every request; nothing caches it.

**Entitlements.** Platform exposes one public entitlements facade that other modules call inside
their own transactions: the plan's limits and features, and the salon's current usage. Staff seats
count active staff accounts, enforced by identity on staff creation (409 `entitlement_exceeded`
naming the limit); branches, message allowance, custom domain and dedicated app are enforced by
the features that create those things (F04, F11, F16) through the same facade. An Admin may move a
salon to another plan; the move is refused with 409 `entitlement_exceeded` when current usage
exceeds the new plan's limits. Staff of the salon read their entitlements and subscription state
from the API; nothing in a client is the source of an entitlement.

As built (F03-S02, decision 051): the registry keeps `access_state` (`full` or `read_only`,
written only by billing) beside the lifecycle `status`, which takes precedence, and a `version`
for operator changes. The read-only allowlist is staff sign-in, MFA, renewal, revoking one's own
sessions, password change, customer sign-in and the customer's own profile and deletion; any other
mutating `/api` request on a read-only salon, even one without a handler, answers 403
`salon_read_only` before authentication. `/local` hooks and provider events are exempt. A salon
without a currency is given one when its subscription starts (stored on the subscription only);
a salon without an Owner contact has its reminders recorded with recipient `none`, no email, and a
"No billing contact" warning in the console. Billing emails and charge requests are enqueued in
the salon's outbox in the same transaction as the invoice, so while a salon is suspended they wait
with its other messages; invoices are still issued and bank transfers still recorded. Boundaries
are exact 24-hour-day offsets from the due instant; a late job pass issues every missed period
and sends each passed reminder once. Staff seats count active staff including the Owner, and a
plan move checks staff seats only, since no other usage exists yet. Provider events carry
`X-Salon-Billing-Signature`, a lowercase hex HMAC-SHA256 of the body; a bad or missing signature is
401 `invalid_credentials`, and a late success after a manual payment is recorded `already_paid`.

| Permission | `OPERATOR_ADMIN` | `OPERATOR_SUPPORT` |
| --- | --- | --- |
| `operator.billing.read` (subscription, invoices) | yes | no |
| `operator.billing.manage` (start subscription, amount, method, plan move, record transfer) | yes | no |

## Suspension, closure, export and retention (F03-S03)

**Suspension.** An Admin suspends a salon with a reason (10–500 characters). A suspended salon
behaves as decision 027 and 032 describe: 403 `salon_suspended` on every salon endpoint, the web's
"temporarily unavailable" page, waiting messages and skipped jobs. Its staff and customer sessions
are refused while suspended, not revoked. Billing continues to issue invoices and record payments.
Reactivation (with a reason) returns the salon to the access state its billing implies (`active`,
or `read_only` if an invoice is that overdue). Suspending a suspended salon, or reactivating an
active one, is 409 `invalid_state` with no change.

**Closure.** An Admin closes a salon by typing its slug to confirm, with a reason. Closure is
irreversible. A closed salon answers 404 `not_found` on its hostname as if it did not exist, its
sessions and pending outbox messages are cancelled, its jobs stop and billing stops (open invoices
stay open as records; no new invoice is issued). Its slug is retired: it is never reusable.

**Export.** Closure schedules a full export of the salon's tenant data in the same transaction. A
platform job builds one ZIP archive containing a JSON file per tenant table, written by each
owning module through a registered exporter, with a manifest of tables, row counts, the salon's
profile and the export instant. Secrets (password hashes, MFA secrets, token or challenge hashes,
encrypted delivery payloads) are excluded and the manifest says so. The archive is stored encrypted
at rest in a platform table (no object storage exists yet; decision 049), with a SHA-256 checksum.
When it is ready the Owner contact receives an email with a download link valid until deletion;
the link carries a 256-bit token stored only as a hash, opens a platform-host download page and
each download is audited. An Admin may download the same archive from the console. Exporters for
tables added by later features are part of those features' definition of done.

**Retention.** Tenant data is deleted 30 days after closure by an idempotent platform job: every
tenant row of the salon through each owning module's registered eraser, its brand, outbox messages,
jobs, audit entries and the export archive. The tenant registry row remains as a `deleted`
tombstone with its retired slug and timestamps; platform audit, invoices and payments remain.
Deletion that fails part-way resumes on the next run and never deletes another salon's rows.

| Permission | `OPERATOR_ADMIN` | `OPERATOR_SUPPORT` |
| --- | --- | --- |
| `operator.salon.suspend`, `operator.salon.close`, `operator.export.read` | yes | no |

As built (F03-S03, decision 052): the exporter/eraser contract lives in `shared`, because platform
already depends on identity and branding; each module registers both for its tenant tables, and a
test fails when a table with `tenant_id` is unregistered. Module erasers are guarded database
functions that refuse a salon not closed for 30 days. A trigger forbids reopening a closed salon,
changing a retired slug or removing a `deleted` tombstone. Deletion waits while an export is being
built. The export-ready email uses its own `platform-email` outbox channel, the only one still
delivered for a closed salon, with its link encrypted in the outbox; the Owner's page at
`/exports#<token>` on the platform host reads `GET /api/v1/exports/{token}/metadata` and the archive,
and request logs and traces show the token as `{token}`. Closure instants keep millisecond
precision. The local reset uses the owning role's connection in the `local` profile only; the
runtime platform role has no delete rights on billing or audit.

## Support sessions (F03-S04)

**Grant.** An operator with `operator.support.start` starts a support session for one salon with
a reason (10–500 characters) and a duration of 5–120 minutes (default 30). One operator has at
most one active session per salon (409 `support_session_active`). A session is refused for a
suspended or closed salon. The session ends at its expiry, when the operator ends it, when an
Admin revokes it in the console or when the salon's Owner revokes it in the workspace; every end
takes effect on the next request.

**Entry.** Starting a session returns a single-use entry code valid for 60 seconds, bound to the
session. The console opens `https://<slug>.<platform>/workspace/support#<code>`; the fragment never
reaches a server or a log. The page removes it from the address at once and posts it to the salon
host's BFF, which exchanges it with the API for a support credential and keeps that credential in
host-only HttpOnly cookies named `salon_support_{access,session}`. The exchange applies the exact
Origin and Fetch Metadata checks. A consumed, expired or foreign-salon code issues nothing.

**Authority.** The support credential has kind `support`, issuer `salon-support`, the salon as its
only tenant, the session's expiry and no refresh. It reads what the salon's Owner can read through
the existing staff read endpoints and workspace pages, and nothing else: every mutation answers
403 `support_read_only`, and operator, customer and other salons' endpoints refuse it. It never
becomes a staff session and never reveals passwords, MFA material or tokens.

**Audit.** The salon's audit trail records the session's start (operator display name, reason,
expiry), its end and how it ended, and every API request made under it (method, route template,
outcome, time). The Owner and Manager see these entries in the workspace audit list; the Owner
sees active and past sessions on a Support access page and may revoke an active one. The platform
audit records the same start, end and revocation. Operator email addresses are not shown to salons.

| Permission | `OPERATOR_ADMIN` | `OPERATOR_SUPPORT` |
| --- | --- | --- |
| `operator.salon.read` (directory, detail and lifecycle history, to choose and understand a salon) | yes | yes, from S04 |
| `operator.support.start`, ending one's own session | no | yes |
| `operator.support.read`, `operator.support.revoke` (every session) | yes | no |

As built (F03-S04, decision 053): the entry exchange returns `{accessToken,sessionId,issuedAt,expiresAt}`
to the BFF only (`/identity-web/support/entry`); the credential is checked against the stored
session on every request and one issued in the future is refused. The single mutation a support
credential may make is `DELETE /api/v1/support/session` (End session from the banner). The Owner's
Support access page is denied to the support view, and the Owner gains `support.manage`; the Owner's
revocation is on the read-only allowlist. In one browser a support cookie takes precedence over a
staff session on that salon. Suspending or closing a salon leaves open support sessions to expire,
but their credentials are refused at once (403 `salon_suspended` or 404). Operator session lists
filter by `salonId` and `state` (`active`, `ended`). When a support view ends, the salon host
sets a 10-minute HttpOnly marker cookie `salon_session_ended=support` (no credential, no access);
a protected page requested without a session but with the marker shows the "session ended"
notice at `/workspace/support#ended` instead of staff sign-in, and the notice clears it
(BUG-F03-005).
The start response also carries `codeExpiresAt`. The console's entry link uses
`SALON_SUPPORT_LINK_ORIGIN` (default `https://%s`, locally `http://<slug>.localhost:3100`). Platform
audit actions for operator security are prefixed `operator.` (`operator.signed_in`,
`operator.sign_in_failed`, `operator.signed_out`, `operator.mfa_*`, `operator.session_renewed`,
`operator.refresh_replayed`).

Reproducing what a customer sees in the customer app (NFR-20's customer half) waits for F15.
