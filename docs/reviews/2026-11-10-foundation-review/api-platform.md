# Platform review: tenancy, identity, operator console, cross-cutting (F01–F03)

Read-only review at `main` `e5ccf3aa`, 2026-11-10. Nothing was run: no tests, no builds, no servers.
Every finding is from reading code, migrations and infra files. "Verified" means the mechanism is
in the code as cited; impact figures (timings, rates) are estimates, not measurements.

Paths are relative to `the repository root`. `J/` stands for
`apps/api/src/main/java/com/salonplatform/`, `M/` for `apps/api/src/main/resources/db/migration/`.

## Verdict

The isolation core is sound and better built than most. I found **no path by which a request reads
or writes another salon's rows**. Row-level security is enabled and forced on all 49 tenant tables,
the application role cannot bypass it and the API refuses to start if it could, the tenant setting
is transaction-local, every direct SQL statement on a tenant table names `tenant_id`, and every
`SECURITY DEFINER` function has `PUBLIC` revoked. The operator console is properly separated.

What is not sound is the layer above it: **how the platform behaves under load, under abuse and
during a deployment**. The three findings that should be fixed before F08 are the staff sign-in
denial of service (H1), the event bus losing events during a rolling deployment (H2), and the
absence of any server-side database timeout (M1). The rest are scale ceilings and readiness gaps
for F11 and F15 that are cheap to fix now and expensive after those features build on them.

No rework of the foundation is needed for the roadmap. Several additive changes are.

## Findings

### H1. Staff sign-in hashes the password inside a pooled transaction, under a salon-wide lock, with a rate limit one address can multiply by the number of salons

Severity high. Confidence: mechanism verified, impact estimated.

- `J/identity/service/impl/IdentityWorkImpl.java:31-34`: every identity use case opens a tenant
  transaction and takes the salon's lock (`J/identity/repository/IdentityLock.java:30-35`,
  `IdentityGuardRepository.java:15-17`, `PESSIMISTIC_WRITE`).
- `J/identity/service/impl/StaffSignInServiceImpl.java:51-61`: inside that transaction and lock,
  `crypto.matchesPassword` runs PBKDF2-SHA256 at 600,000 iterations
  (`J/identity/adapter/IdentityCrypto.java:45-48,64-65`), also for an unknown identifier (dummy hash).
- The pool is 10 connections per instance, shared by every salon (`apps/api/src/main/resources/application.yaml:22`),
  with a 2 s wait for a connection.
- The address limit is a row of a tenant table (`M/V8__create_staff_identity.sql:27,46`), so it is
  per salon: one address gets 30 attempts in 15 minutes **on each salon**.
- The same hazard was found and fixed for operators, and the fix's own comment describes it:
  `J/platform/service/impl/OperatorSignInServiceImpl.java:81-89`.

Failure: salon slugs are public. One client posts wrong sign-ins to many salon hostnames. Each
holds a pooled connection for the length of a hash (roughly 0.2–0.5 s) and a CPU core. Ten in
flight exhaust an instance's pool; every other request on that instance, for any salon, waits 2 s
and is answered 503. Against one salon, requests queue behind the lock while each waiter also holds
a connection, and every signed-in request of that salon stalls behind each hash. With 1,000 salons
one address is allowed about 33 hashes per second without ever reaching a limit.

Smallest fix: do what `OperatorSignInServiceImpl` does (count and commit, hash outside any
transaction, record the outcome); bound concurrent hashes with a semaphore sized to the cores;
add one platform-level per-address limit (a platform table, or a Caddy rate limit on the sign-in
paths) so the ceiling does not multiply by salons. `changePassword` (`StaffSignInServiceImpl.java:90-104`)
has the same shape and needs the same treatment.

### H2. An event is marked delivered by an instance that has no subscriber for it, so adding a subscriber loses events during a rolling deployment

Severity high for F08/F11. Confidence: verified by reading code.

- `J/shared/outbox/impl/DeclaredEventTypes.java:14-36`: an instance claims every event type its
  build declares, subscriber or not ("A declared type with no subscriber is delivered at once").
- `J/shared/outbox/impl/EventSubscribers.java:66-80`: no subscriber means no failure, so the event
  becomes `sent`.
- `J/booking/service/impl/BookingChanges.java:76` already publishes `booking.BookingConfirmed.v1`.
  Its only future consumers (notification, reporting, payment) do not exist yet. The only
  `EventSubscriber` in the product today is a local test hook (`J/identity/local/CustomerMessageCapture.java:28`).
- `infra/scripts/stack-deploy.sh:9-16` replaces the two API instances one at a time, each with the
  dispatcher on.

Failure: F11 ships a subscriber for `BookingConfirmed.v1`. While api-1 is new and api-2 is old,
api-2 claims confirmed bookings' events and marks them delivered with nothing done. Those
customers get no confirmation and no reminders, and nothing is logged as an error. Decision 064
covers a type the old build does not know; it does not cover a known type gaining a subscriber.
The same happens whenever a second module subscribes to an existing type.

Smallest fix: an instance asks `outbox_due_events` only for types it has a subscriber for, and the
rule "a new subscriber to an existing type ships in a release after the dispatcher of every running
instance knows it, or the type gets a new version" goes into `docs/engineering/backend.md`. The
durable fix is the one `ARCHITECTURE.md` already names as the first split: run the dispatcher and
jobs as one worker deployment, so only one build delivers.

Related, same severity for F13: events have no order. Ids are random UUIDs, events are delivered
on up to four virtual threads at once (`J/shared/outbox/impl/OutboxConfiguration.java:50-52`), and
two events about one booking can be handled in either order or simultaneously. Give each event its
aggregate's id and a sequence (or the booking's version) before read models are fed from them.

### M1. No server-side statement, lock or idle-in-transaction timeout; the client's 3 s socket timeout abandons work without cancelling it

Severity medium now, high once reports exist. Confidence: verified absent (no `statement_timeout`,
`lock_timeout`, `idle_in_transaction_session_timeout` or `client_connection_check_interval` in
`apps/api/src/main`, `infra/` or the docs); consequence reasoned.

- `J/shared/persistence/RequestDatabaseLimit.java:31-33` sets a 3 s network timeout, applied at the
  start of every tenant and platform transaction (`TenantJpaDialect.java:44-51`,
  `PlatformDatabase.java:105-110`), jobs and deliveries included.

Failure: a statement that takes more than 3 s (a report, a large export table, a wait on the salon
lock or an advisory lock) is answered 503 and the pool discards the connection, but the PostgreSQL
backend carries on, or stays parked in its lock wait, holding its locks and its connection slot.
The pool opens a replacement, the user retries, and the same query runs again. Under H1 the
waiters pile up server-side. It also means no tenant transaction can ever run a statement longer
than 3 s, which F13 and the closure export will meet.

Smallest fix: `ALTER ROLE <app role> SET statement_timeout / lock_timeout /
idle_in_transaction_session_timeout` in `infra/postgres/provision-roles.sql` (a little under the
client limits), `client_connection_check_interval` on the server, and a separate, longer, explicit
limit for jobs and reports.

### M2. Every signed-in request takes a salon-wide exclusive lock in a write transaction

Severity medium. Confidence: verified.

- `J/identity/service/impl/SessionServiceImpl.java:43-49`: `me()` runs through `IdentityWork.run`,
  and `Principals.require` calls it (`PrincipalsImpl.java:20-30`) from every module
  (`BookingCallers.java:84-92`, `StaffScope.java:59-63`, `CatalogueScope`, branding, platform).

Consequences: every authenticated read is an `INSERT … ON CONFLICT` plus a `SELECT … FOR UPDATE`,
so it takes a transaction id and a WAL flush on commit; one salon's requests are serialised through
one row; a typical request makes four to five separate pooled transactions (registry lookup,
identity, default branch, linked team member, the use case); authenticated reads can never go to a
replica. Fine at today's size; it is the reason H1 hurts a whole salon.

Smallest fix: read the principal in a plain read-only transaction with no lock. Keep the lock for
the use cases that need mutual exclusion (staff creation and seats, last-Owner check, rate
counters, refresh rotation).

### M3. The outbox and the scheduler do work proportional to the number of salons on every tick

Severity medium (scale ceiling; matters for F11). Confidence: verified structure, cost estimated.

- `M/V15__create_salon_lifecycle.sql:293-314`, `M/V17__add_outbox_events.sql:37-54`,
  `M/V6__create_outbox_message.sql:118-131`: `outbox_due`, `outbox_due_events` and `outbox_backlog`
  each loop over every salon, setting it and querying it. `outbox_backlog` also loops closed and
  deleted tombstones and counts rows.
- `J/shared/outbox/impl/OutboxDispatcher.java:169-178`: all three run on every poll, every second,
  and every 50 ms while anything was delivered (`:61,210`), on each instance.
- `J/shared/jobs/JobScheduler.java:173-238`: one thread runs every job, salon after salon, one
  registry read and one transaction each. `booking-hold-expiry` runs every minute for every active
  salon (`J/booking/job/HoldExpiryJob.java:30-32`). A slow platform job (export, billing) delays
  every other job behind it. A job that runs past its 10-minute hold (`application.yaml:189`) is
  claimed by the other instance and runs twice at once.

At tens of salons this is invisible. At the "thousands of small salons" `ARCHITECTURE.md` plans
for, it is several thousand round trips per second while idle, and F11 adds a per-minute reminder
job and far more messages.

Smallest fix: let the dispatcher find due work in one indexed query. The clean way within the
isolation rules is a second, narrow policy for a dedicated dispatcher role that may select only
`id, tenant_id, status, next_attempt_at, lease_until, channel, kind, event_type`, plus an index on
`(next_attempt_at) WHERE status = 'pending'`. Sample the backlog meter every 30–60 s rather than
every poll. Run tenant jobs with bounded parallelism and each job on its own cadence.

### M4. The token-only tenant path, documented as the mobile app's, only works when `Host` is `localhost` or an IP literal

Severity medium for F15. Confidence: verified.

- `J/shared/web/TenantFilter.java:194-196,211-224`: `nativeAddress` matches only
  `localhost`, an IPv4 literal or a bracketed IPv6 literal.
- `api.<platform domain>` is read as the candidate slug `api` (`PlatformHostname.java:34-47`),
  which is reserved and resolves to no salon, so it answers 404. The bare platform domain also
  answers 404. `infra/stack/edge/Caddyfile.staging:65-89` serves only the platform domain and its
  subdomains, so an IP-literal host never reaches the API from outside.

`multi-tenancy.md` ("the app reaches one API host and names its salon by the signed tenant token")
therefore describes something no public hostname can do. The app can use each salon's own
subdomain instead, and sign-in, MFA and refresh already require that, so nothing is blocked; but
the decision should be made before F15 is specified.

Smallest fix: a setting naming the API's own hostnames (for example `api.<domain>`), treated as
the native address. On that path a suspended salon currently answers 404 rather than 403
`salon_suspended` (`TenantFilter.java:216-217`); fix it at the same time.

### M5. Sign-in limits let anyone lock a named person out, and let anyone fill a salon's immutable audit trail

Severity medium. Confidence: verified.

- `StaffSignInServiceImpl.java:53-58,117-124` and `J/identity/entity/RateBucket.java:68-76`: ten
  attempts per identifier per 15 minutes, counted from any address, successes included. The
  operator path is the same (`OperatorSignInServiceImpl.java:95-112`).
- Every refused attempt, limited ones too, writes an audit row
  (`StaffSignInServiceImpl.java:56,63-69`), and the tenant role cannot delete audit rows
  (`M/V5__create_audit_entry.sql:59-61`).

Failure: ten requests every 15 minutes with an Owner's email (often the salon's public contact)
keep that Owner out indefinitely. The same against an operator's email keeps the operator out of
the console during an incident. Separately, a loop of rate-limited requests writes
`identity.sign_in_limited` rows without bound into a table that is never pruned.

Smallest fix: limit on the pair (identifier, address) with a much higher identifier-only ceiling,
or exempt a device that has signed in successfully before; write the "limited" audit entry once
per bucket per window.

### M6. Sessions and spent refresh credentials are never removed, the refresh lookups have no index, and refresh is not rate limited

Severity medium. Confidence: verified.

- `M/V8__create_staff_identity.sql:8,48-49`: no index on `identity_session.refresh_hash`; the
  history index is `(tenant_id, session_id, credential_hash)`, useless for a lookup by hash alone.
  The operator tables have both (`M/V11…sql:44,59`).
- `J/identity/repository/AccessSessionRepository.java` `findFirstByRefreshHash`,
  `RefreshHistoryRepository.java:12`; used in `SessionServiceImpl.java:75-93`, which has no
  attempt counter.
- `J/identity/job/IdentityCleanupJob.java:41-46` removes challenges, steps and rate buckets, never
  sessions or refresh history. Each rotation adds a history row, roughly one per session every
  five minutes of use.

Failure: both tables grow for the life of the salon. Every refresh scans all of the salon's
sessions, under the salon lock. An unauthenticated caller posting random refresh tokens forces two
full per-salon scans per request with no limit.

Smallest fix: one migration adding `UNIQUE (tenant_id, refresh_hash)` and
`(tenant_id, credential_hash)`; delete sessions and history some days after expiry or revocation
in the cleanup job; count refresh failures per address.

### M7. Salon endpoints served from the platform connection are isolated by one hand-written predicate only

Severity medium (architectural; correct today). Confidence: verified.

- `J/platform/service/impl/SalonBillingServiceImpl.java:95-110`: `/api/v1/salon/invoices/{id}`
  reads any invoice by id on the platform role, then filters `invoice.salonId().equals(salonId)`
  in Java. `SalonSupportSessionServiceImpl.java:47-89` and `SupportEntryServiceImpl.java:53-137`
  are the same pattern.
- These tables name their salon `salon_id`, not `tenant_id`, deliberately, so they are outside
  row-level security and outside `DatabaseIsolationCatalogueIntegrationTest`.

Rule T-2 ("enforced twice") does not hold for salon-scoped platform data: invoices,
subscriptions, support sessions, exports. Every check I read is correct. The risk is the next
ones: message metering (F11), add-on modules (F18) and `tenant_domain` will all add salon-facing
reads on this connection, which can also read `operator_user` and every salon's registry row.

Smallest fix: put the salon predicate in the SQL of every store method a salon endpoint calls
(`invoiceById(id, salonId)`), and add an architecture rule that a class reachable from a
non-operator controller calls only store methods that take a salon id. Longer term, expose
salon-scoped platform data to the tenant role through `SECURITY DEFINER` functions keyed on
`app.tenant_id`, as `salon_entitlements()` already does.

### M8. Encrypted values carry no key identifier, and there is no recovery or offboarding path for any credential

Severity medium before a production release. Confidence: verified.

- `IdentityCrypto.java:95-111`: ciphertext is `nonce.ciphertext`, one key, no version. The same
  holds for operator MFA secrets and the export archive.
- No staff password reset by an Owner, no Owner or operator MFA recovery, and no product path to
  disable or re-credential an operator (only the offline bootstrap and a `local` hook). The docs
  say this is deliberate for now (`identity-and-access.md:54-55`, `operator-console.md:46-48`).

Failure: rotating `SALON_IDENTITY_ENCRYPTION_KEY` after a suspected leak makes every authenticator
secret unreadable, and there is no recovery flow, so every Owner and every MFA user is locked out
permanently. An Owner who loses a phone today can only be fixed by SQL as the owning role. A
departing support operator can only be disabled the same way.

Smallest fix: prefix stored ciphertext with a key id and accept a list of decryption keys, before
real data exists; decide the recovery policy the docs defer; add operator disable to the console.

### M9. The closure export is built and served whole in memory and stored as one `bytea`

Severity medium-low. Confidence: structure verified; failure suspected.

- `J/shared/lifecycle/TenantTableRows.java:28-41`: a table is read whole into a `List<String>`,
  image tables included (`catalogue_photo`, `staff_member_photo`, `branding_image`).
- `J/platform/service/impl/SalonExportServiceImpl.java:147-175` joins, encrypts and stores it;
  `ExportDownloadServiceImpl.java:95-121` loads, decrypts and returns the archive as `byte[]`, on
  one of four platform connections. `M/V15__create_salon_lifecycle.sql:150,178`.

Failure: a salon with a few hundred photos and a year of bookings and audit rows needs several
copies of its export in the heap at once; the download link needs no session and can be requested
concurrently; a table read that outlasts 3 s (M1) fails on every run. Deletion waits for the
export, so a salon whose export cannot be built is never erased.

Smallest fix: page the export by key, stream the download, and move the archive to object storage
when that arrives with the first feature that needs it.

### M10. Customer one-time-code abuse controls are per salon only (before F15)

Severity medium for F15; not live today (outside `local` the endpoint answers 503,
`CustomerAccessServiceImpl.java:103-105`). Confidence: verified.

- `CustomerAccessServiceImpl.java:106-128`: 3 per number, 10 per device, 20 per address per
  15 minutes, 1,000 per salon per day. A device is issued to anyone who sends no cookie
  (`CustomerAccessController.java:81-87`), so the device limit limits nothing. There is no
  platform-wide ceiling.
- A code is 6 digits with 5 attempts and a new challenge may be requested 3 times per 15 minutes,
  indefinitely: about 1,400 guesses a day against one number.
- A refresh credential presented twice revokes the session (`SessionServiceImpl.java:79-86`). A
  mobile client that retries a refresh whose answer was lost is signed out and must pay for
  another code.

Smallest fix, as part of F15's spec: a platform daily budget and per-address limit across salons;
a per-number failed-verification lockout that grows; app attestation or a challenge before a code
is sent; a short grace window in which the previous refresh credential returns the same successor.

### Low

- **L1. A support session's request audit is best effort and written after the response.**
  `J/shared/audit/SupportRequestAudit.java:40,61` ("Never throws"), called in a `finally` at
  `TenantFilter.java:147-151`. A database blip leaves an operator's read of a salon's data
  unrecorded, against T-10. Write the entry before serving the read, or fail the read.
- **L2. `/actuator/health` is public with its components.** `Caddyfile.staging:70` routes
  `/actuator/*` on every hostname; `application.yaml:100` shows components. Route only the
  readiness and liveness groups, or none, at the edge.
- **L3. Access tokens are refused if `iat` is in the future, with no tolerance.**
  `IdentityCrypto.java:166-167`. Two instances a fraction of a second apart reject a token the
  other just issued, and the BFF treats that 401 as a sign-out. Allow a few seconds.
- **L4. Tracing samples every request, and every tenant job logs one INFO line and one span per
  salon per run.** `application.yaml:120`, `JobScheduler.java:276`. A per-minute job across
  1,000 salons is 1.4 million lines and spans a day. Make sampling a setting; log per run.
- **L5. `catalogue_add_default_categories` has no "created in this transaction" guard**, unlike
  the identity and branding bootstraps (`M/V25__create_catalogue.sql:219-240` against
  `M/V12…sql:27-31`). The platform role can call it for any salon. Add the same `xmin` check.
- **L6. The operator console is reachable from the internet with no network restriction.** MFA is
  mandatory and the host check is exact, so this is defence in depth only: an address allow-list
  on `console.<domain>` at the edge.

## Readiness for what is planned

| Next | Verdict | What it needs |
| --- | --- | --- |
| F08 payment gateway notifications | Supported, with three traps | The notify URL is ours to set per payment, so use the salon's own hostname and the tenant resolves normally. (a) `ReadOnlyRoutes` refuses every mutating `/api` route of a read-only salon that is not on its allowlist (`J/shared/web/ReadOnlyRoutes.java:21-39,53-66`), and a suspended salon answers 403: a customer's payment for such a salon would be refused and lost. The notification route must be exempt and must store the notification whatever the salon's state. (b) If a platform-host endpoint is preferred, it needs a platform lookup from order to salon, then `TenantContext.callAs`; `ActiveTenants.findActive` refuses suspended salons. (c) `BookingConfirmed` consumers meet H2. |
| F11 reminders across salons | Works at small scale; fix first | H2 (lost events), M3 (per-tick cost), event ordering. `Outbox.enqueue(channel, payload, at)` already stores `at` as the due time (`OutboxMessage.java:105`), and `Outbox.cancel` exists, so a reminder can be a future-dated message cancelled on reschedule, with no new scheduler. Plan `message_allowance` exists (`M/V11…sql:113`); usage metering does not. |
| F15 customer principal in a shared app | Foundation present | The customer principal, its kind separation and per-salon accounts exist and are isolated; a customer of five salons has five accounts and five sessions, by design. Needs M4 decided, M10, and a client rule for 401s after rotation (a rotation invalidates the previous access token at once, `SessionAccess.java:86-88`). |
| F18 branches | Claim verified in schema; decisions outstanding | Every catalogue, staff and booking table from V21 on has `branch_id NOT NULL`, with branch-leading unique keys and indexes, and repository queries filter by branch. But: every use case hard-wires `branches.defaultBranch()` (about ten call sites) and no endpoint, `Principal` or staff account carries a branch; `identity_staff`, `audit_entry` and `outbox_message` have none. The schema models each branch with its own catalogue and its own team members; a shared catalogue with branch overrides, a professional working at two branches, and per-branch roles are each a modelling decision, not a column. Note the double-booking constraint is per `member_id`, so one person entered as two members in two branches is not protected. Take these decisions before F10 and F13 build on the single-branch shape. |
| Salon-owned domains | Additive | Resolution has one seam (`TenantDirectory`). The hostname is rebuilt from slug + platform domain in the address assertion (`ClientAddressAssertion.java:47`), the BFF's `requestHost` and origin check, and the email link origins; each needs the request's real hostname instead. No schema rework. |
| Role model | Adequate | Fixed roles mapped to permission strings in one enum (`J/identity/domain/StaffRole.java`), checked by permission, read from the database on every request. Per-branch roles need a staff-to-branch assignment table and a branch set on `Principal`: additive. |

## Checked and found sound

- **RLS coverage, enumerated.** 49 tables carry `tenant_id`; each has `ENABLE` and `FORCE ROW LEVEL
  SECURITY` and the `tenant_isolation` policy with `NULLIF(current_setting('app.tenant_id', true), '')`
  in both `USING` and `WITH CHECK`. No migration drops or disables a policy. The build asserts the
  same from the catalogue (`apps/api/src/test/java/com/salonplatform/DatabaseIsolationCatalogueIntegrationTest.java`).
- **Roles.** Three roles, none superuser, none `BYPASSRLS`; the owner owns the tables and the
  application and platform roles own nothing (`infra/postgres/provision-roles.sql:73-95`). Startup
  refuses otherwise (`ApplicationRoleCheck.java`, `PlatformDatabase.java:179-192`). Migrations run
  in a separate container with the owner's password; instances never receive it.
- **Tenant binding.** `Host` only, never a forwarded header (`application-staging.yaml`,
  `Caddyfile.staging:59`); non-canonical paths refused before any path check
  (`RequestPaths.java:27-48`); a bearer token's salon must equal the hostname's
  (`TenantFilter.java:245-246`, again in `SessionAccess.java:81-83`). Context is a `ScopedValue`;
  no `ThreadLocal`, `@Async`, `@Scheduled` or cache anywhere in the product code.
- **Per-transaction setting.** `set_config(…, true)` at every transaction start
  (`TenantJpaDialect.java:31-51`); no session-level `SET` anywhere; no salon means no rows.
- **Direct SQL.** No JPA native query. Every `JdbcClient` statement on a tenant table names
  `tenant_id` (scripted check over every file outside `platform` that uses it; `platform`'s stores
  use the platform connection, see M7).
- **`SECURITY DEFINER` functions.** All 28 pin `search_path`, revoke `PUBLIC`, and are granted to
  exactly one role. Cross-salon ones return ids and counts only; erasers refuse a salon not past
  retention; bootstraps refuse a salon not created in the calling transaction.
- **Background work.** Jobs and deliveries run one salon at a time through `TenantContext.callAs`
  in their own transaction; suspended salons are skipped; provider calls run with no transaction
  open, a time limit, a concurrency limit and a breaker.
- **Sessions and tokens.** PBKDF2-SHA256 600k with a per-hash salt; fixed HS256, issuer, audience
  and kind checked; session, account state, role and MFA read from the database on every request;
  refresh rotates with family revocation on reuse; password change, removal, Owner promotion and
  MFA enrolment revoke sessions; TOTP steps single-use; refused attempts commit their counters.
  Staff, customer, operator and support credentials use different keys, issuers and kinds and
  cannot stand in for one another.
- **Authorisation, enumerated for identity and platform.** Every operator use case calls
  `access.require`/`check`/`authenticated` inside its transaction; every salon-facing platform use
  case calls `principals.require`. The only unauthenticated routes are sign-in, MFA, refresh, the
  customer code routes, the signed provider event, the export token link, `meta` and health. A
  grep-level pass over F04–F07 services found a permission or an explicit `publicly()` on every
  use case.
- **Operator console.** Exact-host filter before authentication; platform tables unreachable by
  the tenant role; provisioning is one platform transaction (registry, Owner, brand, module
  defaults, subscription, audit, receipt) with idempotency; support access is a time-limited,
  salon-bound, read-only credential checked against its stored session on every request, with
  mutations refused in the filter.
- **Suspension and closure.** Read from the registry on every request, never cached; enforced for
  hostname requests, token-only requests, support credentials, jobs and the outbox; closure is
  irreversible in the database.
- **Module boundaries are enforced, not conventional.** `ModuleBoundariesTest` runs Spring
  Modulith `verify()` plus ArchUnit rules, with table and path ownership tests.
- **Migrations.** V1–V33 in sequence, additive; no role created in a migration; grants go to
  placeholders; destructive statements are limited to re-creating functions and swapping check
  constraints in the same transaction.
- **Error contract, secrets, build.** Problem details with stable codes generated into the
  contract; no stack traces or class names; no fallback keys outside `local`, and startup refuses
  equal keys; OpenAPI generated and drift-checked; image runs as a non-root user.
- **CSRF and CORS.** The API authenticates by bearer token only and configures no CORS, so there
  is no ambient credential to forge with. The BFF checks exact `Origin` and Fetch Metadata and
  sets host-only, HttpOnly, SameSite=Lax cookies (`apps/web/src/features/identity/server/identity-web.ts:54-67,124-126`); I skimmed it only.

## Not verified

- Nothing was executed. Hash timing, per-tick cost and the orphaned-backend behaviour in M1 are
  reasoned from the code and from how PostgreSQL and its driver behave, not measured.
- That Hibernate's `@TenantId` filters `findById`. Row-level security makes it moot, and the
  repository has its own "Hibernate filter alone" test, which I did not run.
- Whether the pinned versions (Spring Boot 4.1.1, Tomcat 11.0.27, Jackson 3.1.7, PostgreSQL 18.6,
  springdoc 3.1.1, `java-otp` 1.0.0) are current or in support. No online check was made. The
  Tomcat and Jackson pins override Spring Boot's and must be removed when Boot passes them, as the
  pom says.
- The web BFF beyond a skim, and F04–F07 beyond the authorisation grep: both belong to the other
  half of this review.
- Any real environment: no `.env` file, staging host or secret store was read.
