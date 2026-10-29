# F23-S01 Build conventions and module verification

## Objective

The API's build carries the tooling every later F23 stage and every later feature is built with:
Lombok and MapStruct as annotation processors under the conventions of CR-021, Spring Modulith
verifying the module boundaries in place of the hand-written rules, one Java formatter, and compiler
warnings as errors. Nothing a salon, a staff member or the operator does changes.

## Read first

The project documents that hold the rules for this stage. Developer and QA apply the shared
context-reading rule in `salon-sdlc` before their work.

- `docs/changes/CR-021-modular-extractable-architecture.md`: why, and decisions 1, 6 and 9.
- `docs/engineering/backend.md`: "Modules", "Inside a module", "Code generation", "Clean code",
  "Tests" (the architecture row) and "Verification commands".
- `docs/architecture/tech-stack.md`: "Module boundaries", "Code generation (API)", "Formatting and
  dead-code checks"; the note that Spring Boot 4 and Hibernate 7 differ from older versions.
- `docs/operations/verification-evidence.md`: how gate evidence is recorded at handoff.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| NFR-22 | Each backend business module offers its capabilities to other modules only through provided interfaces ... and can be moved into a separate service without a change to the web or mobile clients | part: module verification by Spring Modulith; interfaces in F23-S02, ownership in F23-S04, events in F23-S05 |
| NFR-23 | Backend persistence follows one convention ... repetitive code is generated within enforced limits ... the code meets the written clean-code standard | part: the build-time limits and the formatter; their use on entities in F23-S03 |
| NFR-04 | The design supports growth without re-architecture | part |

## Scope

API only (`apps/api`):

- **Lombok** (version managed by Spring Boot) and **MapStruct 1.6.3** with
  `lombok-mapstruct-binding` 0.2.0, listed in `maven-compiler-plugin` `annotationProcessorPaths` in
  the order Lombok, binding, MapStruct. Lombok excluded from the packaged jar. Compiler arguments
  set MapStruct's defaults: Spring component model, constructor injection, unmapped targets as
  errors.
- **`apps/api/lombok.config`**: `config.stopBubbling = true`, `lombok.addLombokGeneratedAnnotation
  = true`, and `flagUsage = error` for every annotation that `docs/engineering/backend.md`, "Code
  generation", denies and that Lombok can flag.
- **A shared `@MapperConfig`** in `shared` (unmapped target and type-conversion policies as
  errors, Spring component model, constructor injection) for later mappers to use.
- **Spring Modulith 2.1.x** (`spring-modulith-starter-core`, `spring-modulith-starter-test`; the
  version that targets Spring Boot 4.1, managed by its BOM). `ApplicationModules.of(...).verify()`
  replaces the rules it covers in `ModuleRules` (cycles; use of another module only through its
  root package). Each sub-package of `shared` used by other modules is declared a named interface.
  The ArchUnit rules Modulith does not cover stay: every class in a listed module, `shared` uses no
  other module, nothing depends on `reporting`, `domain` uses no Spring and no HTTP, and the local
  test hook rules.
- **Fixtures.** Every boundary rule keeps a fixture that proves it fails on a violation, moved to
  the Modulith-based check where Modulith now enforces it. A new fixture proves each of: a denied
  Lombok annotation fails compilation; a MapStruct mapper with an unmapped target property fails
  compilation. These fixtures are compiled by a test (for example through `javax.tools`), not by the
  main build.
- **Spotless** (Maven plugin) with `palantir-java-format`, formatting every Java file under
  `src/main` and `src/test` and removing unused imports; `spotless:check` bound so that
  `./mvnw verify` fails on an unformatted file. The one-time reformatting of the whole API is its
  own commit with no other change in it.
- **Compiler warnings as errors** (`-Xlint:all -Werror`, excluding only `-Xlint:-processing` if the
  annotation processors make it necessary). Each warning found is fixed at its source; a
  `@SuppressWarnings` is allowed only with a comment beside it saying why.
- **Clean code** in what this stage touches: remove any unused import, field, method, class or
  dependency the compiler or the IDE reports; nothing commented out.
- **Documentation hand-back:** if a version, plugin or setting differs from what
  `docs/architecture/tech-stack.md` or `docs/engineering/backend.md` says, say so in
  `handoff.notes` for the lead to correct.

## Out of scope

- Turning facades into interfaces, renaming `api/` to `web/`, `allowedDependencies` per module, and
  splitting oversized platform classes: F23-S02.
- Using Lombok or MapStruct on existing entities and responses; the entity-setter architecture rule;
  splitting `StaffAccess`: F23-S03.
- Table and path ownership tests, OpenAPI tags by module: F23-S04.
- Spring Modulith's event publication registry, externalization, observability or documentation
  starters: not used (CR-021).
- The web application (formatter and dead-code checks there are F23-S06).

## Acceptance criteria

Each criterion is one observable outcome that a test can pass or fail.

- AC-1: Given the API source on this stage's branch, when `cd apps/api && ./mvnw -q verify` runs,
  it succeeds, and its output shows Spotless's check, the compiler with warnings as errors, and the
  architecture tests (Spring Modulith verification and the remaining ArchUnit rules) were executed.
  (NFR-22, NFR-23)
- AC-2: The module verification reports a violation for each existing fixture (a cycle between
  modules; use of another module's internal package; `shared` using another module; a dependency on
  `reporting`; Spring in `domain`; a class outside a listed module; a local test hook used outside
  its package or loaded outside the local profile), and reports none for the clean fixture.
  (NFR-22)
- AC-3: A fixture class annotated with each denied Lombok annotation that `lombok.config` flags
  (at least `@Data`, `@Value`, `@EqualsAndHashCode`, `@ToString`, `@Builder`, `@SneakyThrows`)
  fails compilation with Lombok's flag-usage error, and a fixture using only allowed annotations
  compiles. (NFR-23)
- AC-4: A fixture MapStruct mapper whose target record has a property with no source fails
  compilation with an unmapped-target error, and the same mapper with the property mapped compiles.
  (NFR-23)
- AC-5: The packaged jar (`apps/api/target/salon-api.jar`) contains no Lombok classes, and the
  application starts in the local profile and answers `GET /api/v1/meta` and the readiness check as
  before. (NFR-23)
- AC-6: The committed OpenAPI document (`docs/api/openapi.json`) and the generated client are
  unchanged by this stage: `pnpm contracts:check` passes and `git diff origin/main -- docs/api
  packages/api-contracts/src/generated` is empty. (NFR-22)
- AC-7: Behaviour is unchanged: every test that existed on `origin/main` still exists and passes,
  and no assertion was changed. A test may move with the rule it tests, and the formatter may
  change its layout. (NFR-04)
- AC-8: The API contains no commented-out code, no `TODO` without a stage, change request or bug
  id, and no `@SuppressWarnings` without a comment saying why. (NFR-23)

## Design notes

- Annotation processor order matters: Lombok must run before MapStruct, which is what the binding
  artifact guarantees. JDK 23 and later do not discover processors on the class path, so all three
  are listed explicitly.
- Spring Modulith treats each direct sub-package of `com.salonplatform` as a module and its root
  package as the API, which is the repository's existing rule (decision 016), so no module needs to
  move for `verify()` to pass. `shared` sub-packages that other modules use need `@NamedInterface`
  in their `package-info.java`. Do not use `Type.OPEN` to make the check pass.
- Keep `ModuleBoundariesTest` as the single architecture test entry point, or replace it with one
  clearly named equivalent; do not leave two tests asserting the same rule.
- The Spotless reformat commit comes first, so later diffs in F23 show only real changes.
- Nothing in `src/main` uses Lombok or MapStruct yet, apart from the shared `@MapperConfig`.

## API and data changes

Not applicable for this stage. No endpoint, response, table or migration changes; AC-6 proves the
contract is untouched.

## UI and UX

Not applicable for this stage.

## Risks and test focus

- **Annotation processing on JDK 25** (`build`): a processor silently not running would let a
  denied annotation or an unmapped field through. AC-3 and AC-4 prove the processors run and fail
  closed.
- **Weaker boundary check** (`build`): replacing hand-written rules with Modulith could drop a rule
  without anyone noticing. AC-2 keeps a failing fixture per rule.
- **Behaviour drift from formatting or warning fixes** (`data-integrity`, `tenant-isolation`): a
  "harmless" change while fixing warnings in tenant or security code. The existing isolation and
  identity integration tests must pass unchanged (AC-7); review warning fixes in `shared.tenancy`,
  `identity` and `platform` with particular care.
- **Contract drift** (`api`): AC-6.

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
- The reformatting commit contains formatting only.
- Any difference from `docs/architecture/tech-stack.md` or `docs/engineering/backend.md` (versions,
  plugin settings, rules Modulith could not express) is listed in `handoff.notes`.
