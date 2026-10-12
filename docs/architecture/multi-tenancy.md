# Multi-tenancy

One database, one schema, a `tenant_id` on every tenant-owned row. A leak between salons is the one
defect the business cannot survive, so isolation is enforced twice and tested on every build.

## Contents

- The ten rules
- Salons and their hostnames
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
- What a suspended salon's hostname shows is F01-S03's to define. Until then no salon is suspended.
- A domain the salon owns (a later release) maps to the same tenant through the edge; it is another
  way in to the same resolution, not a second mechanism.

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
- **The application reads the registry only through `tenant_by_slug(text)`**, a `SECURITY DEFINER`
  function that returns a slug's id and status. It has no grant on `tenant`. A suspended salon
  resolves to no salon until F01-S03 defines what its hostname shows.

## Adding a table

- [ ] `tenant_id uuid NOT NULL`, with a foreign key to the tenant.
- [ ] `ENABLE` and `FORCE ROW LEVEL SECURITY`, and the `tenant_isolation` policy, in the same
      migration that creates the table.
- [ ] The entity carries `@TenantId`.
- [ ] Every unique constraint and every index starts with `tenant_id`.
- [ ] Every foreign key to another tenant table is composite: `(tenant_id, other_id)`.
- [ ] The application role is granted only the operations it needs, in the migration, to the Flyway
      placeholder for the application role (never to a role name written into the migration, and
      no migration creates a role).
- [ ] An isolation test covers the table (see below).

## Adding an endpoint

- [ ] The tenant is taken from the request context, never from a path, query or body parameter.
- [ ] An id that belongs to another salon returns "not found", exactly as an id that does not exist.
      Never "forbidden": that confirms the record exists.
- [ ] Native queries, reports and exports go through the same transaction and so the same policy.
- [ ] The role check is in the service, not only in the controller.

## Background jobs

A job iterates tenants explicitly and opens one transaction per tenant with that tenant set. A job
that needs every salon's data in one query is a platform job: it runs under the platform role,
touches platform tables only, and is reviewed as such.

## Caches, files, search and logs

- A cache key starts with the tenant id. So does anything cached at the edge.
- An object-storage path starts with the tenant's prefix. A signed URL is issued only after the
  tenant check.
- Every log line, trace and error report carries the tenant id (NFR-16). No personal data, one-time
  code, token or payment detail is logged.

## Platform data and operator access

Tenants, plans, subscriptions and operator users have no `tenant_id` and live apart from tenant
tables. The tenant role cannot read them; the application reads what a salon is entitled to through
a narrow service. An operator enters a salon's workspace only through a support session with a
recorded reason and an expiry, visible to the salon's owner and written to the audit log.

## Tests you must write

For every new table and endpoint, an integration test with two tenants that proves:

- tenant A cannot read tenant B's row through the endpoint (it gets "not found");
- tenant A cannot update or delete tenant B's row;
- tenant A cannot create a row that points at tenant B's row;
- a query with no tenant context returns nothing.

QA repeats these from outside for every endpoint. Yours prove the mechanism; theirs prove nothing
slipped past it.
