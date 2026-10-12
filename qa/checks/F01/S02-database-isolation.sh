#!/usr/bin/env bash
# F01-S02, AC-5 to AC-8: the database's own half of tenant isolation, checked in the local
# database the way the handoff says to reach it (psql in the salon-postgres container), as the
# application's role and as the owning role.
#
# Cases: TC-F01-S02-030 to TC-F01-S02-043.
#
# Every isolation case has one shape: first the data is shown to exist (the owning role sees three
# brand rows), then the application's role is shown not to reach what is not its salon's.
#
# Nothing here changes the product or its data. Every statement that could write runs inside a
# transaction that is rolled back. Where a point cannot be proved with the grants the product has
# (the application's role may only SELECT from salon_brand, so a write is refused before the policy
# is ever asked), the grant or the owner the proof needs is given inside such a transaction and
# is gone with it: no other connection ever sees it. The catalogue is compared before and after.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool docker node

docker info >/dev/null 2>&1 || environment_fault "Docker is not running"
require_product_up
require_database
load_salons

APP="$DB_APP_USER"
# How PostgreSQL says no. A GRANT by a role that holds nothing to give is answered with a warning
# that nothing was granted, which is a refusal too.
REFUSED='permission denied|must be owner|must be superuser|row-level security|no privileges were granted'

# What must be the same when this check ends as when it began: the rows, the grants, the policies,
# the role, the owner and the function.
catalogue_state() {
  owner_psql <<SQL
select 'tenant rows: ' || coalesce(string_agg(id::text || ' ' || slug || ' ' || status || ' ' || updated_at::text, '; ' order by slug), '') from tenant;
select 'brand rows: ' || coalesce(string_agg(tenant_id::text || ' ' || display_name || ' ' || brand_colour || ' ' || updated_at::text, '; ' order by tenant_id), '') from salon_brand;
select 'tables: ' || string_agg(c.relname || ' owner=' || c.relowner::regrole::text || ' rls=' || c.relrowsecurity || ' forced=' || c.relforcerowsecurity || ' acl=' || coalesce(c.relacl::text, '-'), '; ' order by c.relname)
  from pg_class c where c.relnamespace = 'public'::regnamespace and c.relkind in ('r', 'p', 'v', 'm', 'S');
select 'policies: ' || coalesce(string_agg(polrelid::regclass::text || '.' || polname || ' cmd=' || polcmd::text || ' roles=' || polroles::text || ' using=' || coalesce(pg_get_expr(polqual, polrelid), '-') || ' check=' || coalesce(pg_get_expr(polwithcheck, polrelid), '-'), '; ' order by polrelid::regclass::text, polname), '') from pg_policy;
select 'roles: ' || string_agg(rolname || ' super=' || rolsuper || ' bypass=' || rolbypassrls || ' createrole=' || rolcreaterole || ' createdb=' || rolcreatedb || ' repl=' || rolreplication, '; ' order by rolname) from pg_roles where rolname !~ '^pg_';
select 'functions: ' || coalesce(string_agg(p.oid::regprocedure::text || ' owner=' || p.proowner::regrole::text || ' definer=' || p.prosecdef || ' config=' || coalesce(p.proconfig::text, '-') || ' acl=' || coalesce(p.proacl::text, '-') || ' md5=' || md5(p.prosrc), '; ' order by p.oid::regprocedure::text), '') from pg_proc p where p.pronamespace = 'public'::regnamespace;
SQL
}
STATE_BEFORE="$(catalogue_state)"
printf '%s\n' "$STATE_BEFORE" >"$REPORT_DIR/database-state-before.txt"
# A state that could not be read cannot be compared: six lines, none of them an error.
if printf '%s' "$STATE_BEFORE" | grep -q 'ERROR' || [ "$(printf '%s\n' "$STATE_BEFORE" | grep -cE '^(tenant rows|brand rows|tables|policies|roles|functions): .')" -ne 6 ]; then
  environment_fault "could not read the catalogue's state before the check: $(printf '%s' "$STATE_BEFORE" | grep -m 2 -E 'ERROR|^$')"
fi

# expect_state_unchanged <when>: the rows, grants, policies, roles and functions are as they began.
expect_state_unchanged() {
  local now; now="$(catalogue_state)"
  if [ "$now" = "$STATE_BEFORE" ]; then
    right "the rows, grants, policies, roles and functions $1 are as they were when the check began"
  else
    wrong "the database $1 differs from how the check found it: $(diff <(printf '%s\n' "$STATE_BEFORE") <(printf '%s\n' "$now") | head -n 12)"
  fi
}

# The data exists: three salons, each with a brand, exactly the three of the test plan.
BRAND_ROWS="$(printf 'select count(*) from salon_brand;\n' | owner_psql)"
[ "$BRAND_ROWS" -ge 3 ] 2>/dev/null \
  || environment_fault "the owning role sees '$BRAND_ROWS' brand rows, not the three seeded salons; start from infra/scripts/local-reset.sh and local-up.sh"
for slug in $SALON_SLUGS; do
  found="$(printf "select t.slug || '|' || b.display_name from tenant t join salon_brand b on b.tenant_id = t.id where t.id = '%s';\n" "$(salon_value "$slug" ID)" | owner_psql)"
  [ "$found" = "$slug|$(salon_value "$slug" NAME)" ] \
    || environment_fault "the seeded salon $slug is not in the database as docs/qa/test-plan.md lists it (found '$found'); isolation cannot be judged without it"
done
note "the owning role sees $BRAND_ROWS brand rows: there is something to isolate"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-030 "The application's role is not a superuser, cannot bypass row-level security, belongs to no other role and owns nothing"
expect_equal "who the application's password logs in as" "$APP|$APP" "$(printf "select current_user || '|' || session_user;\n" | app_psql)"
ATTRIBUTES="$(printf "select 'super=' || rolsuper || ' bypassrls=' || rolbypassrls || ' createrole=' || rolcreaterole || ' createdb=' || rolcreatedb || ' replication=' || rolreplication from pg_roles where rolname = current_user;\n" | app_psql)"
expect_equal "the role's attributes, as it sees them itself" "super=false bypassrls=false createrole=false createdb=false replication=false" "$ATTRIBUTES"
expect_equal "is_superuser, in its own session" off "$(printf 'show is_superuser;\n' | app_psql)"
expect_equal "roles it is a member of" 0 "$(printf "select count(*) from pg_auth_members where member = '%s'::regrole;\n" "$APP" | owner_psql)"
expect_equal "predefined or other roles it can act as" "" "$(printf "select coalesce(string_agg(rolname, ', '), '') from pg_roles where rolname <> '%s' and pg_has_role('%s', oid, 'member');\n" "$APP" "$APP" | owner_psql)"
expect_equal "tables, views and sequences it owns" 0 "$(printf "select count(*) from pg_class where relowner = '%s'::regrole;\n" "$APP" | owner_psql)"
expect_equal "functions it owns" 0 "$(printf "select count(*) from pg_proc where proowner = '%s'::regrole;\n" "$APP" | owner_psql)"
expect_equal "schemas it owns" 0 "$(printf "select count(*) from pg_namespace where nspowner = '%s'::regrole and nspname !~ '^pg_';\n" "$APP" | owner_psql)"
expect_equal "databases it owns" 0 "$(printf "select count(*) from pg_database where datdba = '%s'::regrole;\n" "$APP" | owner_psql)"
expect_equal "settings fixed on the role (a tenant set there would follow every connection)" "" "$(printf "select coalesce(rolconfig::text, '') from pg_roles where rolname = '%s';\n" "$APP" | owner_psql)"
expect_equal "may create in the public schema" f "$(printf "select has_schema_privilege('%s', 'public', 'create');\n" "$APP" | owner_psql)"
expect_equal "may create a schema in the database" f "$(printf "select has_database_privilege('%s', current_database(), 'create');\n" "$APP" | owner_psql)"
# The owner of the tables is a different role from the one the API logs in as.
expect_not_equal "the owner of salon_brand" "$APP" "$(printf "select relowner::regrole from pg_class where oid = 'public.salon_brand'::regclass;\n" | owner_psql)"
# The running API really is connected as this role, and as no other.
expect_equal "roles the API's connections to the database use" "$APP" "$(printf "select coalesce(string_agg(distinct usename, ', '), '') from pg_stat_activity where datname = current_database() and backend_type = 'client backend' and pid <> pg_backend_pid() and application_name <> 'psql';\n" | owner_psql)"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-031 "The application's role is refused any read of the tenant table, by row, by column or by count"
expect_equal "tenants the owning role can read (the table is not empty)" 3 "$(printf 'select count(*) from tenant;\n' | owner_psql)"
for query in \
  "select * from tenant" \
  "select count(*) from tenant" \
  "select id from tenant" \
  "select slug from tenant where id = '$BETA_ID'" \
  "select 1 from tenant limit 1" \
  "select exists (select 1 from tenant where slug = '$BETA_SLUG')" \
  "table tenant" \
  "copy tenant to stdout" \
  "copy (select * from tenant) to stdout" \
  "select * from public.tenant tablesample system (100)" \
  "select b.display_name from salon_brand b join tenant t on t.id = b.tenant_id" \
  "update tenant set status = 'suspended' where slug = '$BETA_SLUG'" \
  "delete from tenant where slug = '$BETA_SLUG'" \
  "insert into tenant (id, slug) values (gen_random_uuid(), 'qa-intruder')" \
  "truncate tenant" \
  "lock table tenant in access share mode" \
  "select * from flyway_schema_history"; do
  expect_matches "as the application's role: $query" 'permission denied for (table|relation)' "$(as_salon "$ALPHA_ID" "$query")"
done
expect_equal "any privilege at all on tenant" f "$(printf "select has_table_privilege('%s', 'public.tenant', 'select, insert, update, delete, truncate, references, trigger');\n" "$APP" | owner_psql)"
expect_equal "any privilege on any column of tenant" f "$(printf "select has_any_column_privilege('%s', 'public.tenant', 'select, insert, update, references');\n" "$APP" | owner_psql)"
expect_equal "tenant readable by everyone (PUBLIC)" f "$(printf "select coalesce(bool_or(grantee = 0), false) from pg_class c, aclexplode(c.relacl) where c.oid = 'public.tenant'::regclass;\n" | owner_psql)"
expect_equal "what the statistics show the application's role of tenant and salon_brand (most common values would be other salons' names)" 0 "$(printf "select count(*) from pg_stats where tablename in ('tenant', 'salon_brand');\n" | app_psql)"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-032 "The application's role cannot give itself more: every attempt to get round the policy is refused and leaves the catalogue as it was"
OWNER="$DB_USER"
for attempt in \
  "set role $OWNER" \
  "set session authorization $OWNER" \
  "alter table salon_brand disable row level security" \
  "alter table salon_brand no force row level security" \
  "drop policy tenant_isolation on salon_brand" \
  "alter policy tenant_isolation on salon_brand using (true)" \
  "create policy qa_open on salon_brand using (true)" \
  "alter table salon_brand owner to $APP" \
  "alter role $APP bypassrls" \
  "alter role $APP superuser" \
  "alter role $APP set app.tenant_id = '$BETA_ID'" \
  "create role qa_intruder superuser" \
  "grant $OWNER to $APP" \
  "grant select on tenant to $APP" \
  "grant insert, update, delete on salon_brand to $APP" \
  "grant all on all tables in schema public to public" \
  "create table public.qa_intruder (x int)" \
  "create schema qa_intruder" \
  "create or replace function public.tenant_by_slug(requested_slug text) returns table (id uuid, status text) language sql as 'select ''$BETA_ID''::uuid, ''active''::text'" \
  "alter function public.tenant_by_slug(text) owner to $APP" \
  "alter function public.tenant_by_slug(text) reset search_path" \
  "drop function public.tenant_by_slug(text)" \
  "truncate salon_brand" \
  "create trigger qa_intruder before insert on salon_brand for each row execute function suppress_redundant_updates_trigger()" \
  "select pg_read_file('/etc/passwd')" \
  "select rolpassword from pg_authid" \
  "copy salon_brand to program 'cat'"; do
  expect_matches "refused: $attempt" "$REFUSED" "$(as_salon "$ALPHA_ID" "$attempt")"
done
# Turning row security off for the session is not a way round it: the query is refused, not answered.
expect_matches "set row_security = off, then read salon_brand" 'row-level security policy' "$(as_salon "$ALPHA_ID" "set local row_security = off; select count(*) from salon_brand")"
expect_state_unchanged "after every attempt"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-033 "The registry is reached only through tenant_by_slug, which gives one slug's id and status, to the application's role alone, and cannot be redirected"
expect_equal "tenant_by_slug('$ALPHA_SLUG')" "$ALPHA_ID|active" "$(as_salon none "select * from tenant_by_slug('$ALPHA_SLUG')")"
expect_equal "tenant_by_slug('$BETA_SLUG')" "$BETA_ID|active" "$(as_salon none "select * from tenant_by_slug('$BETA_SLUG')")"
for nothing in "'nobody-here'" "null" "''" "'%'" "'_____'" "'$(printf '%s' "$ALPHA_SLUG" | tr '[:lower:]' '[:upper:]')'" "'$ALPHA_SLUG'' or ''1''=''1'" "'$ALPHA_ID'"; do
  expect_equal "tenant_by_slug($nothing): no row, so it cannot be used to list salons" "" "$(as_salon none "select * from tenant_by_slug($nothing)")"
done
expect_equal "the columns it returns" "id status" "$(printf "select string_agg(a, ' ') from unnest((select proargnames[2:] from pg_proc where oid = 'public.tenant_by_slug(text)'::regprocedure)) a;\n" | owner_psql)"
# A table of the caller's own named tenant must not be what the function reads.
SHADOWED="$(as_salon none "create temp table tenant (id uuid, slug text, status text) on commit drop; insert into tenant values ('$BETA_ID', '$ALPHA_SLUG', 'active'); select * from tenant_by_slug('$ALPHA_SLUG')")"
expect_equal "tenant_by_slug('$ALPHA_SLUG') with a temporary table named tenant that says alpha is beta" "$ALPHA_ID|active" "$SHADOWED"
expect_equal "who may execute it, apart from its owner" "$APP" "$(printf "select coalesce(string_agg(case when a.grantee = 0 then 'PUBLIC' else a.grantee::regrole::text end, ', '), '') from pg_proc p, aclexplode(p.proacl) a where p.oid = 'public.tenant_by_slug(text)'::regprocedure and a.privilege_type = 'EXECUTE' and a.grantee <> p.proowner;\n" | owner_psql)"
expect_matches "another role calling it" 'permission denied for function tenant_by_slug' "$(owner_tx "create role qa_probe_nobody nologin; set local role qa_probe_nobody; select * from public.tenant_by_slug('$ALPHA_SLUG')")"
# Every function that runs with its owner's rights pins where it looks, and is not open to everyone.
expect_equal "SECURITY DEFINER functions in the schema (the registry's is one)" t "$(printf "select count(*) >= 1 from pg_proc where pronamespace = 'public'::regnamespace and prosecdef;\n" | owner_psql)"
expect_equal "SECURITY DEFINER functions with no fixed search_path, or executable by PUBLIC" "" "$(printf "select coalesce(string_agg(p.oid::regprocedure::text, ', '), '') from pg_proc p where p.pronamespace = 'public'::regnamespace and p.prosecdef and (not exists (select 1 from unnest(p.proconfig) c where c like 'search_path=%%') or p.proacl is null or exists (select 1 from aclexplode(p.proacl) a where a.grantee = 0));\n" | owner_psql)"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-034 "With no tenant set, the application's role sees no row of salon_brand, although three exist"
expect_equal "rows the owning role sees" 3 "$(printf 'select count(*) from salon_brand;\n' | owner_psql)"
expect_equal "the setting, in a new session" "unset" "$(printf "select coalesce(nullif(current_setting('app.tenant_id', true), ''), 'unset');\n" | app_psql)"
expect_equal "rows the application's role sees outside any transaction" 0 "$(printf 'select count(*) from salon_brand;\n' | app_psql)"
expect_equal "rows it sees in a transaction with no tenant" 0 "$(as_salon none "select count(*) from salon_brand")"
for slug in $SALON_SLUGS; do
  expect_equal "$slug's row, asked for by its id, with no tenant" 0 "$(as_salon none "select count(*) from salon_brand where tenant_id = '$(salon_value "$slug" ID)'")"
done
expect_equal "what a COPY of the table gives with no tenant" "" "$(as_salon none "copy salon_brand to stdout")"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-035 "Set to alpha, the application's role sees alpha's row and no other, however the question is put"
expect_equal "beta's row exists, for the owning role" "$BETA_NAME" "$(printf "select display_name from salon_brand where tenant_id = '%s';\n" "$BETA_ID" | owner_psql)"
expect_equal "every row alpha sees" "$ALPHA_ID|$ALPHA_NAME|$ALPHA_COLOUR" "$(as_salon "$ALPHA_ID" "select tenant_id, display_name, brand_colour from salon_brand")"
for slug in $SALON_SLUGS; do
  id="$(salon_value "$slug" ID)"
  expect_equal "every row $slug sees, set to $slug" "$id|$(salon_value "$slug" NAME)|$(salon_value "$slug" COLOUR)" "$(as_salon "$id" "select tenant_id, display_name, brand_colour from salon_brand")"
done
for question in \
  "select count(*) from salon_brand where tenant_id = '$BETA_ID'" \
  "select count(*) from salon_brand where tenant_id <> '$ALPHA_ID'" \
  "select count(*) from salon_brand where tenant_id in ('$BETA_ID', '$GAMMA_ID')" \
  "select count(*) from salon_brand where display_name like 'Beta%'" \
  "select count(*) from salon_brand where brand_colour = '$BETA_COLOUR'" \
  "select count(*) from only salon_brand where tenant_id = '$BETA_ID'" \
  "select count(*) from salon_brand tablesample system (100) where tenant_id <> '$ALPHA_ID'" \
  "select count(*) from salon_brand where ctid is not null and tenant_id <> '$ALPHA_ID'" \
  "select count(*) from (select * from salon_brand union all select * from salon_brand) s where tenant_id <> '$ALPHA_ID'" \
  "with every as (select * from salon_brand) select count(*) from every where tenant_id <> '$ALPHA_ID'" \
  "select count(*) from salon_brand a cross join salon_brand b where b.tenant_id <> '$ALPHA_ID'" \
  "select count(*) from salon_brand a where exists (select 1 from salon_brand b where b.tenant_id = '$BETA_ID')" \
  "create temp view qa_view as select * from salon_brand; select count(*) from qa_view where tenant_id <> '$ALPHA_ID'" \
  "create function pg_temp.qa_all() returns bigint language sql as 'select count(*) from public.salon_brand where tenant_id <> ''$ALPHA_ID'''; select pg_temp.qa_all()" \
  "prepare qa_plan as select count(*) from salon_brand where tenant_id = \$1; execute qa_plan('$BETA_ID')"; do
  expect_equal "rows of another salon: $question" 0 "$(as_salon "$ALPHA_ID" "$question")"
done
expect_equal "the rows a join of the table with itself gives alpha" 1 "$(as_salon "$ALPHA_ID" "select count(*) from salon_brand a cross join salon_brand b")"
expect_equal "what a COPY of the table gives alpha" "$ALPHA_ID" "$(as_salon "$ALPHA_ID" "copy (select tenant_id from salon_brand) to stdout")"
expect_equal "does beta's row exist, asked as alpha" f "$(as_salon "$ALPHA_ID" "select exists (select 1 from salon_brand where tenant_id = '$BETA_ID')")"
# A function that prints what it is shown, cheap enough to be tried on every row first, and an
# error that quotes the value it choked on: neither may be handed another salon's row.
LEAK="$(as_salon "$ALPHA_ID" "create function pg_temp.qa_tell(text) returns boolean language plpgsql cost 0.0000001 as \$f\$ begin raise notice 'saw %', \$1; return true; end \$f\$; select count(*) from salon_brand where pg_temp.qa_tell(display_name)")"
expect_contains "what a telltale function was shown" "saw $ALPHA_NAME" "$LEAK"
expect_no_match "what a telltale function was shown of the others" "Beta Hair|saw Gamma" "$LEAK"
CAST="$(as_salon "$ALPHA_ID" "select count(*) from salon_brand where display_name::int > 0")"
expect_contains "what an error that quotes a value quoted" "$ALPHA_NAME" "$CAST"
expect_no_match "what such an error quoted of the others" "Beta Hair|Gamma" "$CAST"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-036 "On the running product, set to alpha, an UPDATE or DELETE aimed at beta's row and an INSERT with beta's tenant_id change nothing"
beta_row() { printf "select display_name || '|' || brand_colour || '|' || updated_at::text from salon_brand where tenant_id = '%s';\n" "$BETA_ID" | owner_psql; }
BETA_BEFORE="$(beta_row)"
expect_matches "beta's row before" "^$BETA_NAME\|" "$BETA_BEFORE"
# "It affects 0 rows or is refused": a count of 0, or an error. Anything else is a change.
unchanged_or_refused() {
  if [ "$2" = 0 ] || printf '%s' "$2" | grep -qE "$REFUSED|violates"; then right "$1: '$2'"; else wrong "$1: expected 0 rows or a refusal, got '$2'"; fi
}
unchanged_or_refused "UPDATE of beta's row, as alpha" "$(as_salon "$ALPHA_ID" "with done as (update salon_brand set display_name = 'Taken over' where tenant_id = '$BETA_ID' returning 1) select count(*) from done")"
unchanged_or_refused "UPDATE of every row but alpha's, as alpha" "$(as_salon "$ALPHA_ID" "with done as (update salon_brand set brand_colour = '#000000' where tenant_id <> '$ALPHA_ID' returning 1) select count(*) from done")"
unchanged_or_refused "DELETE of beta's row, as alpha" "$(as_salon "$ALPHA_ID" "with done as (delete from salon_brand where tenant_id = '$BETA_ID' returning 1) select count(*) from done")"
unchanged_or_refused "UPDATE of beta's row, with no tenant" "$(as_salon none "with done as (update salon_brand set display_name = 'Taken over' where tenant_id = '$BETA_ID' returning 1) select count(*) from done")"
INSERTED="$(as_salon "$ALPHA_ID" "insert into salon_brand (tenant_id, display_name, brand_colour) values ('$BETA_ID', 'Second beta', '#000000') returning 'inserted'")"
expect_matches "INSERT with beta's tenant_id, as alpha: refused" "$REFUSED" "$INSERTED"
MOVED="$(as_salon "$ALPHA_ID" "update salon_brand set tenant_id = '$BETA_ID' where tenant_id = '$ALPHA_ID' returning 'moved'")"
expect_matches "moving alpha's own row to beta's tenant_id: refused" "$REFUSED" "$MOVED"
expect_equal "beta's row afterwards" "$BETA_BEFORE" "$(beta_row)"
expect_equal "brand rows afterwards" 3 "$(printf 'select count(*) from salon_brand;\n' | owner_psql)"
note "the refusal here is the missing grant (the application's role may only read salon_brand); the policy's own answer is TC-F01-S02-037"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-037 "The policy itself: with the writes granted, alpha changes its own row and nothing of beta's, and with no tenant nothing can be written"
# The grant lives inside one transaction of the owning role, which then becomes the application's
# role for the rest of it and rolls back. So the question the missing grant answered above is here
# put to the policy on the real table, and nothing is left behind.
as_app_with_writes() {
  local setting=""
  [ "$1" = none ] || setting="select set_config('app.tenant_id', '$1', true) \\g /dev/null"
  owner_tx "grant insert, update, delete on salon_brand to $APP;
set local role $APP;
$setting
$2"
}
expect_equal "who the transaction acts as" "$APP" "$(as_app_with_writes "$ALPHA_ID" "select current_user")"
# The writes are really possible in there: alpha can change, delete and re-create its own row.
expect_equal "rows an UPDATE of alpha's own row changes" 1 "$(as_app_with_writes "$ALPHA_ID" "with done as (update salon_brand set display_name = 'Alpha, renamed' where tenant_id = '$ALPHA_ID' returning 1) select count(*) from done")"
expect_equal "alpha deletes its own row and creates it again" "1|recreated" "$(as_app_with_writes "$ALPHA_ID" "with gone as (delete from salon_brand where tenant_id = '$ALPHA_ID' returning 1) select count(*) from gone \\gset
insert into salon_brand (tenant_id, display_name, brand_colour) values ('$ALPHA_ID', 'Alpha again', '#000000') returning :count || '|recreated'")"
expect_equal "rows an UPDATE aimed at beta's row changes" 0 "$(as_app_with_writes "$ALPHA_ID" "with done as (update salon_brand set display_name = 'Taken over' where tenant_id = '$BETA_ID' returning 1) select count(*) from done")"
expect_equal "rows a DELETE aimed at beta's row removes" 0 "$(as_app_with_writes "$ALPHA_ID" "with done as (delete from salon_brand where tenant_id = '$BETA_ID' returning 1) select count(*) from done")"
# An UPDATE and a DELETE of everything, then the owning role reads back inside the same transaction.
expect_equal "an UPDATE of every row, as alpha: rows changed, then the names the owner reads" "1|Changed by alpha; $BETA_NAME; $GAMMA_NAME" "$(as_app_with_writes "$ALPHA_ID" "with done as (update salon_brand set display_name = 'Changed by alpha' returning 1) select count(*) from done \\gset
reset role;
select :count || '|' || string_agg(display_name, '; ' order by tenant_id) from salon_brand")"
expect_equal "a DELETE of every row, as alpha: rows removed, then the salons the owner still finds" "1|$BETA_ID; $GAMMA_ID" "$(as_app_with_writes "$ALPHA_ID" "with done as (delete from salon_brand returning 1) select count(*) from done \\gset
reset role;
select :count || '|' || string_agg(tenant_id::text, '; ' order by tenant_id) from salon_brand")"
POLICY_REFUSAL='new row violates row-level security policy for table "salon_brand"'
expect_contains "INSERT with beta's tenant_id" "$POLICY_REFUSAL" "$(as_app_with_writes "$ALPHA_ID" "insert into salon_brand (tenant_id, display_name, brand_colour) values ('$BETA_ID', 'Second beta', '#000000')")"
expect_contains "INSERT with gamma's tenant_id, ON CONFLICT DO NOTHING" "$POLICY_REFUSAL" "$(as_app_with_writes "$ALPHA_ID" "insert into salon_brand (tenant_id, display_name, brand_colour) values ('$GAMMA_ID', 'x', '#000000') on conflict do nothing")"
expect_contains "INSERT with beta's tenant_id, ON CONFLICT DO UPDATE" "$POLICY_REFUSAL" "$(as_app_with_writes "$ALPHA_ID" "insert into salon_brand (tenant_id, display_name, brand_colour) values ('$BETA_ID', 'x', '#000000') on conflict (tenant_id) do update set display_name = 'Taken over'")"
expect_contains "MERGE aimed at beta's row" "$POLICY_REFUSAL" "$(as_app_with_writes "$ALPHA_ID" "merge into salon_brand b using (select '$BETA_ID'::uuid as id) s on b.tenant_id = s.id when matched then update set display_name = 'Taken over' when not matched then insert (tenant_id, display_name, brand_colour) values (s.id, 'Taken over', '#000000')")"
expect_contains "moving alpha's own row to beta's tenant_id" "$POLICY_REFUSAL" "$(as_app_with_writes "$ALPHA_ID" "update salon_brand set tenant_id = '$BETA_ID' where tenant_id = '$ALPHA_ID'")"
# A salon with no brand yet, so that no duplicate key can be what refuses: only the policy can.
NEW_SALON="d4d4d4d4-0000-4000-8000-0000000000d4"
with_new_salon() {
  owner_tx "insert into tenant (id, slug) values ('$NEW_SALON', 'qa-probe-salon');
grant insert, update, delete on salon_brand to $APP;
set local role $APP;
select set_config('app.tenant_id', '$1', true) \\g /dev/null
$2"
}
expect_equal "the new salon creating its own first brand row" created "$(with_new_salon "$NEW_SALON" "insert into salon_brand (tenant_id, display_name, brand_colour) values ('$NEW_SALON', 'New salon', '#000000') returning 'created'")"
expect_contains "alpha creating the new salon's first brand row" "$POLICY_REFUSAL" "$(with_new_salon "$ALPHA_ID" "insert into salon_brand (tenant_id, display_name, brand_colour) values ('$NEW_SALON', 'New salon', '#000000') returning 'created'")"
expect_contains "alpha moving its own row to the new salon" "$POLICY_REFUSAL" "$(with_new_salon "$ALPHA_ID" "update salon_brand set tenant_id = '$NEW_SALON' where tenant_id = '$ALPHA_ID' returning 'moved'")"
# With no tenant at all.
expect_equal "rows an UPDATE of everything changes with no tenant" 0 "$(as_app_with_writes none "with done as (update salon_brand set display_name = 'Taken over' returning 1) select count(*) from done")"
expect_equal "rows a DELETE of everything removes with no tenant" 0 "$(as_app_with_writes none "with done as (delete from salon_brand returning 1) select count(*) from done")"
expect_contains "INSERT of any salon's row with no tenant" "$POLICY_REFUSAL" "$(as_app_with_writes none "insert into salon_brand (tenant_id, display_name, brand_colour) values ('$BETA_ID', 'x', '#000000')")"
expect_state_unchanged "after the rolled-back transactions"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-038 "A tenant set in one transaction leaves the next transaction on the same connection with none"
SAME_CONNECTION="$(app_psql <<SQL
select 'pid-1=' || pg_backend_pid();
begin;
select set_config('app.tenant_id', '$ALPHA_ID', true) \g /dev/null
select 'in-committed=' || count(*) from salon_brand;
commit;
select 'pid-2=' || pg_backend_pid();
select 'setting-after-commit=' || coalesce(nullif(current_setting('app.tenant_id', true), ''), 'none');
select 'rows-after-commit=' || count(*) from salon_brand;
begin;
select 'rows-in-next-transaction=' || count(*) from salon_brand;
select 'beta-in-next-transaction=' || count(*) from salon_brand where tenant_id = '$BETA_ID';
commit;
begin;
select set_config('app.tenant_id', '$BETA_ID', true) \g /dev/null
select 'in-rolled-back=' || count(*) from salon_brand;
rollback;
select 'setting-after-rollback=' || coalesce(nullif(current_setting('app.tenant_id', true), ''), 'none');
select 'rows-after-rollback=' || count(*) from salon_brand;
begin;
select set_config('app.tenant_id', '$GAMMA_ID', true) \g /dev/null
select 1/0;
rollback;
select 'rows-after-failed=' || count(*) from salon_brand;
begin;
set local app.tenant_id = '$BETA_ID';
select 'in-set-local=' || count(*) from salon_brand;
commit;
select 'rows-after-set-local=' || count(*) from salon_brand;
select 'pid-3=' || pg_backend_pid();
SQL
)"
value_of() { printf '%s\n' "$SAME_CONNECTION" | sed -n "s/^$1=//p"; }
expect_equal "rows inside the transaction set to alpha" 1 "$(value_of in-committed)"
expect_equal "the setting after that transaction committed" none "$(value_of setting-after-commit)"
expect_equal "rows outside a transaction, after it" 0 "$(value_of rows-after-commit)"
expect_equal "rows in the next transaction, which set nothing" 0 "$(value_of rows-in-next-transaction)"
expect_equal "beta's row in that next transaction" 0 "$(value_of beta-in-next-transaction)"
expect_equal "rows inside the transaction set to beta" 1 "$(value_of in-rolled-back)"
expect_equal "the setting after that transaction rolled back" none "$(value_of setting-after-rollback)"
expect_equal "rows after it" 0 "$(value_of rows-after-rollback)"
expect_equal "rows after a transaction set to gamma failed part-way" 0 "$(value_of rows-after-failed)"
expect_equal "rows inside a transaction that used SET LOCAL" 1 "$(value_of in-set-local)"
expect_equal "rows after it" 0 "$(value_of rows-after-set-local)"
expect_matches "the connection's process id" '^[0-9]+$' "$(value_of pid-1)"
expect_equal "the connection at the second reading (the same one)" "$(value_of pid-1)" "$(value_of pid-2)"
expect_equal "the connection at the end (still the same one)" "$(value_of pid-1)" "$(value_of pid-3)"
# The query after the transaction must be "no rows", not an error that would take the request down.
expect_no_match "errors, apart from the division by zero that was the point" 'ERROR:  (invalid|permission|syntax)' "$SAME_CONNECTION"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-039 "A tenant that is nil, empty or not a tenant id at all shows no row, and never another salon's"
expect_equal "rows with the nil tenant id (what a session with no salon carries)" 0 "$(as_salon "$NIL_TENANT_ID" "select count(*) from salon_brand")"
expect_equal "rows with an empty value" 0 "$(as_salon "" "select count(*) from salon_brand")"
expect_equal "rows with an id that is no salon's" 0 "$(as_salon "99999999-0000-4000-8000-000000000009" "select count(*) from salon_brand")"
for junk in "$ALPHA_SLUG" "$BETA_ID, $ALPHA_ID" "$BETA_ID'' or ''1''=''1" "*" "null" "true" "$(printf '%s' "$BETA_ID" | tr -d '-' | cut -c1-31)"; do
  SEEN="$(as_salon "$junk" "select 'rows=' || count(*) from salon_brand")"
  if [ "$SEEN" = "rows=0" ] || printf '%s' "$SEEN" | grep -q 'invalid input syntax for type uuid'; then right "set to \"$junk\": no row ($SEEN)"; else wrong "set to \"$junk\": expected no row or a refusal, got '$SEEN'"; fi
done
# The same id written another way is still that salon and only that salon.
expect_equal "rows with alpha's id in upper case" "$ALPHA_ID" "$(as_salon "$(printf '%s' "$ALPHA_ID" | tr '[:lower:]' '[:upper:]')" "select tenant_id from salon_brand")"
expect_equal "no row can carry the nil tenant id: the registry refuses it" t "$(owner_tx "insert into tenant (id, slug) values ('$NIL_TENANT_ID', 'qa-nil-salon')" | grep -c 'violates check constraint' | sed 's/^1$/t/')"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-040 "Row-level security is forced: an owner of the table that is not a superuser is held to the policy too"
# Locally the owning role is a superuser, which no policy binds, so "forced" cannot be seen through
# it. Inside a transaction that is rolled back the table is given an ordinary owner, and that owner
# asks. Without FORCE ROW LEVEL SECURITY an owner sees every row.
as_plain_owner() {
  owner_tx "create role qa_probe_owner nologin;
alter table salon_brand owner to qa_probe_owner;
set local role qa_probe_owner;
$1"
}
expect_equal "the stand-in owner" "qa_probe_owner super=false bypassrls=false owns=true" "$(as_plain_owner "select current_user || ' super=' || r.rolsuper || ' bypassrls=' || r.rolbypassrls || ' owns=' || (c.relowner = r.oid) from pg_roles r, pg_class c where r.rolname = current_user and c.oid = 'public.salon_brand'::regclass")"
expect_equal "rows the owner sees with no tenant" 0 "$(as_plain_owner "select count(*) from salon_brand")"
expect_equal "rows the owner sees set to alpha" "$ALPHA_ID" "$(as_plain_owner "select set_config('app.tenant_id', '$ALPHA_ID', true) \\g /dev/null
select tenant_id from salon_brand")"
expect_equal "rows the owner's UPDATE of beta's row changes, set to alpha" 0 "$(as_plain_owner "select set_config('app.tenant_id', '$ALPHA_ID', true) \\g /dev/null
with done as (update salon_brand set display_name = 'Taken over' where tenant_id = '$BETA_ID' returning 1) select count(*) from done")"
# And the check can tell: the same owner, with the forcing taken off in the same rolled-back transaction, sees all three.
expect_equal "rows the same owner would see were it not forced (so the 0 above is the forcing)" 3 "$(as_plain_owner "alter table salon_brand no force row level security; select count(*) from salon_brand")"
expect_state_unchanged "after the rolled-back transactions"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-041 "In the catalogue, every table with a tenant_id is protected in full: enabled and forced, one fail-closed tenant_isolation policy, tenant_id NOT NULL with a foreign key to tenant, and in every unique index"
# One line for each thing wrong with any table that has a tenant_id column. No line means none.
read -r -d '' FAULTS <<'SQL' || true
with tenant_tables as (
  select c.oid, c.relname, c.relrowsecurity, c.relforcerowsecurity, a.attnotnull, a.attnum
    from pg_attribute a
    join pg_class c on c.oid = a.attrelid
    join pg_namespace n on n.oid = c.relnamespace
   where a.attname = 'tenant_id' and not a.attisdropped and c.relkind in ('r', 'p')
     and c.relpersistence <> 't' and n.nspname <> 'information_schema' and n.nspname !~ '^pg_'
)
select relname || ': row-level security is not enabled' from tenant_tables where not relrowsecurity
union all
select relname || ': row-level security is not forced' from tenant_tables where not relforcerowsecurity
union all
select t.relname || ': no policy named tenant_isolation' from tenant_tables t
 where not exists (select 1 from pg_policy p where p.polrelid = t.oid and p.polname = 'tenant_isolation')
union all
select relname || ': tenant_id is not NOT NULL' from tenant_tables where not attnotnull
union all
select t.relname || ': tenant_id has no foreign key to tenant' from tenant_tables t
 where not exists (select 1 from pg_constraint k where k.conrelid = t.oid and k.contype = 'f'
                      and k.confrelid = 'public.tenant'::regclass and k.conkey = array[t.attnum])
union all
select t.relname || ': unique index ' || i.indexrelid::regclass::text || ' does not include tenant_id'
  from tenant_tables t join pg_index i on i.indrelid = t.oid
 where i.indisunique and not (t.attnum = any (i.indkey::int2[]))
union all
select t.relname || ': a second policy, ' || p.polname || ', which could let through what tenant_isolation refuses'
  from tenant_tables t join pg_policy p on p.polrelid = t.oid where p.polname <> 'tenant_isolation'
union all
select t.relname || ': tenant_isolation does not cover every command' from tenant_tables t
  join pg_policy p on p.polrelid = t.oid and p.polname = 'tenant_isolation' where p.polcmd <> '*'
union all
select t.relname || ': tenant_isolation does not apply to every role' from tenant_tables t
  join pg_policy p on p.polrelid = t.oid and p.polname = 'tenant_isolation' where p.polroles <> '{0}'
union all
select t.relname || ': tenant_isolation does not compare tenant_id with the app.tenant_id setting, when reading or when writing'
  from tenant_tables t join pg_policy p on p.polrelid = t.oid and p.polname = 'tenant_isolation'
 where coalesce(pg_get_expr(p.polqual, p.polrelid), '') !~ 'tenant_id = .*current_setting\(''app\.tenant_id''.*true\)'
    or coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '') !~ 'tenant_id = .*current_setting\(''app\.tenant_id''.*true\)'
union all
select t.relname || ': readable or writable by everyone (PUBLIC)' from tenant_tables t
  join pg_class c on c.oid = t.oid, aclexplode(c.relacl) a where a.grantee = 0
order by 1;
SQL
TENANT_TABLES="$(printf "select coalesce(string_agg(c.relname, ' ' order by c.relname), '') from pg_attribute a join pg_class c on c.oid = a.attrelid join pg_namespace n on n.oid = c.relnamespace where a.attname = 'tenant_id' and not a.attisdropped and c.relkind in ('r', 'p') and n.nspname !~ '^pg_' and n.nspname <> 'information_schema';\n" | owner_psql)"
expect_matches "the tables that have a tenant_id (there is at least salon_brand to check)" '(^| )salon_brand( |$)' "$TENANT_TABLES"
expect_equal "faults found in the tables that have a tenant_id ($TENANT_TABLES)" "" "$(printf '%s\n' "$FAULTS" | owner_psql)"
# The same question, asked of a table built wrongly in every way and of one built almost rightly,
# inside a transaction that is rolled back: a check that could not find a fault would find none here either.
WRONG="$(owner_tx "create table qa_probe_wrong (id uuid primary key, tenant_id uuid, code text unique);
grant select on qa_probe_wrong to public;
create table qa_probe_nearly (tenant_id uuid not null references tenant (id), code text, unique (tenant_id, code));
alter table qa_probe_nearly enable row level security;
alter table qa_probe_nearly force row level security;
create policy tenant_isolation on qa_probe_nearly for select using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy qa_everyone on qa_probe_nearly using (true);
$FAULTS")"
for fault in \
  "qa_probe_wrong: row-level security is not enabled" \
  "qa_probe_wrong: row-level security is not forced" \
  "qa_probe_wrong: no policy named tenant_isolation" \
  "qa_probe_wrong: tenant_id is not NOT NULL" \
  "qa_probe_wrong: tenant_id has no foreign key to tenant" \
  "qa_probe_wrong: unique index qa_probe_wrong_pkey does not include tenant_id" \
  "qa_probe_wrong: unique index qa_probe_wrong_code_key does not include tenant_id" \
  "qa_probe_wrong: readable or writable by everyone (PUBLIC)" \
  "qa_probe_nearly: a second policy, qa_everyone" \
  "qa_probe_nearly: tenant_isolation does not cover every command" \
  "qa_probe_nearly: tenant_isolation does not compare tenant_id with the app.tenant_id setting"; do
  expect_contains "the check, shown a table built wrongly, reports" "$fault" "$WRONG"
done
expect_no_match "the check, shown those tables, on the product's own table" '^salon_brand:' "$WRONG"
expect_equal "tables left behind by the probe" 0 "$(printf "select count(*) from pg_class where relname like 'qa_probe%%';\n" | owner_psql)"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-042 "The registry refuses a slug that is reserved, too short, too long, or anything but lowercase letters, digits and inner hyphens, and a slug already taken"
# try_slug <sql literal>: 'accepted', or the constraint that refused it. Rolled back either way.
try_slug() {
  local out
  out="$(owner_tx "insert into tenant (id, slug) values (gen_random_uuid(), $1) returning 'accepted'")"
  if [ "$out" = accepted ]; then printf accepted; else printf '%s' "$out" | grep -oE 'violates (check|not-null|unique) constraint( "[a-z_]+")?' | head -n 1; fi
}
refused_slug() {
  local got; got="$(try_slug "$2")"
  if [ -n "$got" ] && [ "$got" != accepted ]; then right "refused, $1: $2 ($got)"; else wrong "$1: the slug $2 was not refused (got '${got:-neither accepted nor a constraint}')"; fi
}
accepted_slug() { expect_equal "accepted, $1: $2" accepted "$(try_slug "$2")"; }

RESERVED_COUNT=0
for slug in $RESERVED_SLUGS; do
  refused_slug "reserved" "'$slug'"
  RESERVED_COUNT=$((RESERVED_COUNT + 1))
done
expect_equal "reserved slugs tried (the list in docs/architecture/multi-tenancy.md)" 17 "$RESERVED_COUNT"
refused_slug "1 character" "'a'"
refused_slug "2 characters" "'ab'"
refused_slug "31 characters" "'$(printf 'a%.0s' $(seq 1 31))'"
refused_slug "64 characters" "'$(printf 'a%.0s' $(seq 1 64))'"
refused_slug "empty" "''"
refused_slug "no slug at all" "null"
refused_slug "an uppercase letter" "'Alpha2'"
refused_slug "all uppercase" "'SALON'"
refused_slug "an uppercase reserved word" "'ADMIN'"
refused_slug "an underscore" "'al_pha'"
refused_slug "a dot" "'al.pha'"
refused_slug "a space" "'al pha'"
refused_slug "a leading space" "' salon'"
refused_slug "a trailing space" "'salon '"
refused_slug "a trailing newline" "E'salon\\n'"
refused_slug "a newline inside" "E'sal\\non'"
refused_slug "a slash" "'al/pha'"
refused_slug "an at sign" "'al@pha'"
refused_slug "a percent sign" "'al%pha'"
refused_slug "an accented letter" "'salón'"
refused_slug "letters of another script" "'සැලෝන්'"
refused_slug "a Cyrillic letter that looks like a" "'s' || chr(1072) || 'lon'"
refused_slug "a leading hyphen" "'-salon'"
refused_slug "a trailing hyphen" "'salon-'"
refused_slug "only hyphens" "'---'"
refused_slug "a slug already taken" "'$ALPHA_SLUG'"
refused_slug "a slug already taken, by another salon" "'$GAMMA_SLUG'"
accepted_slug "3 characters" "'abc'"
accepted_slug "30 characters" "'$(printf 'a%.0s' $(seq 1 30))'"
accepted_slug "digits only" "'123'"
accepted_slug "an inner hyphen" "'hair-and-beauty'"
accepted_slug "letters, digits and hyphens" "'salon-7-colombo'"
accepted_slug "a slug that only contains a reserved word" "'api-salon'"
accepted_slug "a slug that only begins with a reserved word" "'wwwsalon'"
expect_matches "a second tenant with an existing id" 'duplicate key value violates unique constraint' "$(owner_tx "insert into tenant (id, slug) values ('$ALPHA_ID', 'qa-second-alpha')")"
expect_equal "tenants afterwards" 3 "$(printf 'select count(*) from tenant;\n' | owner_psql)"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-043 "The tenant and brand tables hold their own rules: a known status, a real tenant, one brand per salon, a name, and a colour written #RRGGBB"
refused_by() { expect_matches "refused: $1" "$3" "$(owner_tx "$2")"; }
refused_by "a status that is neither active nor suspended" "insert into tenant (id, slug, status) values (gen_random_uuid(), 'qa-status', 'closed')" 'violates check constraint'
refused_by "no status" "insert into tenant (id, slug, status) values (gen_random_uuid(), 'qa-status', null)" 'violates not-null constraint'
expect_equal "accepted: a suspended tenant" accepted "$(owner_tx "insert into tenant (id, slug, status) values (gen_random_uuid(), 'qa-status', 'suspended') returning 'accepted'")"
refused_by "a brand for a tenant that does not exist" "insert into salon_brand (tenant_id, display_name, brand_colour) values ('99999999-0000-4000-8000-000000000009', 'Nobody', '#000000')" 'violates foreign key constraint'
refused_by "a brand with no tenant" "insert into salon_brand (tenant_id, display_name, brand_colour) values (null, 'Nobody', '#000000')" 'violates not-null constraint'
refused_by "a second brand row for alpha" "insert into salon_brand (tenant_id, display_name, brand_colour) values ('$ALPHA_ID', 'Second alpha', '#000000')" 'duplicate key value violates unique constraint'
refused_by "an empty name" "update salon_brand set display_name = '' where tenant_id = '$ALPHA_ID'" 'violates check constraint'
refused_by "a name of spaces" "update salon_brand set display_name = '   ' where tenant_id = '$ALPHA_ID'" 'violates check constraint'
refused_by "no name" "update salon_brand set display_name = null where tenant_id = '$ALPHA_ID'" 'violates not-null constraint'
for colour in "'D6086A'" "'#D6086'" "'#D6086A7'" "'#GGGGGG'" "'red'" "''" "'#D6086A '" "E'#D6086A\\n'" "'rgb(1,2,3)'" "'#D60 86A'"; do
  refused_by "the colour $colour" "update salon_brand set brand_colour = $colour where tenant_id = '$ALPHA_ID'" 'violates check constraint'
done
refused_by "no colour" "update salon_brand set brand_colour = null where tenant_id = '$ALPHA_ID'" 'violates not-null constraint'
for colour in "'#000000'" "'#FFFFFF'" "'#1A2B3C'"; do
  expect_equal "accepted: the colour $colour" accepted "$(owner_tx "update salon_brand set brand_colour = $colour where tenant_id = '$ALPHA_ID' returning 'accepted'")"
done

expect_state_unchanged "when the check ends"
printf '%s\n' "$(catalogue_state)" >"$REPORT_DIR/database-state-after.txt"
finish
