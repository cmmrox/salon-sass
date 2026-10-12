# F01-S02 Tenant registry and data isolation

## Objective

After this stage salons exist as tenants, the API knows which salon a request is for from its
hostname, and every row of salon data is protected twice: by Hibernate's tenant filter in the
application and by row-level security in PostgreSQL, with the application connected as a role that
cannot get round it. Three synthetic salons are seeded locally, and the first tenant-owned table
(a salon's published name and brand colour) proves the mechanism end to end. A request can only
ever see its own salon, and the system fails closed when it does not know which salon that is.

## Read first

- `docs/architecture/multi-tenancy.md`: the ten rules, salons and their hostnames, how the tenant
  reaches the database, the checklist for a new table and a new endpoint, and the tests you must write
- `docs/architecture/ARCHITECTURE.md`: tenancy and web addresses
- `docs/engineering/backend.md`: modules (`platform` owns tenants, `branding` owns brand data,
  `shared` holds the tenant context), persistence and migrations, health, tests
- `docs/architecture/tech-stack.md`: versions; Java 25 scoped values for the request's tenant
- `docs/operations/environments-and-releases.md`: the local environment and its scripts
- QA: `docs/qa/test-plan.md` (the synthetic salons, their fixed ids and hostnames),
  `docs/qa/test-types.md`, `docs/qa/automation.md` (isolation test shape, k6 concurrency, shell checks)

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| NFR-01 | Tenant isolation | In part: the mechanism, the registry, hostname resolution on the API, and the first tenant table. Every later table and endpoint follows the same checklist |
| NFR-15 | A failed dependency degrades one function, not the system | In part: a database that accepts connections and does not answer counts as down (CR-003) |
| NFR-07 | Least exposure | In part: the local API and web app listen on this machine only (CR-002) |

No functional requirement is delivered in full by this stage. BRD-03 (a subdomain per salon with a
secure certificate) is begun here on the API and completed in F01-S03 and F01-S05.

## Scope

**Platform (`platform` module)**
- The `tenant` table and a registry that resolves a slug to a salon, following "Salons and their
  hostnames" in `docs/architecture/multi-tenancy.md`, including the slug format and the reserved slugs,
  both enforced by the database.

**Tenant context (`shared`)**
- A request filter that resolves the salon from the `Host` header against the registry and holds it
  in a scoped value for the request.
- The tenant identifier resolver for Hibernate's `@TenantId`, and the per-transaction
  `set_config('app.tenant_id', ..., true)` described in the multi-tenancy document.
- A setting for the platform domain (`localhost` locally).

**Database roles**
- Migrations run as the owning role; the application connects as a separate role that owns no
  table, is not a superuser and cannot bypass row-level security. Both work on a fresh local volume
  and on one created by F01-S01.

**First tenant table (`branding` module)**
- `salon_brand`: the salon's published display name and brand colour, one row per salon, created
  with the full checklist from the multi-tenancy document.
- `GET /api/v1/salon`: the current salon's public brand, resolved from the hostname.

**Local seed**
- The three synthetic salons of `docs/qa/test-plan.md` (`alpha`, `beta`, `gamma`) with their fixed
  ids, slugs, names and colours, applied by `local-up.sh` and `local-reset.sh` in the local profile
  only.

**Approved change requests**
- CR-002: by default the local API and web app accept connections from this machine only, with an
  opt-in variable for testing from a phone on the same network.
- CR-003: a database that accepts connections and does not answer counts as down for the new
  endpoint, as it already does for health.

## Out of scope

- The web app resolving a hostname, the branded public shell, and the pages for an unknown or
  suspended salon: F01-S03. This stage changes nothing a browser shows.
- What a suspended salon's hostname answers: F01-S03. No salon is suspended in this stage.
- Creating, renaming or suspending a salon through an endpoint: F03 (operator console). Salons exist
  only through the local seed in this stage.
- Editing brand settings, drafts and publishing, logo and cover image: F04.
- Tenant context for background jobs, tenant-tagged logs and traces, the audit trail: F01-S04.
- The error-code enumeration in the contract (CR-001): F01-S04.
- Database roles on staging and production: F01-S05.
- Sign-in, and the signed token as a second source of the tenant: F02.

## Acceptance criteria

Each criterion is one observable outcome that a test can pass or fail.

- AC-1: With the product started by `local-up.sh`, `GET /api/v1/salon` on `alpha.localhost`,
  `beta.localhost` and `gamma.localhost` (the API's port) returns 200 with that salon's `slug`,
  `name` and `brandColour` exactly as listed in `docs/qa/test-plan.md`, and nothing else identifies
  the salon: no tenant id. The hostname is matched without regard to case. (NFR-01)
- AC-2: `GET /api/v1/salon` on a hostname that resolves to no salon returns 404 `not_found` as
  problem details, with a body identical apart from `instance` for each of: the bare platform domain
  (`localhost`), an unknown slug, a reserved slug (`console`, `www`), two labels in front of the
  platform domain (`alpha.beta.localhost`), and a hostname that only contains the platform domain
  (`alpha.localhost.example.com`). `GET /api/v1/meta` and `GET /actuator/health` still answer 200
  on every one of these hostnames. (NFR-01)
- AC-3: A tenant chosen by the client is never trusted: a request on `alpha.localhost` that also
  carries beta's slug or id in a query parameter or in any request header other than `Host`
  (including `X-Forwarded-Host` and `X-Tenant-Id`) returns alpha's brand. (NFR-01)
- AC-4: Under load, no response is ever served with another salon's data: at least 300 requests to
  `GET /api/v1/salon`, spread evenly over the three salons' hostnames and released together by at
  least 50 virtual users, each return the salon of their own hostname, with zero mismatches and zero
  errors. (NFR-01)
- AC-5: Connected directly to the local database as the application's role: the role is not a
  superuser, does not have `BYPASSRLS`, owns no table, and is refused (permission denied) a `SELECT`
  on `tenant`. (NFR-01)
- AC-6: Connected as the application's role, in a transaction with no tenant set, a `SELECT` from
  `salon_brand` returns no rows. In a transaction set to alpha it returns only alpha's row; an
  `UPDATE` or `DELETE` aimed at beta's row changes nothing (it affects 0 rows or is refused) and
  beta's row is unchanged afterwards; an
  `INSERT` with beta's `tenant_id` is refused. Setting the tenant in one transaction leaves the next
  transaction on the same connection with no tenant. (NFR-01)
- AC-7: In the database catalogue, every table with a `tenant_id` column has row-level security
  enabled and forced and a policy named `tenant_isolation`, `tenant_id` is `NOT NULL` with a foreign
  key to `tenant`, and every unique constraint or unique index on that table includes `tenant_id`.
  (NFR-01)
- AC-8: Connected as the owning role, inserting a tenant whose slug is reserved, shorter than 3 or
  longer than 30 characters, contains an uppercase letter or a character other than a letter, digit
  or hyphen, or begins or ends with a hyphen, is refused; so is a second tenant with an existing
  slug. A valid slug is accepted. (NFR-01)
- AC-9: `local-reset.sh` followed by `local-up.sh` leaves exactly the three synthetic salons, with
  the ids, slugs, names and colours of `docs/qa/test-plan.md`; running `local-up.sh` again does not
  duplicate or change them. Started with any profile other than `local` against an empty database,
  the API creates the schema and no salon exists. (NFR-01)
- AC-10: With PostgreSQL frozen (`local-service.sh pause postgres`), `GET /api/v1/salon` on
  `alpha.localhost` answers 503 `service_unavailable` within 5 seconds, both for requests already in
  flight at the moment of the freeze and for requests made during it, and never hangs. Within 5
  seconds of `resume postgres` it answers 200 again without the API being restarted. With
  PostgreSQL stopped it answers 503 within 5 seconds. (NFR-15, CR-003)
- AC-11: By default, the API's and the web app's ports accept connections on this machine's loopback
  addresses only: a connection to either port on the machine's non-loopback network address is
  refused, while `alpha.localhost` works from curl, from Node.js and from a browser, whether the name
  resolves to `127.0.0.1` or `::1` first. With the opt-in variable named in `.env.example` and
  `README.md` set, both ports are reachable on the network address. (NFR-07, CR-002)
- AC-12: The OpenAPI document describes `GET /api/v1/salon` with its 200 and 404 responses, and the
  generated client exposes it; `pnpm contracts:check` passes. (NFR-01)

## Design notes

- Follow `docs/architecture/multi-tenancy.md` exactly; it is the design. Where the real framework
  forces a difference, report it in `handoff.notes`, and the lead corrects the document.
- **Both layers must hold on their own.** QA can observe the combination from outside; only your
  integration tests can prove each layer separately. Write them: the Hibernate filter alone (with
  row-level security out of the way, for example as the owning role in the test), row-level security
  alone (a native query that bypasses Hibernate's filter), and the four cases in "Tests you must
  write". List these tests by name in `handoff.notes`.
- The tenant is held in a `ScopedValue`, not a `ThreadLocal`. The filter runs before anything that
  could open a transaction.
- The application role reads the registry only through a narrow path (for example a
  `SECURITY DEFINER` function that returns a slug's id and status), never by a grant on `tenant`.
- Role names, how the application role is created, and how its password reaches the API are yours
  to choose; make them work on a fresh volume and on the volume F01-S01 created, keep `.env.example`
  the single list of local settings, and name the choices in `handoff.notes`. The local passwords
  are local-only values, like F01-S01's.
- The seed is applied only in the local profile, by whichever mechanism you choose (for example a
  Flyway location enabled only in that profile). It must be idempotent.
- `GET /api/v1/salon` belongs to `branding`. The registry belongs to `platform`. The tenant context,
  filter and resolver belong to `shared`, which may not depend on `platform`; resolve that with a
  port in `shared` that `platform` implements, so the architecture tests stay as they are.
- The platform domain is a setting (for example `SALON_PLATFORM_DOMAIN`, default `localhost`) in
  `.env.example`.
- Every database wait keeps the limits set in F01-S01 (`docs/engineering/backend.md`); the registry
  lookup and the brand query both go through the same pool. If you cache the registry, the cache
  key includes the slug only for lookup and the result is the tenant id; say so in `handoff.notes`.
- CR-002: `alpha.localhost` resolves to `::1` first in Node.js and Python on this machine, so the
  loopback binding must answer there too.
- Give QA a way to run the database checks in AC-5 to AC-8 from a shell script without editing the
  product: the application role's name and local password in `.env.example`, and `psql` reachable
  through the `salon-postgres` container.

## API and data changes

| Method and path | Response |
| --- | --- |
| `GET /api/v1/salon` | 200 `{ "slug": string, "name": string, "brandColour": string ("#RRGGBB") }` for the salon of the request's hostname; 404 problem details `code: not_found` when the hostname resolves to no salon; 503 `service_unavailable` when the database is down or silent |

Data, in new migrations after `V1__baseline.sql`:

| Table | Columns | Rules |
| --- | --- | --- |
| `tenant` (platform) | `id uuid` PK, `slug text` unique, `status text` (`active`, `suspended`), `created_at`, `updated_at` | Slug format and reserved slugs as check constraints. No `tenant_id`. Not readable by the application role |
| `salon_brand` (tenant) | `tenant_id uuid` PK and FK to `tenant`, `display_name text`, `brand_colour text`, `created_at`, `updated_at` | The full checklist in `docs/architecture/multi-tenancy.md`: `ENABLE` and `FORCE ROW LEVEL SECURITY`, `tenant_isolation` policy, `@TenantId` on the entity. Colour checked as `#RRGGBB` |

Roles: an owning role for migrations, and an application role with only the grants it needs.

## UI and UX

Not applicable for this stage. Nothing a browser shows changes; the branded shell is F01-S03.

## Risks and test focus

| Risk | Test type QA should plan |
| --- | --- |
| One salon's data is served to another salon's request | `tenant-isolation` through the API on all three salons, and from the database as the application role (AC-1 to AC-3, AC-5 to AC-7) |
| A pooled connection or a reused virtual thread carries one salon into the next request | `concurrency` with k6: many simultaneous requests across the three hostnames, counting mismatches in thresholds (AC-4) |
| The hostname parser accepts a lookalike, or the client chooses its own salon | `security`: hostile `Host` values, forwarded headers, tenant ids in parameters (AC-2, AC-3) |
| The application could bypass row-level security after all | `data-integrity` and `security`: role attributes, grants and the catalogue check (AC-5, AC-7) |
| The seed duplicates, drifts from QA's constants, or reaches a non-local profile | `data-integrity` (AC-9) |
| A silent database hangs every salon's requests | `resilience` with `pause postgres`, requests in flight at the freeze (AC-10) |
| The local product is exposed on the network | `security`: a connection to the non-loopback address (AC-11) |
| The new endpoint and the contract drift apart | `api` against `docs/api/openapi.json` (AC-12) |

`functional` browser cases, `responsive`, `accessibility` and `branding` do not apply: no screen
changes. `permissions` does not apply: there is no sign-in yet. QA creates `qa/shared/tenants.ts`
with the three salons' constants in this stage, and the first k6 script under `qa/load/F01/`.

## Verification commands

The developer gates. Each line is run in its own shell from the repository root. They are also in
`stage.yaml` `verification.commands`.

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
- QA has passed the stage and every bug it found is verified fixed.
- The developer's integration tests prove each isolation layer on its own, and are named in
  `handoff.notes`.
- Before closing, the lead has corrected the documents under `docs/` for anything the developer
  reported in `handoff.notes` as different from them, and marked CR-002 and CR-003 `implemented`.
