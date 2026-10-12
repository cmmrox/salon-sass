# Backend rules (`apps/api`)

The isolation rules every table, endpoint and job must follow are in
`docs/architecture/multi-tenancy.md`. Versions are in `docs/architecture/tech-stack.md`.

## Contents

- Modules
- Inside a module
- API conventions
- Persistence and migrations
- Booking integrity
- Virtual threads
- Transactions, outbox and external calls
- Security
- Tests
- Verification commands

## Modules

One deployable, divided into modules by business area. The module list follows the BRD:

| Module | Owns |
| --- | --- |
| `platform` | Tenants, plans, entitlements, subscriptions, operator users. No `tenant_id` |
| `shared` | Tenant context, errors, time, money, audit trail, outbox, jobs. No business rules |
| `identity` | Staff accounts, roles, sessions, customer one-time-code sign-in |
| `catalogue` | Services, categories, variants, tax settings |
| `staff` | Professionals, working hours, leave, blocked time |
| `booking` | Availability, holds, bookings, waitlist, policies |
| `payment` | Gateway accounts, payments, refunds, receipts, checkout |
| `notification` | Templates, channels, delivery log, preferences |
| `customer` | Customer records, notes, consents |
| `reporting` | Read models and reports |
| `branding` | Brand settings, business profile, web addresses, app registry |

The base Java package is the placeholder in `docs/architecture/tech-stack.md`.

Layout of the project:

- One Maven module. Modules are packages, not Maven modules: `apps/api/src/main/java/<base package>/<module>/`.
- Tests mirror the source tree under `apps/api/src/test/java/`.
- Migrations: `apps/api/src/main/resources/db/migration/V<number>__<description>.sql`, numbered in
  sequence (`V1__baseline.sql`, `V2__create_tenant.sql`).
- An endpoint that belongs to no business module (such as the service's own metadata) lives in `shared`.

- A module exposes a small public interface (a facade) and its events. Another module calls the
  facade; it never imports the other module's entities, repositories or internal services.
- Concretely: another module may use only the types in a module's root package, and any module
  may use anything in `shared`. The architecture tests enforce exactly this.
- No module reads another module's tables. A report that needs both asks both, or reads a read
  model that the owning modules feed.
- Dependencies point one way. `shared` depends on nothing; nothing depends on `reporting`.
- An architecture test fails the build on a boundary violation. Do not weaken it to get a change in;
  move the code.

## Inside a module

```
<module>/
  api/             controllers, request and response records, mapping
  application/     use cases: one public method per thing a user can do; transactions start here
  domain/          entities, value objects, business rules; no Spring, no HTTP
  infrastructure/  repositories, adapters to external services
  <Module>Facade   the only type other modules may use
```

Controllers are thin: validate, call one use case, map the result. Business rules live in `domain`
and `application`, never in a controller, a repository or a mapper.

## API conventions

- JSON over HTTP under `/api/v1`. Plural nouns, no verbs in paths except for actions that are not
  CRUD (`/bookings/{id}/cancel`).
- Errors are problem details (`application/problem+json`) with `title`, `status`, `detail`, a stable
  machine-readable `code`, and `instance` when the application answered. Clients and tests branch
  on `code`, never on the message. The general codes are `not_found` (404), `bad_request` (400),
  `method_not_allowed` (405), `not_acceptable` (406), `unsupported_media_type` (415),
  `service_unavailable` (503), `request_rejected` (any other 4xx) and `internal_error` (any other
  5xx). A module adds its own for business outcomes (`slot_taken`, `hold_expired`,
  `policy_violation`).
- A request the server itself rejects before the application sees it (a badly encoded path, an
  unreadable header) is still answered as problem details, never as the server's HTML page.
- No error body carries a stack trace, a class name or a framework version.
- A missing record and another salon's record both return 404 with the same body.
- Lists are paginated, always. Sorting and filtering are explicit parameters.
- A request that creates a booking, a payment or a refund carries an `Idempotency-Key`. Repeating it
  returns the first result and never a second side effect.
- Request records are validated at the edge; a validation failure names the field.
- The OpenAPI document is generated from the controllers and committed. A drift check fails the
  build when the committed document or the generated client is stale.
- Never break a published field. Add a new one, keep the old one until the minimum supported app
  version no longer needs it.

## Persistence and migrations

- UUID primary keys. `created_at` and `updated_at` on every table. `@Version` on anything two
  people can edit at once.
- Open-session-in-view is off. A use case returns what the response needs; nothing lazy-loads
  during serialisation.
- Watch the query count. A list endpoint that issues one query per row is a defect.
- Money columns are `bigint` minor units plus a currency code. Never floating point.
- Time columns are `timestamptz`. A local date or time (opening hours) is stored as such with the
  salon's time zone applied when it is used.
- Flyway migrations are append-only. Never edit one that has been merged; write a new one.
- A migration must work while the previous version of the application is still running: add a
  nullable column or a new table first, backfill, switch the code, and remove the old shape in a
  later release.
- Every tenant table follows the checklist in `docs/architecture/multi-tenancy.md`, in the migration that creates it.

## Booking integrity

- A hold and a booking are one table with a state. An exclusion constraint forbids two active rows
  for the same tenant and professional with overlapping time ranges.
- The loser of a race gets the constraint violation, which the use case turns into `slot_taken`.
  Do not "check availability then insert" as the protection; the check is only for a good message.
- A hold has an expiry. Availability ignores an expired hold immediately; a job removes it later.
- Confirming a payment, converting the hold and scheduling notifications happen in one transaction.
- Every state change records who, when and why.

## Health and service metadata

- `GET /actuator/health` reports the database and Spring Boot's default components, showing status
  only. The `db` component is our own check (`DatabaseHealthIndicator`), not Spring Boot's: a
  `SELECT 1` under the pool's validation timeout. Health answers within about two seconds whether
  the database is down or accepts connections and stays silent, and recovers by itself when the
  database answers again.
- **Every wait on the database has a limit:** connection 2 s, validation 1 s, connect 2 s, login 2 s,
  socket 30 s. A statement that gets no answer for 30 s fails. Do not add a database call without one.
- **A request's database work waits at most 3 s** (`salon.database.request-timeout`). When the
  database is down, or accepts connections and stays silent, any endpoint that needs it answers 503
  `service_unavailable` within that limit, including a request already in flight, and recovers by
  itself. Migrations use the same connection limits.
- **The API finds a database that answers again within a quarter of a second**
  (`salon.database.reconnect-interval`, 250 ms). The pool opens its connections through
  `SteadyReconnectDataSource`, which retries an unreachable or silent database at that interval.
  Without it the pool's own wait between attempts grows to five seconds during a long outage, and
  recovery took up to five seconds more than the outage. Only the pool's own thread waits there; a
  request still waits at most the pool's connection limit. The API logs one warning when an outage
  starts and one line when a connection is opened again. The configuration builds the pool's own
  driver data source, so check both it and the pool's wait between attempts when the pool is
  upgraded.
- **The API refuses to start** when the role it connects as is a superuser, can bypass row-level
  security or owns a table. Migrations run as the owning role through their own connection.
- `GET /actuator/info` is served in the `local` profile only and returns 404 in any other.
- `GET /api/v1/meta` returns the service name, the build's version, the commit it was built from
  and the server time as a UTC instant. It does not touch the database.
- The JVM currently runs in the machine's time zone; instants in the API are UTC regardless. Time
  handling for business rules arrives in F01-S04.

## Virtual threads

- Request handling and scheduled work run on virtual threads. Blocking I/O is fine; that is the point.
- The database pool is now the limit, not the thread pool. Size it deliberately and keep
  transactions short.
- Put an explicit concurrency limit and a timeout on every call to an external provider.
- Do not rely on a thread-local surviving, and do not cache expensive objects in one. Carry the
  tenant and the user in the request scope.
- Do not hold a lock across I/O.

## Transactions, outbox and external calls

- One transaction per use case, opened in `application`.
- Anything that leaves the system (message, payment call, webhook) is written to the outbox table
  in the same transaction and sent afterwards, with retry and a dead-letter state.
- An incoming payment notification is verified (signature, amount, merchant, order), handled
  idempotently, and acknowledged only after it is stored.
- Every external call sits behind an interface with a real adapter and a local fake.

## Security

- Staff: password plus two-step verification (required for the Owner role). Customers: one-time
  code, no passwords.
- Permission checks are in the use case and are based on the role's permissions, not on the role's
  name.
- One-time-code requests are rate limited per number, device and address, with a tenant ceiling.
- Sensitive actions (refunds, cancellations, price and policy changes, exports, role changes,
  sign-ins, support sessions) write an audit entry with actor, time, tenant, and before and after.
- Gateway credentials are encrypted at rest and never returned by any endpoint.
- Never log a password, one-time code, token, card detail or full phone number.

## Tests

| Kind | For | Notes |
| --- | --- | --- |
| Unit | Domain rules: availability, deposit and refund calculation, policy windows, state changes | Plain JUnit, no Spring. Use a fixed clock |
| Integration | Use cases and endpoints against real PostgreSQL in a container | Includes the two-tenant isolation cases and the wrong-role cases |
| Concurrency | Anything that must happen once: holds, bookings, payment notifications | Fire simultaneous requests and assert exactly one succeeds |
| Architecture | Module boundaries | Fails the build |
| Contract | The OpenAPI document and generated client are current | Fails the build. `pnpm contracts:check` regenerates the document from the controllers, so it needs the JDK and Docker |

Integration tests are named `*IntegrationTest` and run in the same Maven phase as unit tests. One
PostgreSQL container serves the whole run.

A test asserts an outcome the spec promises. A test that passes when the feature is removed is not
a test: when you write one for a bug, watch it fail first.

## Verification commands

The commands for a stage are the ones in its `stage.yaml` under `verification.commands`. For the API
that means the Maven wrapper's `verify` (unit, integration and architecture tests) and the contract
drift check.
