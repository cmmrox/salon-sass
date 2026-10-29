# F23-S02 Provided module interfaces

## Objective

Every module offers what other modules use from it through interfaces and records in its root
package (or an `inprocess` named interface when a contract must join the caller's transaction),
declares the modules it may use, and keeps controllers in `web/`. The platform module's oversized
use-case classes are split along their capabilities. Nothing a salon, a staff member or the operator
does changes, and the API contract stays byte-identical.

## Read first

The project documents that hold the rules for this stage. Developer and QA apply the shared
context-reading rule in `salon-sdlc` before their work.

- `docs/changes/CR-021-modular-extractable-architecture.md`: decisions 1, 2 and 9.
- `docs/engineering/backend.md`: "Modules", "Inside a module", "Provided interfaces", "Code
  generation" (MapStruct), "Clean code", "Tests".
- `docs/architecture/operator-console.md`: "Entitlements" and the atomic salon and Owner bootstrap,
  which are the transaction-bound contracts this stage marks in-process only.
- `docs/architecture/multi-tenancy.md`: the ten rules; nothing here may weaken one.
- `docs/operations/verification-evidence.md`.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| NFR-22 | Each backend business module offers its capabilities to other modules only through provided interfaces of records and through versioned, durable events ... | part: provided interfaces and allowed dependencies; ownership in F23-S04, events in F23-S05 |
| NFR-01 | No request can read or change another tenant's data ... | part: kept intact through the restructuring |
| NFR-04 | The design supports growth without re-architecture | part |

## Scope

API only (`apps/api`):

- **Provided interfaces.** The concrete classes in module root packages become interfaces, with
  their implementations moved into the module's `application/` and named for what they are (no
  `Impl` suffix):
  - `identity.IdentityFacade` becomes the interface `identity.Principals` (`current()`,
    `require(String permission)`). Its nested `Principal` becomes the top-level record
    `identity.Principal` with no OpenAPI annotation.
  - `identity.IdentityLifecycle`, `identity.IdentityProvisioning` and `branding.BrandProvisioning`
    join their caller's transaction (`requireTransaction()`), so they become interfaces in
    `identity.inprocess` and `branding.inprocess`, each declared a named interface `inprocess`, each
    with Javadoc saying why it must join the caller's transaction and that it becomes an event, not
    a remote call, if its module is extracted. Their records (such as `PreparedOwner`) move with
    them and keep any secret out of `toString`.
  - `identity.CustomerDataErased` stays a root-package event record.
- **Response shape split.** The principal returned by the API gets its own response record in
  `identity/web/` carrying the OpenAPI annotations that are on `Principal` today, produced by a
  MapStruct mapper on `ResponseMapperConfig`. The generated document must not change.
- **Entitlements.** `shared.entitlements.SalonEntitlements` stays in `shared`, as the one business
  contract there: every module enforces plan limits inside its own transaction, `platform`
  implements it, and moving it to `platform` would create a cycle with `identity`. Its
  implementation moves from `platform.EntitlementsFacade` into `platform/application/` (or
  `infrastructure/`), leaving no class in platform's root package.
- **`api/` becomes `web/`** in every module that has one, including `shared.api` (renamed
  `shared.web`, kept a named interface). Packages and imports only; class names stay.
- **Allowed dependencies.** Every module's `package-info.java` declares
  `@ApplicationModule(allowedDependencies = ...)` with exactly what it uses: named interfaces of
  `shared`, and for `platform`, `identity`, `identity :: inprocess` and `branding :: inprocess`. The
  empty modules declare `shared` only.
- **Architecture rules**, each with a fixture proving it fails:
  - a root package holds only interfaces, records, enums, exceptions and `package-info.java`: no
    class with behaviour and no Spring stereotype;
  - no OpenAPI (`io.swagger`) annotation on a type in a root package or an `inprocess` package;
  - every MapStruct `@Mapper` names `ResponseMapperConfig` (decision 056);
  - Spring Modulith refuses an undeclared dependency (allowed-dependency fixture).
- **Platform use-case classes split** along their capabilities, each resulting class under about
  400 lines (`docs/engineering/backend.md`, "Clean code"): `SupportSessions` (767 lines),
  `BillingLedger` (682), `SalonExports` (618), `OperatorAccess` (606), `SalonProvisioning` (568).
  A split moves code; it changes no rule, transaction boundary, query or error. If a class is
  better left above 400 lines, write the reason beside it.
- **Clean code** in everything touched.
- **Documentation hand-back:** anything that differs from the documents goes in `handoff.notes`.

## Out of scope

- Persistence style on entities, `StaffAccess` (775 lines) and the entity-setter rule: F23-S03.
- Table and path ownership, OpenAPI tags by module: F23-S04.
- Moving `CustomerDataErased` or any call onto the outbox: F23-S05.
- Adding `@HttpExchange` clients or any remote adapter: not built until a module is extracted
  (CR-021, decision 10).

## Acceptance criteria

Each criterion is one observable outcome that a test can pass or fail.

- AC-1: The architecture tests report a violation for a fixture root package holding a concrete
  `@Service` class, and none for the application: every root package of every module contains only
  interfaces, records, enums, exceptions and `package-info.java`. (NFR-22)
- AC-2: The architecture tests report a violation for a fixture record carrying an OpenAPI
  annotation in a root package, and for a fixture mapper that does not name `ResponseMapperConfig`;
  none for the application. (NFR-22)
- AC-3: Spring Modulith reports a violation for a fixture module that uses a module missing from its
  `allowedDependencies`, and verifies the application: every module declares its allowed
  dependencies, and only `platform` (and its `local` hooks) uses an `inprocess` interface. (NFR-22)
- AC-4: Each `inprocess` interface has Javadoc stating why it joins the caller's transaction and
  that it becomes an event if its module is extracted; no other provided interface requires a
  caller's transaction. (NFR-22)
- AC-5: No package named `api` remains under `com.salonplatform`; every controller is in a `web`
  package. (NFR-22)
- AC-6: The committed OpenAPI document and the generated client are byte-identical to
  `origin/main` (`pnpm contracts:check` passes and `git diff origin/main -- docs/api
  packages/api-contracts/src/generated` is empty), including the principal response's schema.
  (NFR-22)
- AC-7: Behaviour is unchanged: every test on the feature branch before this stage still exists and
  passes with unchanged assertions, including every tenant-isolation, identity, billing, lifecycle,
  support and provisioning integration test; the application starts in the local profile and the
  operator console and a salon workspace sign-in work as before. (NFR-01, NFR-04)
- AC-8: Each of the five named platform classes is split so that no resulting class exceeds about
  400 lines without a written reason beside it, and no transaction boundary, query or error code
  changed (the integration tests in AC-7 are the proof). (NFR-04)
- AC-9: The API contains no commented-out code, no `TODO` without an id, and no unused class,
  method, field or import left by the moves. (NFR-04)

## Design notes

- Named interfaces: put `@NamedInterface("inprocess")` on `identity.inprocess` and
  `branding.inprocess`, and reference them as `identity :: inprocess` in `allowedDependencies`.
  Spring Modulith 2.1 syntax is in its reference documentation, "Fundamentals".
- The principal response mapper is the first production MapStruct mapper; keep it trivial. If a
  hand-written mapping is genuinely shorter, the rule in "Code generation" allows it, but the
  response record must still be separate from `Principal`.
- A split class keeps its package. Prefer one class per user-facing capability (for example
  support entry, support grant, support revocation) over technical slices.
- `@Transactional` boundaries stay on the same public methods; moving a method between classes
  must not turn a self-invocation into a proxied call or the reverse without the integration tests
  proving the same behaviour.

## API and data changes

Not applicable for this stage. No endpoint, response, table or migration changes; AC-6 proves the
contract is untouched.

## UI and UX

Not applicable for this stage.

## Risks and test focus

- **Transaction semantics change in a split** (`data-integrity`, `concurrency`): moving a method
  across classes can change whether Spring's proxy applies `@Transactional`. The provisioning,
  billing and lifecycle integration tests, including their concurrency cases, must pass unchanged.
- **Tenant context lost across a moved call** (`tenant-isolation`): the isolation suites must pass
  unchanged.
- **Contract drift from the response split** (`api`): AC-6.
- **Boundary checks that silently stop checking** (`build`): each new rule's fixture (AC-1 to AC-3).

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
- Moves and renames are in commits separate from behaviour-preserving edits where practical, so the
  review can tell a move from a change.
- Differences from `docs/engineering/backend.md` are listed in `handoff.notes`.
