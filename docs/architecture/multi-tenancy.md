# Multi-tenancy

One database, one schema, a `tenant_id` on every tenant-owned row. A leak between salons is the one
defect the business cannot survive, so isolation is enforced twice and tested on every build.

## Contents

- The ten rules
- Salons and their hostnames
- Domains a salon owns (designed, not yet built)
- How the tenant reaches the database
- Adding a table
- Adding an endpoint
- Background jobs
- Caches, files, search and logs
- Platform data and operator access
- Tests you must write

## The ten rules

| | Rule |
| --- | --- |
| T-1 | Every tenant-owned table has a non-null `tenant_id`. Platform tables (tenants, plans, operator users) are separate and cannot be reached with a tenant role |
| T-2 | Isolation is enforced twice: Hibernate `@TenantId` filters every query and stamps every insert, and a row-level security policy on each table compares `tenant_id` with a per-transaction setting, with `FORCE ROW LEVEL SECURITY` on |
| T-3 | The application connects with a database role that does not own the tables and cannot bypass row-level security. Migrations run under a different role |
| T-4 | The tenant setting is applied per transaction, never per session, so a pooled connection cannot carry one salon into another salon's request |
| T-5 | The tenant comes from the hostname or the signed token, on the server. A tenant id sent by the client is never trusted on its own; where both exist they must match |
| T-6 | Unique constraints, foreign keys and indexes include `tenant_id` |
| T-7 | A background job runs for one tenant at a time with an explicit context. A job with no tenant context can read nothing |
| T-8 | Caches, file paths, search indexes and log lines are keyed or tagged by tenant |
| T-9 | An automated suite attempts cross-tenant reads and writes through every endpoint on every build |
| T-10 | Operator access to a salon's data is a deliberate, logged, time-limited session (PLT-07), never a standing privilege |

## Salons and their hostnames

A salon is a row of the platform table `tenant`: its id (a UUID, never shown to a customer), its
slug, its status (`active` or `suspended`) and the times it was created and changed. The slug is
the salon's subdomain on the platform domain.

- **Hostname:** `<slug>.<platform domain>`, with the port ignored and compared without regard to case.
  The platform domain is a setting: `localhost` on a developer's machine (so `alpha.localhost`
  needs no hosts file), the staging and production domains elsewhere (open decision D-02).
- **A hostname matches only when it is exactly one label in front of the platform domain.**
  `a.b.<platform domain>`, `<slug>.<platform domain>.example.com` and the platform domain itself
  resolve to no salon.
- **Slug format:** 3 to 30 characters of lowercase letters, digits and hyphens, beginning and ending
  with a letter or digit. The database refuses any other.
- **Reserved slugs**, which can never be a salon, because they name the platform's own hostnames:
  `admin`, `api`, `app`, `apps`, `assets`, `auth`, `cdn`, `console`, `docs`, `help`, `login`,
  `mail`, `staging`, `static`, `status`, `support`, `www`. The database refuses them as well.
- A request on a hostname that resolves to no salon reaches the platform endpoints (`meta`, health)
  only. Every tenant endpoint answers 404 `not_found`, the same answer as for a salon that does not
  exist, so a caller cannot probe for salons.
- **A suspended salon** (decision 027): every endpoint that needs a salon answers 403 problem
  details with `code` `salon_suspended` and nothing of the salon; `meta` and health still answer.
  The web app shows "temporarily unavailable" with status 503. The tenant context binds no salon
  for such a request. A status change is seen on the next request; nothing caches it.
- A domain the salon owns (a later release) maps to the same tenant through the edge; it is another
  way in to the same resolution, not a second mechanism.

## Domains a salon owns (designed, not yet built)

Approved as a design in CR-021 and built only when the owner asks; until then, every salon is
reached on its platform subdomain. The tenant is still resolved by the API from the hostname. The
edge never decides or sends a tenant id: a tenant header set by a proxy is a client-supplied tenant
id on any hop that fails to strip it (T-5), and a per-domain proxy map needs a reload for every
salon and can disagree with the database.

- **Edge.** Caddy stays the edge and passes `Host` unchanged, stripping `X-Forwarded-Host` and any
  `X-Tenant*` header. Platform subdomains keep one wildcard certificate (DNS challenge). Salon-owned
  hostnames are served by a catch-all site with on-demand TLS, gated by an `ask` endpoint on the
  API's private network that answers 200 only for a verified hostname of an active salon and 404
  for everything else. Caddy's certificate storage is a persistent, backed-up volume.
- **Data.** A platform table `tenant_domain`: `hostname` (lowercase, punycode, no port, unique
  across all salons), `tenant_id`, `kind` (`primary` or `alias`, at most one primary per salon),
  `verification_token`, `verified_at`, `last_checked_at`, `tls_status` (`pending`, `active`,
  `failed`), `created_at`. Hostnames under the platform domain and reserved names are refused. An
  unverified claim expires after seven days so a domain cannot be squatted. The salon's
  `<slug>.<platform domain>` stays implicit and cannot be removed.
- **Resolution.** A hostname exactly one label in front of the platform domain resolves by slug, as
  today. Any other hostname is looked up exactly through a new `SECURITY DEFINER` function
  `tenant_by_hostname(text)`. A signed token and the hostname must still agree. An unknown hostname
  gets the same 404 `not_found` as an unknown slug.
- **Onboarding.** The owner enters a hostname (a subdomain such as `book.example.com` is
  recommended; an apex domain needs an address record tied to the server). They add a CNAME to the
  platform's domain target and a TXT record holding the verification token. A job confirms both,
  sets `verified_at`, requests the hostname once so the certificate is issued, records `tls_status`,
  and re-checks daily; a hostname whose DNS moved away is detached and the `ask` endpoint stops
  approving it.
- **Web.** The request proxy keeps reading `Host` only; an alias redirects (308) to the primary.
  Session cookies stay host-only, so a sign-in on one hostname is not a sign-in on another.
- **Mobile.** Unaffected: the app reaches one API host and names its salon by the signed tenant
  token. A dedicated salon app may list its salon's own domain for universal links at build time.
- **Limits.** Let's Encrypt allows 300 new orders per account per three hours and five failed
  validations per hostname per hour; certificate lifetimes shorten to 45 days by 2028, so renewal
  is automatic and never manual. A provider in front (such as a custom-hostname service) can be
  added later without changing tenant resolution.

## How the tenant reaches the database

1. The request filter resolves the tenant from the hostname, or from the signed token for the app,
   and rejects a request where the two disagree.
2. The tenant is held in a request-scoped holder (a scoped value, not a thread-local that could
   outlive the request on a reused thread).
3. Hibernate reads it through the tenant identifier resolver for `@TenantId`.
4. At the start of every transaction the application runs:

   ```sql
   SELECT set_config('app.tenant_id', :tenantId, true);  -- true = this transaction only
   ```

5. The policy on each table does the second check:

   ```sql
   ALTER TABLE service ENABLE ROW LEVEL SECURITY;
   ALTER TABLE service FORCE ROW LEVEL SECURITY;
   CREATE POLICY tenant_isolation ON service
     USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
     WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
   ```

   With no setting, the value is null, the comparison is null, and no row is visible. The system
   fails closed. The `NULLIF` is required: on a pooled connection that an earlier transaction set,
   `current_setting` returns an empty string afterwards, not null, and an empty string cast to a
   UUID is an error instead of "no rows".

Three consequences of how the framework works, which every later stage follows:

- **Resolve the salon before the transaction.** The request filter only binds the hostname's slug.
  The registry is asked on first use, through `TenantContext.require()`, so an endpoint that needs
  no salon (such as `meta`) never touches the database. A use case calls it before it opens its
  transaction.
- **A session with no salon uses the nil UUID.** Hibernate refuses a session with no tenant once an
  entity carries `@TenantId`, so the resolver answers `00000000-0000-0000-0000-000000000000`, which
  the database refuses as a tenant's id. It can therefore match no row.
- **The application reads the registry only through `SECURITY DEFINER` functions.** It has no grant
  on `tenant` or on any other platform table. `tenant_by_slug(text)` returns a slug's id, status and
  time zone; `tenant_by_id` the same for an id; `active_tenant_ids` the ids of active salons, for
  jobs. The outbox reaches across salons only through `outbox_due`, `outbox_due_events` and `outbox_backlog`, which
  return ids and counts and nothing of a message, and the scheduler only through `job_claim` and
  `job_finish`.
- **Row-level security is forced, so it holds for the owning role too**, which is not a superuser
  outside a developer's machine. A `SECURITY DEFINER` function that must see several salons' rows
  (`outbox_due`, `outbox_due_events`, `outbox_backlog`) sets each active salon in turn inside the function, and puts the
  caller's `app.tenant_id` back before it returns. A `SET` clause on the function cannot be used:
  only a superuser may attach one for a custom setting. `TenantContext`
  tells "no such salon" (404 `not_found`) from "suspended" (403 `salon_suspended`).

## Adding a table

- [ ] The table is named with its owning module's prefix and added to that module's entry in the
      ownership map (`docs/engineering/backend.md`, "Ownership of tables and paths").
- [ ] `tenant_id uuid NOT NULL`, with a foreign key to the tenant.
- [ ] `ENABLE` and `FORCE ROW LEVEL SECURITY`, and the `tenant_isolation` policy, in the same
      migration that creates the table.
- [ ] The entity carries `@TenantId`.
- [ ] Every unique constraint and every index starts with `tenant_id`.
- [ ] Every foreign key to another tenant table is composite: `(tenant_id, other_id)`, and points
      only at a table of the same module. Another module's row is held as its id.
- [ ] The application role is granted only the operations it needs, in the migration, to the Flyway
      placeholder for the application role (never to a role name written into the migration, and
      no migration creates a role).
- [ ] An isolation test covers the table (see below).
- [ ] From F04, a salon-operations table (catalogue, staff, booking, payment, customer activity) also
      carries `branch_id uuid NOT NULL`. Branches belong to `branding` (`branding_branch`), so only
      `branding`'s own tables have the composite foreign key `(tenant_id, branch_id)`; every other
      module holds the branch as an id, with no cross-module key, and checks it through `branding`'s
      provided interface `SalonBranches` when it is written. An index a query filters by branch
      leads with `(tenant_id, branch_id)`. Each salon has one default branch in R1; a query never
      assumes there is only one (decisions 073 and 083).

## Adding an endpoint

- [ ] The tenant is taken from the request context, never from a path, query or body parameter.
- [ ] An id that belongs to another salon returns "not found", exactly as an id that does not exist.
      Never "forbidden": that confirms the record exists.
- [ ] Native queries, reports and exports go through the same transaction and so the same policy.
      They are one of the named direct-SQL exceptions in `docs/engineering/backend.md` and state
      `tenant_id = ?` explicitly, because Hibernate's filter does not apply to them.
- [ ] The role check is in the service, not only in the controller.

## Background jobs

A job iterates tenants explicitly and opens one transaction per tenant with that tenant set. A job
that needs every salon's data in one query is a platform job: it runs under the platform role,
touches platform tables only, and is reviewed as such.

The scheduler and the outbox dispatcher in `shared` already do this (`docs/engineering/backend.md`).
A new job declares itself to the scheduler and receives one salon at a time; it never queries
across salons. A suspended salon's jobs are skipped and its messages wait.

An event between modules is an outbox message and follows the same rules: it carries the salon it
was written for, and its consumer runs in a transaction with that salon set, records the message as
processed for that salon, and can read nothing of another salon.

## Caches, files, search and logs

- A cache key starts with the tenant id. So does anything cached at the edge.
- An object-storage path starts with the tenant's prefix. A signed URL is issued only after the
  tenant check.
- Every log line, trace and error report carries the tenant id (NFR-16) as `tenant.id`, whenever a
  salon is bound. No personal data, one-time code, token or payment detail is logged. How the API
  does it is in `docs/engineering/backend.md`, "Logs, traces and meters".

## Platform data and operator access

Tenants, plans, subscriptions and operator users have no `tenant_id` and live apart from tenant
tables. The tenant role cannot read them; the application reads what a salon is entitled to through
a narrow service. An operator enters a salon's workspace only through a support session with a
recorded reason and an expiry, visible to the salon's owner and written to the audit log.

Operator authentication, the dedicated platform database connection and the narrowly granted
initial Owner/brand bootstrap are specified in `docs/architecture/operator-console.md`.
Initial creation does not grant an operator ongoing access to tenant records or a tenant session.

The yearly Sri Lankan holiday and Poya calendar is platform reference data with no `tenant_id`,
published by the operator. A salon's accepted or edited copy of it is tenant data (STF-04, decision 078).

## Tests you must write

For every new table and endpoint, an integration test with two tenants that proves:

- tenant A cannot read tenant B's row through the endpoint (it gets "not found");
- tenant A cannot update or delete tenant B's row;
- tenant A cannot create a row that points at tenant B's row;
- a query with no tenant context returns nothing.

QA repeats these from outside for every endpoint. Yours prove the mechanism; theirs prove nothing
slipped past it.

The first tenant table also proves each layer independently in the API's integration tests:
`SalonBrandRowLevelSecurityAloneIntegrationTest` uses SQL with Hibernate out of the path;
`SalonBrandHibernateFilterAloneIntegrationTest` disables RLS in its disposable test database;
`TenantTransactionIntegrationTest` reuses one pooled connection across salons and transactions
without a salon. Keep this independent coverage when adding tenant tables.
