# Multi-tenancy

One database, one schema, a `tenant_id` on every tenant-owned row. A leak between salons is the one
defect the business cannot survive, so isolation is enforced twice and tested on every build.

## Contents

- The ten rules
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
     USING      (tenant_id = current_setting('app.tenant_id', true)::uuid)
     WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
   ```

   With no setting, `current_setting(..., true)` is null, the comparison is null, and no row is
   visible. The system fails closed.

## Adding a table

- [ ] `tenant_id uuid NOT NULL`, with a foreign key to the tenant.
- [ ] `ENABLE` and `FORCE ROW LEVEL SECURITY`, and the `tenant_isolation` policy, in the same
      migration that creates the table.
- [ ] The entity carries `@TenantId`.
- [ ] Every unique constraint and every index starts with `tenant_id`.
- [ ] Every foreign key to another tenant table is composite: `(tenant_id, other_id)`.
- [ ] The application role is granted only the operations it needs.
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
