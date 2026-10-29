# F23-S03 Persistence convention for tenant aggregates

## Objective

Every entity is encapsulated in the CR-021 style and reached through a Spring Data JPA repository
in its module's `infrastructure/`; direct SQL survives only as the named exceptions, in
`infrastructure/` or `shared`, through `JdbcClient`, each stating its tenant predicate; and tests
prove Hibernate's tenant filter and row-level security each stop another salon's rows on their own
for every repository. Nothing a salon, a staff member or the operator does changes.

## Read first

The project documents that hold the rules for this stage. Developer and QA apply the shared
context-reading rule in `salon-sdlc` before their work.

- `docs/changes/CR-021-modular-extractable-architecture.md`: decisions 5 and 6.
- `docs/engineering/backend.md`: "Inside a module", "Persistence and migrations" (the default and
  the five named exceptions), "Code generation", "Clean code", "Tests".
- `docs/architecture/multi-tenancy.md` in full: the ten rules, "How the tenant reaches the
  database", "Adding an endpoint", "Tests you must write".
- `docs/architecture/identity-and-access.md` and `docs/architecture/operator-console.md`: the
  security rules the identity and platform code being moved implements.
- `docs/operations/verification-evidence.md`.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| NFR-23 | Backend persistence follows one convention, Spring Data JPA repositories with direct SQL only for named exceptions, and repetitive code is generated within enforced limits ... the code meets the written clean-code standard ... | full for the existing code |
| NFR-01 | No request can read or change another tenant's data ... | part: each repository and each direct-SQL exception proved with each guard alone |

## Scope

API only (`apps/api`). Today identity's application classes use the `EntityManager` directly on
entities with public fields, only branding has a Spring Data repository, and `JdbcTemplate`, raw
SQL and native queries appear in `application/` classes.

- **Entities** (all twelve: identity's nine, `SalonBrand`, `OutboxMessage`, `AuditEntry`): private
  fields; Lombok `@Getter` and `@NoArgsConstructor(access = PROTECTED)` within the allow-list;
  created through a static factory or constructor that establishes its rules; changed only through
  methods named for what they do. No public field, and no setter for an id, tenant id, version or
  creation time. `@Version` stays where it is today (no new column in this stage); where it exists
  it is a wrapper `Long`.
- **Repositories.** One Spring Data JPA repository per aggregate root, in the owning module's
  `infrastructure/` (`shared`'s own in `shared`). Application classes use repositories, not the
  `EntityManager`. A query a derived method cannot express is a `@Query` in JPQL on the repository.
  Bulk `@Modifying` queries on a tenant entity are not used; set-based work is a named direct-SQL
  exception.
- **Direct SQL.** Every remaining use is one of the five named exceptions in `backend.md`, lives in
  `infrastructure/` (or `shared`), and uses `JdbcClient`; no `JdbcTemplate` remains. Native queries
  issued through the `EntityManager` from `application/` (identity's `IdentityWork`, platform's
  entitlements service, `shared.outbox.Outbox`) and the platform-database SQL in
  `platform/application/` (`OperatorAccess`, `OperatorSessions`, `OperatorSignIns`) and in the
  `inprocess` implementations move behind an `infrastructure/` class with an intention-revealing
  method. Each statement that reads or writes a tenant table states `tenant_id = ?` (or calls a
  function that is scoped by `app.tenant_id`, with that written beside it).
- **Response mapping.** A controller that returns an entity's data maps it through a MapStruct
  mapper on `ResponseMapperConfig`, or by hand where that is shorter (`backend.md`, "Code
  generation"). No mapper writes to an entity.
- **`StaffAccess`** (754 lines) is split along its capabilities (staff sign-in, MFA, staff account
  management, sessions), each class under about 400 lines, with the same transaction boundaries,
  rules and errors.
- **Architecture rules**, each with a fixture proving it fails:
  - an `@Entity` has no public field and no method named `setId`, `setTenantId`, `setVersion` or
    `setCreatedAt`;
  - `EntityManager` and `JdbcClient` are used only from `infrastructure` packages or `shared`, and
    Spring Data repository interfaces are declared only there;
  - `JdbcTemplate` is used nowhere in `src/main`.
- **Isolation tests.** For every repository on a tenant entity, an integration test with two
  salons proves, with **Hibernate's filter alone** (row-level security off) and with **row-level
  security alone** (filter bypassed through direct SQL as the application role), that loading by
  id, listing, updating and deleting another salon's row affects nothing and finds nothing. For
  every direct-SQL exception that touches a tenant table, a test with row-level security as the
  only guard. Extend the existing `*HibernateFilterAlone*` and `*RowLevelSecurityAlone*` pattern.
- **Test stability.** The integration tests' HTTP server listened on a loopback port that another
  program on the developer's machine also held during F23-S02. Bind the test server explicitly to
  `127.0.0.1` (or document in `handoff.notes` why that does not help).
- **Clean code** in everything touched.
- **Documentation hand-back** in `handoff.notes`.

## Out of scope

- New tables, columns, `@Version` where there is none, or any migration other than one a moved
  query proves strictly necessary (say why in `handoff.notes`): none is expected.
- Table and path ownership tests: F23-S04.
- The outbox `event` kind: F23-S05.

## Acceptance criteria

Each criterion is one observable outcome that a test can pass or fail.

- AC-1: The architecture tests report violations for a fixture entity with a public field and one
  with `setTenantId`, and none for the application: no `@Entity` in `src/main` has a public field or
  a setter for its id, tenant id, version or creation time. (NFR-23)
- AC-2: The architecture tests report violations for fixtures using the `EntityManager` and
  `JdbcClient` from an `application` package and using `JdbcTemplate` anywhere, and none for the
  application. (NFR-23)
- AC-3: Each repository on a tenant entity has a two-salon integration test proving, with the
  Hibernate filter as the only guard, that another salon's row is not found by id, not listed, and
  not updated or deleted by any repository method; and the same with row-level security as the only
  guard. (NFR-01)
- AC-4: Each direct-SQL exception that reads or writes a tenant table has a test with row-level
  security as the only guard showing it reaches only the current salon's rows, and its SQL names
  `tenant_id` or documents the tenant-scoped function it calls. (NFR-01, NFR-23)
- AC-5: `StaffAccess` is split so that no resulting class exceeds about 400 lines without a written
  reason, and staff sign-in, MFA enrolment and challenge, staff account creation within the seat
  limit, and session refresh and sign-out answer exactly as before (the existing identity
  integration tests unchanged). (NFR-23)
- AC-6: The committed OpenAPI document and the generated client are byte-identical to
  `origin/main`; `pnpm contracts:check` passes. (NFR-23)
- AC-7: Behaviour is unchanged: every test on the feature branch before this stage still exists and
  passes with unchanged assertions; the application starts in the local profile and the operator
  and Owner journeys (console sign-in with MFA, salon creation, Owner sign-in with MFA, staff
  creation at the seat limit, support session, closure and export) behave as before. (NFR-01)
- AC-8: The API contains no `JdbcTemplate`, no commented-out code, no `TODO` without an id, and no
  unused class, method, field or import left by the moves. (NFR-23)

## Design notes

- Spring Data JPA 4 with an assigned UUID: `save()` on an entity with a null wrapper `@Version`
  persists; without `@Version`, implement `Persistable` or use the entity's own creation path so a
  new entity is not merged with an extra select. Keep the existing persist/merge behaviour; the
  query-count rule in `backend.md` applies.
- Hibernate's filter does not apply to native SQL, and `find()` by primary key had filter gaps in
  earlier Hibernate versions: the AC-3 tests are the guard against both, so write them before
  moving each repository and watch them pass on both guards.
- `PlatformDatabase` stays the platform pool and stays out of the `DataSource` and transaction
  manager beans. Do not introduce a second entity manager factory.
- The `inprocess` implementations from F23-S02 stay in `application/`; their SQL moves to an
  `infrastructure/` class they call.
- Prefer moving code verbatim and then refactoring in a later commit, so a reviewer can tell a move
  from a change.

## API and data changes

Not applicable for this stage. No endpoint, response, table or migration changes; AC-6 proves the
contract is untouched.

## UI and UX

Not applicable for this stage.

## Risks and test focus

- **A tenant filter gap opened by moving a query** (`tenant-isolation`): the highest risk of
  F23. AC-3 and AC-4 test each guard alone; the existing isolation suites must pass unchanged.
- **Changed persist/merge or flush behaviour** (`data-integrity`, `concurrency`): repositories
  flush and merge differently from hand-written `EntityManager` code. The identity concurrency and
  rate-limit tests, and the provisioning and billing tests, must pass unchanged.
- **Secrets in logs** (`security`): no generated `toString` (Lombok denies it); entities holding
  password hashes, MFA secrets or tokens never print them.
- **Contract drift from response mapping** (`api`): AC-6.

## Verification commands

The developer gates, run from the repository root. Copy them into `stage.yaml` `verification.commands`.

```bash
cd apps/api && ./mvnw -q verify
pnpm contracts:check
```

## Done criteria

- The developer has checked every acceptance criterion on the running product and recorded it in
  the handoff.
- The verification commands are green.
- Moves are in commits separate from refactoring where practical.
- Any difference from `docs/engineering/backend.md` or `docs/architecture/multi-tenancy.md` is
  listed in `handoff.notes`.
