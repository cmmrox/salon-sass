# F01-S01 Walking skeleton and local environment

## Objective

After this stage the repository builds, the API and the web app each start as a minimal shell, the
web shell talks to the API through a generated contract, and one command runs the product on a
developer's machine. No salon, no sign-in and no business rule exists yet. The
stage exists to prove the chosen stack works end to end and to give every later stage, and QA, a
place to stand.

## Read first

- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/tech-stack.md`: versions, prerequisites and the placeholder names
- `docs/architecture/repository-structure.md`
- `docs/engineering/backend.md`: module layout, API conventions, migrations, virtual threads
- `docs/engineering/web.md`: the three surfaces and their route groups
- `docs/design/ui-ux-standards.md`: tokens, states, test ids
- QA: `docs/qa/test-plan.md` and `docs/qa/automation.md`

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| NFR-17 | No per-tenant code or branches | Foundation only: one codebase, one build for every surface |
| NFR-18 | Backend and web are deployable at any time without downtime; older app versions keep working | Foundation only: versioned API path, migrations from the first table, contract drift check |

No functional requirement is delivered by this stage.

## Scope

**Repository**
- A pnpm workspace containing `apps/web` and `packages/api-contracts`. `qa/` is not
  part of it: QA keeps its own package and lock file there.
- `apps/api` built with the Maven wrapper.
- A root `README.md` quick start, `.editorconfig`, and `.env.example` files. No secret is committed.

**API (`apps/api`)**
- A Spring Boot application on the versions in `docs/architecture/tech-stack.md`, with virtual
  threads enabled.
- The module folders from `docs/engineering/backend.md`, empty except `shared`, and an architecture test that
  fails the build on a forbidden dependency between modules.
- A PostgreSQL connection, Flyway, and a baseline migration.
- `GET /actuator/health` with a database component, and `GET /actuator/info` in the local profile.
- `GET /api/v1/meta`: the first real endpoint, returning the service name, version, commit and
  server time.
- Errors as problem details with a stable `code`.
- The OpenAPI document generated from the controllers and committed at `docs/api/openapi.json`.

**Contract (`packages/api-contracts`)**
- A TypeScript client generated from `docs/api/openapi.json`.
- `pnpm contracts:check`, which fails when the committed document or the client is stale.

**Web (`apps/web`)**
- A Next.js App Router application in TypeScript, with the three route groups from
  `docs/engineering/web.md`.
- A status page that calls `meta` through the generated client and shows the result.
- Translation files from the first string, the token file, and the formatting module (empty of
  rules, but present).

**Local environment (`infra/`)**
- Docker Compose with PostgreSQL.
- `infra/scripts/local-up.sh`, `local-down.sh` and `local-reset.sh`.
- `infra/scripts/local-service.sh <start|stop> <postgres|api|web>`, so that a test can take one
  service down and bring it back.

**Continuous integration**
- A GitHub Actions workflow that runs every verification command on a pull request.

## Out of scope

- Tenants, the tenant filter and row-level security: F01-S02. This stage's single table, if any, is
  platform-level.
- Resolving a salon from a hostname, and any branding: F01-S03.
- The audit trail, outbox, jobs and tenant-tagged logging: F01-S04.
- Staging, deployment and certificates: F01-S05.
- Sign-in of any kind: F02.
- The customer mobile app, including its shell: F15, after the web milestone is released
  (`docs/ROADMAP.md`). Nothing under `apps/mobile` is created here. The generated contract is built
  so that the app can use it later without change.

## Acceptance criteria

Each criterion is one observable outcome that a test can pass or fail.

- AC-1: On a machine with the documented prerequisites, `infra/scripts/local-up.sh` starts
  PostgreSQL, the API and the web app, and prints the address of each. When a prerequisite is
  missing (Docker not running, wrong Java version) it exits non-zero and names what is missing. (NFR-17)
- AC-2: `GET /actuator/health` returns 200 with status `UP` and a database component that is `UP`.
  With PostgreSQL stopped it returns 503 within 5 seconds and does not hang. (NFR-18)
- AC-3: `GET /api/v1/meta` returns 200 with a JSON body containing `service`, `version`, `commit`
  and `time`, where `time` is an ISO-8601 instant in UTC, and the body matches the schema in
  `docs/api/openapi.json`. (NFR-18)
- AC-4: A request to an API path that does not exist returns 404 with content type
  `application/problem+json` and `code` equal to `not_found`. The body contains no stack trace,
  class name or framework version. (NFR-18)
- AC-5: Started with the local profile, `GET /actuator/info` reports the Java major version named in
  `docs/architecture/tech-stack.md` and that virtual threads are enabled. Started with any other profile, `GET /actuator/info` returns 404. (NFR-18)
- AC-6: Starting the API against an empty database applies the baseline migration, recorded in
  `flyway_schema_history`. Starting it a second time applies nothing and does not fail. (NFR-18)
- AC-7: The web app's home page shows the API's service name and version, obtained through the
  generated client. With the API stopped the page shows a plain "service unavailable" state and no
  error overlay, stack trace or blank page. (NFR-17)
- AC-8: `pnpm contracts:check` exits zero on the committed tree. Run in a copy of the repository in
  which `docs/api/openapi.json` or a generated client file has been altered, it exits non-zero and
  names the stale file. (NFR-18)
- AC-9: Every user-visible string in the web shell comes from a translation file, and every element
  a test needs to read or press carries a `data-testid`. (NFR-17)
- AC-10: A pull request on GitHub runs every verification command, and a failing command marks the
  pull request as failed. (NFR-18)

## Design notes

- Names are the placeholders in `docs/architecture/tech-stack.md`. Keep each in one place so the
  rename is mechanical.
- This stage sets conventions that no document fixes yet. For each of these, choose the framework's
  default or the most widely used option that works with the stack, and list the choice in
  `handoff.notes`; the lead writes them into the documents before the stage is closed:
  - the OpenAPI generator for the API and the TypeScript client generator, and the layout of
    `packages/api-contracts`;
  - in the web app: the translation library and where translation files live, how tokens are
    defined, where the formatting module lives, the linter and the unit-test setup;
  - Node.js and pnpm versions; local ports; database name and credentials for the local
    environment; the names of environment variables;
  - what `meta` returns for `service` and `version`.
- The dependencies needed to deliver this scope with that stack are agreed. Anything beyond them (a
  component library, a state library) needs the lead's agreement first.
- `meta` belongs to no business module, so it lives in `shared`.
- Follow the layouts in the documents under "Read first". Where the real tooling forces a
  difference, report it in `handoff.notes` so the lead can correct the document; do not leave the
  two disagreeing.
- `apps/api`, `apps/web` and `qa` already contain a short `AGENTS.md` and `CLAUDE.md`. Keep them.
- This machine currently has an older JDK than the stack requires. Ask the owner before installing
  system software.
- `meta` exists to prove the path from controller to OpenAPI document to generated client to the
  web shell. Keep it; later stages add the minimum supported app version to it (APP-11).
- `commit` in `meta` comes from the build, not from a hand-edited property.
- The database user split (owner for migrations, restricted role for the application) belongs to
  F01-S02. Do not pre-empt it with something that S02 would have to undo.
- Prerequisites to document and check in `local-up.sh`: JDK 25, Node.js with pnpm through Corepack,
  Docker. QA additionally needs k6 from F01-S02 onward.

## API and data changes

| Method and path | Response |
| --- | --- |
| `GET /api/v1/meta` | 200 `{ "service": string, "version": string, "commit": string, "time": string (UTC instant) }` |
| `GET /actuator/health` | 200 or 503, Spring Boot's health format with a `db` component |
| `GET /actuator/info` | Local profile only: Java version and the virtual-thread setting. 404 in any other profile |
| Any unknown path under `/api` | 404 problem details, `code: not_found` |

Data: a Flyway baseline migration. No tenant table is created in this stage.

## UI and UX

One deliberately plain screen, built from tokens so that later stages inherit the structure: a web
status page showing the product placeholder name, the API's service name and version, and the three
states loading, loaded and unavailable.

It is scaffolding, not a prototype screen. It is replaced by the branded shell in F01-S03.

## Risks and test focus

| Risk | Test type QA should plan |
| --- | --- |
| The newest versions do not work together, or only on the developer's machine | `functional`: a clean start from the documented steps |
| A dependency being down takes the service down, or hangs it | `resilience`: database stopped, API stopped |
| The contract and the clients drift apart silently | `api` against the committed OpenAPI document; the drift check itself |
| Error responses leak internals | `security`: unknown path, malformed request |
| The shell breaks on a phone-sized browser | `responsive`: 360 and 1280 wide only, since the screen is throwaway |
| The status page cannot be used with a keyboard or a screen reader | `accessibility`: the automated scan |
| A restart runs migrations twice, or fails | `data-integrity`: the migration history |
| A gate that never fails | `build`: the contract check and the translation and test-id scan, each proved on an altered copy |

`tenant-isolation` and `permissions` do not apply: the stage has no salon data and no protected
endpoint. They start in F01-S02 and F02. `mobile` does not apply: there is no app until F15.

QA creates its Playwright project under `qa/` in this stage, following `docs/qa/automation.md`.

## Verification commands

The developer gates. Each line is run in its own shell from the repository root. They are also in
`stage.yaml` `verification.commands`. This stage creates the scripts they call.

```bash
cd apps/api && ./mvnw -q verify
pnpm install --frozen-lockfile
pnpm -r lint
pnpm -r typecheck
pnpm -r test
pnpm --filter @salon/web build
pnpm contracts:check
qa/scripts/sdlc selftest
```

## Done criteria

- Every acceptance criterion holds on the running product.
- The verification commands are green locally.
- QA has passed the stage and every blocker and major bug is verified fixed.
- AC-10 is verified on GitHub once the owner has created the remote. Until then the developer notes
  it in `handoff.notes` and QA records its case as deferred, unblocked by "the owner creates the
  GitHub remote".
- Before closing, the lead has corrected the documents under `docs/` for anything the developer
  reported in `handoff.notes` as different from them.
