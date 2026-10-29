# F23-S07 Module internal layout with conventional Spring folders

## Objective

Every business module's code sits in the folders the owner chose in CR-022 (`controller/ dto/
mapper/ service/ entity/ domain/ repository/ adapter/ job/ config/ event/ local/`, each only when
needed), with the module's API still in its root package, and every architecture rule re-aimed at
those names. Package moves only: nothing a salon, a staff member or the operator does changes, and
the API contract stays byte-identical.

## Read first

The project documents that hold the rules for this stage. Developer and QA apply the shared
context-reading rule in `salon-sdlc` before their work.

- `docs/changes/CR-022-module-internal-layout.md`: the decision and its reasons.
- `docs/engineering/backend.md`: "Modules", "Inside a module" (the target layout and what goes in
  each folder), "Provided interfaces", "Persistence and migrations", "Code generation", "Clean
  code", "Tests".
- `docs/decisions/DECISION_LOG.md`: 056 to 060.
- `docs/operations/verification-evidence.md`.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| NFR-23 | Backend persistence follows one convention ... the code meets the written clean-code standard ... | part: the layout |
| NFR-22 | Each backend business module offers its capabilities to other modules only through provided interfaces ... | part: boundaries kept under the new names |

## Scope

API only (`apps/api`, main and test sources):

- **Business modules** (`identity`, `platform`, `branding`; the empty modules keep only
  `package-info.java`):
  - `web/` splits into `controller/` (controllers), `dto/` (request and response records) and
    `mapper/` (MapStruct mappers);
  - `application/` becomes `service/`;
  - `domain/` splits into `entity/` (JPA entities) and `domain/` (pure rules and value objects with
    no Spring, JPA or HTTP; omitted when a module has none);
  - `infrastructure/` splits by what each class is: `repository/` (Spring Data repositories and every
    class that runs SQL or calls the database), `adapter/` (external providers, crypto and secret
    holders, address assertions), `job/` (schedules and cleanup jobs), `config/` (Spring
    configuration, filters, settings classes, startup commands);
  - `inprocess/` and `local/` keep their names.
  Where a class could go in two folders, the rule in `backend.md` decides, and the choice is listed
  in `handoff.notes`.
- **`shared`** stays organised by capability (`outbox`, `tenancy`, `web`, `secrets`, `audit`,
  `jobs`, `time`, `persistence`, `mapping`, `entitlements`, `lifecycle`, `observability`, `local`):
  those are its named interfaces and keep their names. Its `application` and `infrastructure`
  packages are redistributed into capability packages (for example request and database plumbing
  into `persistence`, `config` or `web`), so that no package named `application` or
  `infrastructure` remains anywhere.
- **Architecture rules** re-aimed, each with its fixture still proving it fails: database access
  (`EntityManager`, `JdbcClient`, Spring Data repository interfaces) only in `repository` packages
  or `shared`; `domain` uses no Spring, JPA or HTTP; root packages hold only interfaces, records,
  enums and exceptions; mappers only in `mapper`; `@RestController` only in `controller` (or
  `local`); request and response records only in `dto`. A new rule, with a fixture, refuses any
  sub-package of a business module whose name is not in the layout list.
- **No `Impl` suffix** on any class, except the Spring Data fragment `NewEntitiesImpl`.
- **Package-private where possible:** a class that nothing outside its folder uses loses `public`,
  where Spring and JPA allow it.
- **Tests** move with the code they test, mirroring the new packages. No assertion changes.
- **Clean code** in everything touched; **documentation hand-back** in `handoff.notes`.

## Out of scope

- Any change to behaviour, SQL, transactions, the API contract or settings.
- Events (F23-S05) and the web application (F23-S06).

## Acceptance criteria

Each criterion is one observable outcome that a test can pass or fail.

- AC-1: No package named `web`, `application` or `infrastructure` remains inside a business module,
  and none named `application` or `infrastructure` in `shared`; each business module's sub-packages
  are drawn only from `controller dto mapper service entity domain repository adapter job config
  event inprocess local`, and a fixture with any other sub-package name fails the architecture
  tests. (NFR-23)
- AC-2: Each re-aimed rule fails on its fixture and passes for the application: database access
  outside `repository` or `shared`; Spring or JPA in `domain`; a class with behaviour in a root
  package; a mapper outside `mapper`; a controller outside `controller` or `local`. (NFR-22, NFR-23)
- AC-3: Spring Modulith verification passes with every module's `allowedDependencies` and named
  interfaces unchanged in meaning. (NFR-22)
- AC-4: No class name ends in `Impl` except `NewEntitiesImpl`. (NFR-23)
- AC-5: The committed OpenAPI document and the generated client are byte-identical to the feature
  branch before this stage; `pnpm contracts:check` passes. (NFR-22)
- AC-6: Behaviour is unchanged: every test before this stage still exists (moved with its code where
  its package changed) and passes with unchanged assertions; the application starts in the local
  profile and the operator console and a salon workspace sign-in work as before. (NFR-23)

## Design notes

- Do the moves with the IDE or a scripted refactor, one module per commit, then the rule changes in
  their own commit, so a reviewer can see that the moves change nothing but packages and imports.
- Spring component scanning, JPA entity scanning and Spring Data repository scanning all start at
  the base package, so moving classes needs no configuration change; check that nothing names a
  package explicitly (`@EntityScan`, `@EnableJpaRepositories`, `scanBasePackages`, ArchUnit or
  Modulith rules, `module-ownership.yaml`, the compile fixtures).
- Making a class package-private can break a test in another package; the test moves to the same
  package rather than the class staying public, where practical.

## API and data changes

Not applicable for this stage. AC-5 proves the contract is untouched.

## UI and UX

Not applicable for this stage.

## Risks and test focus

- **A rule silently weakened by the rename** (`build`): AC-1 and AC-2 keep each fixture failing.
- **A bean or entity no longer scanned** (`data-integrity`): the full integration suite (AC-6).
- **Tenant isolation** (`tenant-isolation`): the two-guard isolation suites must pass unchanged.

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
- Module moves are in commits separate from rule changes.
- Every class placement the layout did not decide obviously is listed in `handoff.notes`.
