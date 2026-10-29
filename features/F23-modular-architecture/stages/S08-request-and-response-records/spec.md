# F23-S08 Request and response records in dto

## Objective

Every record that a controller reads as a request body or returns as a response, at any depth of
the response, is a top-level type in its module's `dto/`, and an architecture rule holds that. The
API's shapes then live in one predictable place per module. The OpenAPI document and generated
client do not change, and nothing a salon, a staff member or the operator does changes.

## Read first

The project documents that hold the rules for this stage. Developer and QA apply the shared
context-reading rule in `salon-sdlc` before their work.

- `docs/changes/CR-022-module-internal-layout.md`.
- `docs/engineering/backend.md`: "Inside a module" (including the request and response record
  rule), "Provided interfaces" (the module API's records are not response shapes), "API
  conventions", "Code generation" (records and MapStruct), "Clean code", "Tests".
- `docs/decisions/DECISION_LOG.md`: 056 to 061.
- `docs/operations/verification-evidence.md`.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| NFR-23 | Backend persistence follows one convention ... the code meets the written clean-code standard ... | part: the API's shapes in `dto/` |

## Scope

API only (`apps/api`, main and test sources), in `identity`, `platform`, `branding` and `shared`
(including their `local/` hooks):

- **Extract** every record that appears as a `@RequestBody` parameter or in a controller method's
  return type (directly, inside `ResponseEntity`, or as a component of another such record) from the
  controller or service it is nested in into the module's `dto/`, as a public top-level record.
  F23-S07 counted about 30 nested request records and about 90 nested response records.
- **Names:** a top-level record keeps its OpenAPI schema name. Where two nested records share a
  simple name today, choose the names so the generated schema names stay exactly as they are (use
  `@Schema(name = ...)` only where the class name must differ); the document and client must not
  change.
- **Services return dto records** where they returned their own nested response records; a
  service never returns an entity to a controller.
- **Records used only inside one class** (a repository's row, a crypto holder's value, a test
  hook's private shape) stay nested and become non-public where they are public today.
- **The module API's records** (root package) are not moved: they are not response shapes
  (`backend.md`, "Provided interfaces").
- **Architecture rule**, with fixtures: a record used as a controller's request body or reachable
  from its response type is a top-level type in a `dto` package of the same module (or `shared.web`
  for the shared problem shapes); a fixture nested request record and a fixture nested response
  record each fail.
- **Mapping:** where a hand-written `from(...)` factory converts an entity to a response, keep it or
  replace it with a MapStruct mapper on `ResponseMapperConfig`, whichever is shorter
  (`backend.md`, "Code generation").
- **Clean code** in everything touched; **documentation hand-back** in `handoff.notes`.

## Out of scope

- Any change to the API contract, behaviour, validation or error codes.
- Events (F23-S05) and the web application (F23-S06).

## Acceptance criteria

Each criterion is one observable outcome that a test can pass or fail.

- AC-1: No record used as a controller request body or reachable from a controller response type is
  nested in another type; each is a top-level record in its module's `dto` package (or
  `shared.web`). The architecture tests fail on a fixture nested request record and a fixture
  nested response record, and pass for the application. (NFR-23)
- AC-2: The committed OpenAPI document and the generated client are byte-identical to the feature
  branch before this stage, including every schema name; `pnpm contracts:check` passes. (NFR-23)
- AC-3: Records that stay nested are used only inside their enclosing class and are not public.
  (NFR-23)
- AC-4: Behaviour is unchanged: every test before this stage still exists and passes with unchanged
  assertions (type references may follow the moves); the application starts in the local profile
  and the operator console and a salon workspace sign-in work as before. (NFR-23)

## Design notes

- springdoc derives schema names from the simple class name; nested classes today produce names
  such as `SignIn` or `Tokens`. Generate the document after each module's extraction and compare it
  with the committed one before going on.
- Strict JSON reading (`StrictJsonConfiguration`) and validation annotations move with the records
  unchanged.
- One module per commit, then the rule in its own commit.

## API and data changes

Not applicable for this stage. AC-2 proves the contract is untouched.

## UI and UX

Not applicable for this stage.

## Risks and test focus

- **Schema renamed by a moved record** (`api`): AC-2, checked after each module.
- **Validation or strict reading lost in a move** (`api`, `security`): the existing request
  validation and strict-JSON integration tests must pass unchanged.
- **A rule that cannot see nested response components** (`build`): AC-1's fixtures include a record
  reachable only as a component of another response.

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
- Any naming choice that the OpenAPI document constrained is listed in `handoff.notes`.
