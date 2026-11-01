# Backend rules (`apps/api`)

The isolation rules every table, endpoint and job must follow are in
`docs/architecture/multi-tenancy.md`. Versions are in `docs/architecture/tech-stack.md`.

## Contents

- Modules
- Inside a module
- Provided interfaces
- Events between modules
- Ownership of tables and paths
- API conventions
- Persistence and migrations
- Code generation
- Clean code
- Booking integrity
- Health and service metadata
- Time
- Virtual threads
- Transactions, outbox and external calls
- Logs, traces and meters
- Local test hooks
- Security
- Tests
- Verification commands

## Modules

One deployable, divided into modules by business area. The module list follows the BRD:

| Module | Owns |
| --- | --- |
| `platform` | Tenants and their provisioning/contact profile, plans, entitlements, subscriptions, operator users. No `tenant_id` |
| `shared` | Tenant context, errors, time, money, audit trail, outbox, jobs. No business rules |
| `identity` | Staff accounts, roles, sessions, customer one-time-code sign-in |
| `catalogue` | Services, categories, variants, tax settings |
| `staff` | Professionals, working hours, leave, blocked time |
| `booking` | Availability, holds, bookings, waitlist, policies |
| `payment` | Gateway accounts, payments, refunds, receipts, checkout |
| `notification` | Templates, channels, delivery log, preferences |
| `customer` | Customer records, notes, consents |
| `reporting` | Read models and reports |
| `branding` | Brand settings and published salon presentation (business profile, draft and published), branches and their public contact details, web addresses, app registry; canonical provisioning/contact profile remains in `platform` (decision 042, 083) |

The base Java package is the placeholder in `docs/architecture/tech-stack.md`.

Layout of the project:

- One Maven module. Modules are packages, not Maven modules: `apps/api/src/main/java/<base package>/<module>/`.
  Spring Modulith (`docs/architecture/tech-stack.md`) reads each direct sub-package of the base
  package as a module.
- Tests mirror the source tree under `apps/api/src/test/java/`.
- Migrations: `apps/api/src/main/resources/db/migration/V<number>__<description>.sql`, numbered in
  sequence (`V1__baseline.sql`, `V2__create_tenant.sql`).
- An endpoint that belongs to no business module (such as the service's own metadata) lives in `shared`.

The rules between modules (CR-021). They exist so that any module except `shared` could later run as
a separate service without its callers or the web and mobile clients changing:

- A module's root package is its public surface and holds only **provided interfaces**, the
  **records** they take and return, and its **event records**. No class with behaviour, no entity,
  no Spring annotation other than `package-info.java`'s module declaration.
- Another module uses only that root package, and any sub-package the owning module declares as a
  named interface (`@NamedInterface`). Every sub-package of `shared` that other modules use is a
  named interface. Each module declares the modules it may use (`allowedDependencies` on
  `@ApplicationModule`).
- No module reads another module's tables. A report that needs both asks both, or reads a read
  model that the owning modules feed through events.
- Dependencies point one way. `shared` depends on nothing; nothing depends on `reporting`.
- Spring Modulith's `verify()` and the remaining ArchUnit rules fail the build on a violation. Do
  not weaken them to get a change in; move the code.
- `shared` holds technical capabilities only (tenant context, errors, time, money, audit, outbox,
  jobs), organised by capability rather than by the business-module folders: `tenancy`, `web`
  (problem details, request pipeline, service metadata), `persistence`, `secrets`, `outbox`,
  `jobs`, `audit`, `time`, `mapping`, `entitlements`, `lifecycle`, `observability`, `local`, and an
  internal `config` (server, OpenAPI and module-ownership configuration). Each capability other
  modules use is a named interface; no `shared` package is named `application` or
  `infrastructure`. A business contract never moves into `shared` to avoid a dependency cycle; invert the
  dependency with an event instead. The one exception is `shared.entitlements.SalonEntitlements`,
  the plan limits every module enforces inside its own transaction under the registry's share lock:
  `platform` implements it, and neither an event (it must answer and lock now) nor a move into
  `platform` (a cycle with `identity`) can replace it (decision 057).

## Inside a module

The owner's chosen layout (CR-022): the module's API in its root package, and inside the module the
Spring folder names every Java developer and coding agent already knows. A folder exists only when
the module has something to put in it.

```
<module>/
  package-info.java  @ApplicationModule(allowedDependencies = {...}) and the module's description
  <Name>.java        the module's API: provided interfaces, their records, and event records
                     (<Thing><PastTense>, versioned). Nothing else lives in the root
  inprocess/         @NamedInterface: contracts that must join the caller's transaction (only when needed)
  controller/        REST controllers; thin
  dto/               this module's request and response records (never used by another module)
  mapper/            MapStruct response mappers on ResponseMapperConfig
  service/           the service interfaces (FooService): one method per thing a user can do,
                     taking and returning records and ids, never entities
    impl/            their implementations (FooServiceImpl, @Service), and the implementations of
                     the module's root API interfaces; transactions start here
  entity/            JPA entities, with their behaviour
  domain/            pure business rules and value objects: no Spring, no JPA, no HTTP
  repository/        Spring Data repositories and the named direct-SQL exceptions: the only folder
                     that touches the database (the tenant-isolation audit point)
  adapter/           calls to external systems and providers, crypto and secret holders
  job/               scheduled and background work
  config/            Spring configuration, filters, settings classes
  event/             listeners for other modules' events
  local/             test hooks served only in the local profile (see "Local test hooks")
```

Every sub-folder is internal to the module: Spring Modulith fails the build if another module uses
anything but the root package or a declared named interface. Controllers are thin: validate, call
one service, map the result.

**Services are an interface first, then its implementation** (the owner's rule, decision 067):

- Each business service is an interface `service/FooService` with its implementation
  `service/impl/FooServiceImpl`. Write the interface first: its methods are the use cases, in the
  business's words, with records, ids and DTOs in their signatures (input records may be nested in
  the interface), never an entity or a repository type.
- `@Service` goes on the implementation, and the implementation starts the transaction, through
  the module's transaction runner (`IdentityWork`, `OperatorWork`, `PlatformDatabase`) or
  `@Transactional`; never on the interface. A method that joins its caller's transaction says so in
  its Javadoc. Dependencies come through the constructor (`@RequiredArgsConstructor`).
- Controllers, jobs, listeners and other services inject the interface, never an `Impl` class.
- The module's root API interfaces (what other modules call), and `shared` contracts a module
  implements (`SalonEntitlements`, decision 057), are implemented in `service/impl/` too, named
  `<Interface>Impl`. Another module only ever sees the interface.
- In `shared`, each capability package holds its API (`Outbox`, `AuditTrail`, ...) and its
  internals sit in `<capability>/impl/`, package-private, outside the named interface.
- Helpers that are not services (calculators, validators, policies, a store's row records) are not
  `Impl` classes and get no interface: they live in `domain/` when pure, or stay package-private
  beside the implementation that uses them, in `service/impl/`. Every public class in
  `service/impl/` implements an interface.
- An external provider with several adapters (SMS, email, payment) is an interface in the module
  with one class per adapter in `adapter/` (and `local/` for the local fake), named for the
  provider, not `Impl`.

Business rules live in `entity`, `domain` and `service`, never in a
controller, a repository or a mapper. Classes in the root are interfaces, records, enums and
exceptions only; a class elsewhere is package-private when nothing outside its folder needs it.
The records a controller reads as a request body or returns as a response, at any depth of the
response, are top-level types in `dto/`, not nested in a controller or service (decision 061). A
record that is not an API shape stays nested in the class that defines it: `private` when only that
class uses it, otherwise visible within its module (a store's row read by services, a service's
input built by its controller), and never used by another module except through a named interface. `shared`'s local test hooks keep
their records in `shared/local/dto/`. The cases of a sealed result type count as one contract, and a
record declared inside a method is outside these rules. An architecture test refuses any other sub-folder name in a business module, a
mapper outside `mapper/`, a controller outside `controller/` or `local/`, an `@Service` class that
is not in `service/impl/` or does not implement an interface of its module, a public class in
`service/impl/` that is not an implementation, `@Transactional` on an interface, a controller that
depends on an implementation class, and a class named `*Impl` outside `service/impl/` (except the
Spring Data fragment `NewEntitiesImpl` and MapStruct's generated mappers). Moving a
class changes its logger name, which is its package.

## Provided interfaces

When another module needs something from this one, this module offers it as a Java interface in its
root package. The rules make the interface replaceable later by an HTTP client with the same
signature:

- Parameters and results are records of values and ids. Never an entity, a repository type, a
  `Pageable` of entities or a lazily loaded object. Another module's object is referred to by its id.
- The records are not REST response shapes: no OpenAPI annotation in the root package. A controller
  that returns the same data has its own response record in `dto/`.
- The tenant is never a parameter. The implementation reads it from `TenantContext`, as an
  extracted service will read it from the signed token.
- An implementation opens its own transaction and does not join the caller's. A contract that
  genuinely must join the caller's transaction (it writes in the same unit of work, or holds a lock
  until the caller commits) is declared **in-process only**: it sits in a named interface called
  `inprocess`, its Javadoc says why, and it is never exposed remotely. If its module is ever
  extracted, the contract becomes an event with a compensating action, not a remote call.
- A state change that another module reacts to is an event (next section), not a call into that
  module.
- No `@HttpExchange` or other HTTP annotation on a provided interface. On extraction, the calling
  side gets an adapter implementing the same interface over a declarative HTTP client, and the
  adapter is where timeouts, 404 and remote failure are translated.

## Events between modules

- An event is an immutable record in the publishing module's root package, named for what happened
  (`BookingConfirmed`), carrying ids and the values a consumer needs, never an entity. Its type name
  carries a version (`booking.BookingConfirmed.v1`); a published version is never changed, only
  superseded.
- Events are written to the outbox in the publisher's transaction (kind `event`), so an event exists
  exactly when the change that caused it was committed. Spring Modulith's event publication registry
  is not used: it has no tenant column and no row-level security.
- Each consuming module registers a subscriber for the event types it handles. Delivery is at least
  once, for one salon at a time with that salon's context set, with the outbox's retry and dead-letter
  rules. A consumer records each message it has processed and ignores a repeat, in the same
  transaction as its own change.
- Use an event when another module reacts to a change (booking confirmed: send confirmations, update
  reports). Use a provided interface when the caller needs an answer now (availability needs staff
  hours and service durations).
- **How it is built:** an event record carries `@EventType("<module>.<Name>.v<n>")` and is
  published with `Outbox.publish`, which joins the caller's writable tenant transaction and stores
  the row with kind `event`, the reserved channel `event`, the type name, the JSON payload (strict
  reading: an unknown or missing field is refused), the salon and the trace. A subscriber implements
  `EventSubscriber` for one event type and lives in its module's `event/` folder (a local test hook's
  subscriber lives in `local/`). Each subscriber runs in its own transaction with the salon set, and
  its change commits with its row in `shared_processed_event` (salon, event, subscriber), so a repeat
  delivery is ignored; the event is delivered when every subscriber has processed it. A subscriber's
  work counts against the outbox's 10-second provider time limit.
- **Only known types are claimed** (decision 064): at startup an instance collects the `@EventType`
  records of its build and asks `outbox_due_events` for due events of those types only. An event of
  a type the instance does not know stays pending, unattempted, for an instance that knows it, so a
  rolling deployment never loses a newer event; it shows in the waiting and oldest-due meters
  meanwhile. A declared type with no subscriber is delivered at once. An event record is therefore
  kept in the code until none of its events is pending anywhere; retiring a type means deleting its
  pending rows deliberately.
- Meters: `salon.outbox.deliveries` carries a `kind` tag (`delivery` or `event`); the waiting, dead
  and oldest-due meters count both kinds.

## Ownership of tables and paths

- Every table belongs to exactly one module, declared in the ownership map that the architecture
  tests read, and only that module's code and migrations touch it. A table created from CR-021 on is
  named with its module's prefix (`catalogue_service`, `booking_hold`); existing tables keep their
  names, because a rename breaks the rule that a migration works while the previous version runs.
- No foreign key crosses modules, except `tenant_id` to the platform's tenant. Another module's row
  is held as its id.
- Every path under `/api/v1` belongs to exactly one module: each module declares its path prefixes
  in `apps/api/src/main/resources/module-ownership.yaml`, beside its tables, and a path belongs to
  the module with the longest matching prefix (`/api/v1/salon` is `branding`'s,
  `/api/v1/salon/invoices` is `platform`'s; decision 058). A new module prefers a top-level segment
  of its own. Every operation is tagged with its module's name in the OpenAPI document, and the
  generated client is exported per module (`@salon/api-contracts/<module>`). This is what lets the
  edge send one module's paths to a separate service without a client noticing.
- Each module declares the settings prefixes it owns in `module-ownership.yaml`, and no module reads
  a setting under another module's prefix; any module may read `shared`'s. A new module uses
  `salon.<module>.*`. Existing prefixes keep their names, because a setting's name is also its
  environment variable (`SALON_EXPORT_KEY`): `platform` owns `salon.operator`, `salon.billing`,
  `salon.export`, `salon.support` and `salon.retention`; `shared` owns `salon.platform-domain`,
  `salon.database`, `salon.outbox`, `salon.jobs`, `salon.server` and `salon.platform.datasource`
  (decision 059).
- Secret keys are checked across modules without one module reading another's settings: each
  key-holding class declares its keys through `shared.secrets`, and startup is refused if any two
  are equal (compared in constant time; the message names the purposes, never a value).
- The ownership map also gives each module a description, used as its OpenAPI tag text, and each
  module has a springdoc `GroupedOpenApi` bean (`/v3/api-docs/<module>`, local and test profiles
  only); a test fails when a module in the map has no bean. The ownership tests cover tables, not
  SQL functions, and their SQL scan cannot see a table name built at run time.

## API conventions

- JSON over HTTP under `/api/v1`. Plural nouns, no verbs in paths except for actions that are not
  CRUD (`/bookings/{id}/cancel`).
- Errors are problem details (`application/problem+json`) with `title`, `status`, `detail`, a stable
  machine-readable `code`, and `instance` when the application answered. Clients and tests branch
  on `code`, never on the message. The general codes are `not_found` (404), `bad_request` (400),
  `method_not_allowed` (405), `not_acceptable` (406), `unsupported_media_type` (415),
  `service_unavailable` (503), `request_rejected` (any other 4xx) and `internal_error` (any other
  5xx). `salon_suspended` (403) is the answer of every endpoint that needs a salon when the
  hostname's salon is suspended. Every code is listed once, in `ErrorCode`, and the OpenAPI
  document's `Problem.code` enumeration is generated from it (CR-001), so a new code reaches the
  contract before the API can return it. A module adds its own for business outcomes (`slot_taken`, `hold_expired`,
  `policy_violation`).
- A request the server itself rejects before the application sees it (a badly encoded path, an
  unreadable header) is still answered as problem details, never as the server's HTML page.
- No error body carries a stack trace, a class name or a framework version.
- Every problem body carries `traceId`, the request's trace id (32 lowercase hex characters), which
  is the `trace.id` of that request's log lines. Only `Problems` puts a property on a problem body,
  and its `code` always comes from `ErrorCode` (a test holds both).
- A missing record and another salon's record both return 404 with the same body.
- Lists are paginated, always. Sorting and filtering are explicit parameters.
- A request that creates a booking, a payment or a refund carries an `Idempotency-Key`. Repeating it
  returns the first result and never a second side effect.
- Request records are validated at the edge. A validation failure answers 400 `bad_request` with
  `errors: [{ field, detail }]`, one entry per failing field.
- Request bodies are read strictly: no coercion between JSON types (`"5"` is not 5, `1.5` is not 1,
  `1` is not `true`). Each such value is a validation failure naming its field.
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

**Spring Data JPA is the default (CR-021).** Every tenant aggregate is an entity with a Spring Data
JPA repository: one repository per aggregate root, behaviour on the entity (`reprice()`, not
`setPrice()`), `@Version` on the root as a wrapper `Long` (null means new, so saving a new entity
issues no extra select). An entity with an assigned id and no `@Version` is created through the
repository fragment `shared.persistence.NewEntities` (`persist`, always an insert), because
`save()` would merge it: an extra select and a different instance than the one the caller keeps
changing. Its implementation is named `NewEntitiesImpl` because Spring Data finds fragment
implementations by that suffix; it is the one `Impl` name outside `service/impl/`. Lists read through JPQL or
record projections, paginated.

Direct SQL through `JdbcClient` is allowed only for these named exceptions, each in
`repository/` (or in `shared` for the shared capabilities):

1. The platform database pool (`PlatformDatabase`), which is deliberately not a `DataSource` bean
   or a transaction manager, so that `@Transactional` can never pick the platform role by accident.
2. Calls to the `SECURITY DEFINER` functions (`tenant_by_*`, `active_tenant_ids`, `outbox_due`,
   `outbox_due_events`,
   `job_claim`, `job_finish` and the per-module erasers).
3. Leasing and locking: outbox and job claims (`FOR UPDATE SKIP LOCKED`, conditional status
   updates), and a per-salon lock row created with `INSERT ... ON CONFLICT DO NOTHING` (identity's
   `IdentityLock`), where a repository would make two first uses fail instead of both waiting; and
   recording a processed event in `shared_processed_event` in the subscriber's transaction.
4. Set-based bulk work: erasure, cleanup, lifecycle row deletion.
5. Reporting read models and exports.

Hibernate's tenant filter does not apply to direct SQL, so row-level security is its only guard.
Every exception therefore runs inside a tenant transaction, states `tenant_id = ?` explicitly in its
SQL where it reads or writes a tenant table, and has a test with row-level security as the only
guard. No `JdbcTemplate`, no jOOQ, no Spring Data JDBC, and no second entity manager factory.

## Code generation

Lombok and MapStruct remove repetitive code; they never hide a rule (CR-021). Both run as
annotation processors listed in `annotationProcessorPaths` (Lombok, then `lombok-mapstruct-binding`,
then MapStruct), and Lombok is not packaged in the jar.

**Lombok** is allowed only as follows, and `apps/api/lombok.config` refuses every other Lombok
feature at compile time (including `@AllArgsConstructor` everywhere):

| Allowed | Where |
| --- | --- |
| `@Getter` | Entities and value objects |
| `@NoArgsConstructor(access = AccessLevel.PROTECTED)` | Entities (JPA needs it; nobody else may use it) |
| `@RequiredArgsConstructor` | Spring beans with constructor injection |
| `@Slf4j` | Any class that logs |
| `@Setter` on a single field | A plain attribute with no rule attached |

Denied: `@Data`, `@Value`, `@EqualsAndHashCode`, `@ToString` (a generated `toString` would log
password hashes, secrets and personal data), `@Builder` and `@AllArgsConstructor` on entities,
class-level `@Setter`, `@SneakyThrows`, `@With`, and any Lombok annotation on a record. No entity
has a setter for its id, tenant id, version or creation time; an architecture test checks the
compiled classes. An entity is created by a static factory or constructor that establishes its
rules, and changed through methods named for what they do.

**Records** are the form of every request, response, provided-interface type and event. Lombok adds
nothing to them.

**MapStruct** mappers live in `mapper/` and map one way: from an entity or a provided record to a
response record. No mapper writes to an entity (no request-to-entity mapping, no `@MappingTarget`
entity), no mapping expression holds a business rule, and no mapper maps another module's types
other than its provided records. Every mapper names the shared configuration,
`@Mapper(config = ResponseMapperConfig.class)` (`shared.mapping`), which makes unmapped target
properties and lossy conversions errors and the mapper a Spring bean with constructor injection;
an architecture test refuses a mapper that does not. The policies are not set as compiler
arguments: on JDK 25 javac warns about processor options that no processor consumed, which
`-Werror` turns into a failure in every compilation without a mapper (decision 056). A mapping that
is shorter by hand than as a mapper stays by hand.

## Clean code

The owner's standard (CR-021): code a person can read without its author. These hold for every
change, and a stage leaves the code it touches meeting them.

- **Names** use the business's words from `docs/architecture/glossary.md` and say what a thing is
  or does. No abbreviations, no `Manager`, `Helper` or `Util` names; a service implementation is
  its interface's name plus `Impl` (`BookingServiceImpl`), and nothing else is named `Impl`.
- **Small units.** A use-case class serves one capability; a class that grows past about 400 lines,
  or a method past about 40, is split along the business's seams unless the reason it is not is
  written beside it. A long parameter list becomes a record.
- **Nothing dead.** No commented-out code, no unused class, method, field, parameter, import,
  dependency, setting or file, and no code kept "in case". Version control keeps history. A `TODO`
  names the stage, change request or bug that will resolve it, or it is not written.
- **Comments say why**, not what: a business rule's source, a framework constraint, a decision's
  number. Javadoc on every provided interface, event and public record of a module's root package.
- **Errors** are explicit: a business outcome is an `ApplicationProblemException` with its
  `ErrorCode`, never a swallowed exception, a `null` meaning failure, or a generic `RuntimeException`.
  Return `Optional` for "may be absent", never `null`.
- **Immutability by default:** records, `final` fields, unmodifiable collections; an entity changes
  only through its methods.
- **One format.** Spotless with `palantir-java-format` formats every Java file and removes unused
  imports; `./mvnw spotless:check` is part of the build. Compiler warnings are errors
  (`-Xlint:all -Werror`, with each suppression justified where it is written).
- **Tests read as specifications:** one behaviour per test, named for the outcome
  (`refusesAHoldThatOverlapsAnother`), arrange-act-assert, test data built by named fixtures.

## Booking integrity

- A hold and a booking are one table with a state. An exclusion constraint forbids two active rows
  for the same tenant and professional with overlapping time ranges.
- The loser of a race gets the constraint violation, which the use case turns into `slot_taken`.
  Do not "check availability then insert" as the protection; the check is only for a good message.
- A hold has an expiry. Availability ignores an expired hold immediately; a job removes it later.
- Confirming a payment and converting the hold happen in one transaction, deliberately across
  `payment` and `booking` through an in-process-only contract. That transaction writes a
  `BookingConfirmed` event to the outbox; `notification` schedules the messages when it consumes
  the event, not inside the confirming transaction (CR-021).
- Every state change records who, when and why.
- Service time is a list of segments (active, processing, buffer), copied onto the booking when it
  is made. The exclusion constraint covers the segments that occupy the professional; a processing
  segment occupies only the resources it names (decision 074). A price or duration change never
  rewrites a booking's copy (CAT-04).
- States change only through the booking module's state machine, which refuses a change the rules
  forbid: completed only through checkout, no-show only after the start time, cancellation only
  before it (BKG-06, decision 075).
- Money for a booking or sale is one append-only payment record: charges, payments by tender with
  their references, refunds, and deposits held as an amount owed to the customer. No module keeps a
  separate balance; occasion balances and gift cards are entries in the same record (decision 076).
- From F04, every salon-operations table carries `branch_id` beside `tenant_id`. R1 has one default
  branch per salon (decision 073). Branches are `branding`'s; another module holds the id and checks
  it through `SalonBranches` (decision 083, `docs/architecture/multi-tenancy.md`, "Adding a table").
- Phone numbers are stored in E.164 and checked with libphonenumber against the region in
  configuration (`salon.branding.phone-region`, `LK` by default); screens show the national format
  (decision 084, NFR-13). A WhatsApp number must be a mobile number of that region.

## Health and service metadata

- `GET /actuator/health` reports the database and Spring Boot's default components, showing status
  only. The `db` component is our own check (`DatabaseHealthIndicator`), not Spring Boot's: a
  `SELECT 1` under the pool's validation timeout. Health answers within about two seconds whether
  the database is down or accepts connections and stays silent, and recovers by itself when the
  database answers again.
- **Every wait on the database has a limit:** connection 2 s, validation 1 s, connect 2 s, login 2 s,
  socket 30 s. A statement that gets no answer for 30 s fails. Do not add a database call without one.
- **A held database connection has a 3 s network wait timeout** (`salon.database.request-timeout`).
  This is the driver's configured wait, not an exact three-second HTTP stopwatch deadline: request
  scheduling, connection acquisition and response handling add time. The endpoint outage deadline
  remains five seconds, as F01-S02 AC-10 and CR-003 require: a stopped or silent database produces
  503 `service_unavailable` within five seconds, including in-flight calls, and the API recovers
  within five seconds of actual database availability without restarting. Record HTTP and control
  timing separately; do not call an HTTP measurement a database-only wait. Migrations use the same
  connection limits.
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
  For real checkouts, a root `.git`-present Maven profile uses native Git lookup from the build's
  checkout, including linked worktrees. A source archive without `.git` retains the existing
  unknown-commit fallback. Verify the embedded build revision and HTTP metadata against the
  actual API source; later documentation or web-only commits do not rewrite an already built jar.
- `/actuator/health/readiness` and `/actuator/health/liveness` expose only the respective
  application state. On shutdown readiness becomes `OUT_OF_SERVICE` first, with a five-second
  serving delay before graceful shutdown; the edge checks every second and removes the instance
  while accepted calls finish. The full health endpoint remains the database diagnostic.
- In the stack's `staging` profile, migrations run once in a separate container with the `migrate`
  profile and owning-role credentials; application instances never migrate or receive that
  password. Forwarded client address and protocol are trusted only from `SALON_TRUSTED_PROXIES`,
  the edge network. Forwarded host and port are disabled: tenant resolution always uses `Host`.
  OTLP traces and meters are exported to `SALON_OTLP_ENDPOINT`; the collector is internal.
- The API sets its default time zone to UTC when it starts (`UtcTimeZone`), whatever the machine's
  is, and Hibernate talks to the database in UTC. Instants in the API are UTC, written with `Z`.

## Time

- Every time the application decides on (when something is due, when it expires, when it happened)
  comes from the application's one `Clock` bean, never from the database's `now()`. Tables written
  by the application's clock (`audit_entry`, `outbox_message`) have no `DEFAULT now()`.
- A salon's time zone is `tenant.time_zone`, an IANA name the database checks against its own list
  (`is_iana_time_zone`), and the tenant context carries it. One shared service converts between an
  instant and the salon's local date and time: in the hour clocks skip, a local time moves forward
  by the length of the gap; in the hour that repeats, the earlier instant is used.
- A local time shown to a caller is an offset date-time in the salon's zone, with the offset always
  written `+HH:MM`, never `Z`.

## Virtual threads

- Request handling and scheduled work run on virtual threads. Blocking I/O is fine; that is the point.
- The database pool is now the limit, not the thread pool. Size it deliberately and keep
  transactions short.
- Put an explicit concurrency limit and a timeout on every call to an external provider.
- Do not rely on a thread-local surviving, and do not cache expensive objects in one. Carry the
  tenant and the user in the request scope.
- Do not hold a lock across I/O.

## Transactions, outbox and external calls

- One transaction per use case, opened in `service`.
- Anything that leaves the system (message, payment call, webhook) is written to the outbox table
  in the same transaction and sent afterwards, with retry and a dead-letter state.
- **The outbox** (`shared.outbox`): writing a message joins the caller's transaction and refuses to
  run outside one. The dispatcher polls every second, learns what is due across salons only through
  `outbox_due` (ids, nothing else), and handles each message in a transaction with its salon set:
  it claims the message with a lease, commits, calls the provider with no transaction open and the
  message id as the idempotency key, and records the outcome in a new transaction. Backoff starts at
  30 s and doubles to 1 h; after 10 attempts the message is `dead` with its last error. A delivery
  interrupted by a stop is attempted again when its lease (60 s) runs out. A suspended salon's
  messages wait, unattempted, until it is active again.
- Identity code work may end `cancelled` on resend, consumption or expiry. `Outbox.cancel` joins
  a writable tenant transaction and clears its lease; conditional result writes cannot turn it
  back into sending/sent. A provider `OutboxCancelled` outcome does not count as a provider failure.
  Ordinary delivery failures retain the existing retry/breaker behavior. Cancellation does not
  erase payloads by itself; a privacy-erasure operation must use a shared outbox operation to remove
  identifying payloads as well.
- **Every provider call** has a time limit (10 s), a limit on calls at once per channel (4), and a
  circuit breaker per channel that opens after 5 consecutive failures and waits 60 s. While it is
  open, the channel's messages wait without using up attempts.
- **Recurring jobs** (`shared.jobs`): a job runs at most once per occurrence across every instance,
  claimed through `job_claim` and released through `job_finish`. A job over salon data asks
  `active_tenant_ids` and runs each salon in its own transaction with that salon set; one salon's
  failure is logged and the others go on. Suspended salons are skipped. The first job removes
  delivered messages older than 30 days, hourly.
- On stop, the dispatcher and the scheduler stop claiming work and give calls in flight 5 s
  (`salon.outbox.shutdown-grace`); what is not finished is recovered by the lease.
- Every setting is under `salon.outbox` and `salon.jobs` in `application.yaml`, with its default.
- **The audit trail** (`shared.audit`): one way to write an entry, which joins the caller's
  transaction and refuses to run outside one. An entry records the salon, when (the application's
  clock), the actor, the action, the subject, an optional reason, the values before and after, and
  the trace id. Values under a key that names a secret are never stored. The application's role may
  only insert and read; a trigger refuses an update by any role.

## Logs, traces and meters

- The API logs one ECS JSON object per line (`logging.structured.format.console: ecs`) with
  `trace.id`, `span.id`, and `tenant.id` whenever a salon is bound: a request on its hostname, a
  delivery or a job run for it. A request on a salon's hostname looks its salon up when it starts,
  except `/api/v1/meta`, `/actuator/**` and the OpenAPI document, which must not wait on the
  database and so carry no `tenant.id`. Invalid credentials may be rejected before this lookup
  and tenant binding; their required request line retains the caller/problem trace and has no
  `tenant.id`. Logging never performs a registry lookup or trusts an invalid credential to add
  a tenant tag.
- Each request writes one line with its method, its path without the query string, its status and
  its duration (`event.duration`, nanoseconds). No line carries a request body, a query string, a
  `Cookie` or an `Authorization` header.
- Spans for each request, each delivery and each job run for a salon carry `tenant.id`. A W3C
  `traceparent` from the caller is continued, and a message's delivery belongs to the trace of the
  request that wrote it. Spans are exported over OTLP only when an endpoint is configured.
- Spring Boot 4's test support turns W3C propagation off along with export; the integration test
  base carries `@AutoConfigureTracing` so that tests trace as the product does.
- Meters: `salon.outbox.waiting`, `salon.outbox.oldest.due.age`, `salon.outbox.dead`,
  `salon.outbox.deliveries` (tags `channel`, `outcome`) and `salon.jobs.start.delay` (tag `job`).
  `/actuator/metrics` is served in the `local` profile only.

## Local test hooks

Served in the `local` profile only, on loopback, under `/local/`, and never in the OpenAPI document;
404 `not_found` in any other profile. The test clock (`/local/clock`) and the probe
(`/local/probes`), whose fake provider is the only provider until a feature adds a real one. How a
test uses them is in `docs/qa/test-plan.md`.
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
| Architecture | Module boundaries (Spring Modulith `verify()`), provided-interface rules, table and path ownership, no entity setter for id, tenant or version, mappers on the shared configuration | Fails the build. Each rule has a fixture proving it fails on a violation: package fixtures under `src/test/java/<base>/architecture/fixture/`, and sources that must fail to compile (denied Lombok, unmapped MapStruct targets) or that Spring would otherwise scan (fixture entities and repositories) under `src/test/compile-fixtures/`, compiled by a test through `javax.tools` |
| Conventions | Formatting and warnings | `./mvnw verify` runs `spotless:check` and compiles with `-Xlint:all,-processing -Werror` |
| Contract | The OpenAPI document and generated client are current, and every response matches it | Fails the build. `pnpm contracts:check` regenerates the document from the controllers, so it needs the JDK and Docker. From F04 on, each new or changed endpoint also gets an integration test that validates its real responses against the committed schema, including which fields may be null and every documented status code: three of F01 to F03's major bugs were nullability or status mismatches QA found late (decision 068). The first stage that needs it adds the validator |

Integration tests are named `*IntegrationTest` and run in the same Maven phase as unit tests. One
PostgreSQL container serves the whole run, with `max_connections` raised to 200 because every cached
test context holds its own pools.

A test asserts an outcome the spec promises. A test that passes when the feature is removed is not
a test: when you write one for a bug, watch it fail first.

## Verification commands

The commands for a stage are the ones in its `stage.yaml` under `verification.commands`. For the API
that means the Maven wrapper's `verify` (unit, integration and architecture tests) and the contract
drift check.

## Migration-only startup

The `migrate` profile uses web application type NONE, applies database migrations and exits.
Servlet request filters are created only for servlet applications; do not make a migration
context depend on MVC exception resolvers. Servlet tenant authentication still requires its
resolver and fails closed. The actual non-web migration startup integration test protects this
boundary, alongside the servlet tenant-isolation and authentication tests (BUG-F01-002).
