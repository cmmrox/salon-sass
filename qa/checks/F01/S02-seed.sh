#!/usr/bin/env bash
# F01-S02, AC-9: the local seed of the three synthetic salons, and its absence anywhere else.
#
# Cases: TC-F01-S02-070 to TC-F01-S02-072. "local-reset.sh followed by local-up.sh leaves exactly
# the three synthetic salons, with the ids, slugs, names and colours of docs/qa/test-plan.md;
# running local-up.sh again does not duplicate or change them. Started with any profile other than
# local against an empty database, the API creates the schema and no salon exists."
#
# What the salons must be is read from shared/tenants.ts, which holds the test plan's table.
#
# This check empties the local database through the developer's local-reset.sh; it holds nothing
# but synthetic salons. For the other profile it creates a second, empty database beside the
# local one, as the handoff says to, and drops it again. The product is running on the local
# profile and the local database when the check ends, however it ends.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool curl docker jq node lsof

WORK="$(mktemp -d)"
EMPTY_DB="salon_qa_empty"
require_product_up
require_database
load_salons

# psql as the owning role in a named database.
owner_psql_in() { docker exec -i "$POSTGRES_CONTAINER" psql -U "$DB_USER" -d "$1" -X -At -q 2>&1; }

API_RESTORE_NEEDED=0
restore() {
  if [ "$API_RESTORE_NEEDED" = 1 ]; then
    "$SCRIPTS/local-service.sh" stop api >/dev/null 2>&1
    wait_for_port_closed "$API_PORT" 40
    env -u SPRING_PROFILES_ACTIVE -u SALON_DB_URL "$SCRIPTS/local-service.sh" start api >"$WORK/restore-api.log" 2>&1 \
      || printf 'ENVIRONMENT: could not restart the API on the local profile: %s\n' "$(tail -n 3 "$WORK/restore-api.log" | tr '\n' ' ')" >&2
  fi
  printf 'drop database if exists %s;\n' "$EMPTY_DB" | owner_psql_in postgres >/dev/null 2>&1
  product_is_up || "$SCRIPTS/local-up.sh" >"$WORK/restore.log" 2>&1 \
    || printf 'ENVIRONMENT: could not start the product again; see %s\n' "$WORK/restore.log" >&2
  rm -rf "$WORK"
}
trap restore EXIT

# The salons as the test plan lists them, one line each, in the form the database is read in below.
EXPECTED_TENANTS="$(for slug in $SALON_SLUGS; do printf '%s|%s|active\n' "$(salon_value "$slug" ID)" "$slug"; done | sort)"
EXPECTED_BRANDS="$(for slug in $SALON_SLUGS; do printf '%s|%s|%s\n' "$(salon_value "$slug" ID)" "$(salon_value "$slug" NAME)" "$(salon_value "$slug" COLOUR)"; done | sort)"
tenants_now() { printf "select id || '|' || slug || '|' || status from tenant order by 1;\n" | owner_psql | sort; }
brands_now() { printf "select tenant_id || '|' || display_name || '|' || brand_colour from salon_brand order by 1;\n" | owner_psql | sort; }
# Everything about every row, to the microsecond it was last written.
rows_exactly() { printf "select 'tenant ' || t::text from tenant t order by id; select 'brand ' || b::text from salon_brand b order by tenant_id;\n" | owner_psql; }

expect_salons_served() { # <label>
  local slug
  for slug in $SALON_SLUGS; do
    expect_equal "$1: $(salon_value "$slug" HOST) is served" \
      "{\"slug\":\"$slug\",\"name\":$(jq -n --arg n "$(salon_value "$slug" NAME)" '$n'),\"brandColour\":\"$(salon_value "$slug" COLOUR)\"}" \
      "$(curl -s --max-time 10 "$(salon_api "$slug")" | jq -c '{slug, name, brandColour}' 2>/dev/null)"
  done
}

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-070 "local-reset.sh followed by local-up.sh leaves exactly the three synthetic salons of the test plan, and nothing that was there before"
# Something that is not in the test plan, put there first: a fourth salon, and a change to gamma.
# "Exactly three, exactly as listed" must be able to tell, and the reset must remove both.
LEFTOVER="$(owner_psql <<SQL
insert into tenant (id, slug) values ('d4d4d4d4-0000-4000-8000-0000000000d4', 'qa-leftover') on conflict do nothing;
insert into salon_brand (tenant_id, display_name, brand_colour) values ('d4d4d4d4-0000-4000-8000-0000000000d4', 'Left over from an earlier day', '#123456') on conflict do nothing;
update salon_brand set display_name = 'Gamma, as someone left it' where tenant_id = '$GAMMA_ID';
SQL
)"
expect_equal "errors putting a fourth salon and a changed name in place" "" "$LEFTOVER"
expect_not_equal "before the reset, with a fourth salon: the tenants" "$EXPECTED_TENANTS" "$(tenants_now)"
expect_not_equal "before the reset, with gamma renamed: the brands" "$EXPECTED_BRANDS" "$(brands_now)"
expect_equal "before the reset: the fourth salon is served" "Left over from an earlier day" "$(curl -s --max-time 10 "$(on_hostname "$API_URL" "qa-leftover.$PLATFORM_DOMAIN")/api/v1/salon" | jq -r '.name' 2>/dev/null)"

"$SCRIPTS/local-reset.sh" >"$WORK/reset.log" 2>&1; expect_equal "exit code of local-reset.sh" 0 "$?"
"$SCRIPTS/local-up.sh" >"$WORK/up.log" 2>&1; expect_equal "exit code of local-up.sh" 0 "$?"
cp "$WORK/reset.log" "$REPORT_DIR/seed-local-reset.log" 2>/dev/null
require_database

expect_equal "tenants after local-reset.sh and local-up.sh (id|slug|status)" "$EXPECTED_TENANTS" "$(tenants_now)"
expect_equal "brands after local-reset.sh and local-up.sh (tenant|name|colour)" "$EXPECTED_BRANDS" "$(brands_now)"
expect_equal "number of tenants" 3 "$(printf 'select count(*) from tenant;\n' | owner_psql)"
expect_equal "number of brand rows" 3 "$(printf 'select count(*) from salon_brand;\n' | owner_psql)"
expect_equal "salons that are not active" 0 "$(printf "select count(*) from tenant where status <> 'active';\n" | owner_psql)"
expect_salons_served "after the reset"
expect_equal "the fourth salon's hostname after the reset" 404 "$(http_code "$(on_hostname "$API_URL" "qa-leftover.$PLATFORM_DOMAIN")/api/v1/salon")"
# The application's role exists on the new volume, as the role it must be.
expect_equal "the application's role on the new volume (super, bypassrls)" "false|false" "$(printf "select rolsuper || '|' || rolbypassrls from pg_roles where rolname = '%s';\n" "$DB_APP_USER" | owner_psql)"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-071 "Running local-up.sh again, and starting the API again, neither duplicates nor changes the salons"
ROWS_BEFORE="$(rows_exactly)"
expect_equal "rows to compare (three tenants and three brands)" 6 "$(printf '%s\n' "$ROWS_BEFORE" | grep -cE '^(tenant|brand) ')"
"$SCRIPTS/local-up.sh" >"$WORK/up-again.log" 2>&1; expect_equal "exit code of a second local-up.sh" 0 "$?"
expect_equal "every row, after a second local-up.sh" "$ROWS_BEFORE" "$(rows_exactly)"
# The seed runs when the API starts, so the API is started again: twice.
for round in first second; do
  "$SCRIPTS/local-service.sh" stop api >"$WORK/stop-api.log" 2>&1
  wait_for_port_closed "$API_PORT" 40 || environment_fault "the API did not stop"
  "$SCRIPTS/local-service.sh" start api >"$WORK/start-api.log" 2>&1; expect_equal "exit code of the $round restart of the API" 0 "$?"
  expect_equal "every row, after the $round restart of the API (to the microsecond each was written)" "$ROWS_BEFORE" "$(rows_exactly)"
done
"$SCRIPTS/local-down.sh" >"$WORK/down.log" 2>&1; expect_equal "exit code of local-down.sh" 0 "$?"
"$SCRIPTS/local-up.sh" >"$WORK/up-third.log" 2>&1; expect_equal "exit code of local-up.sh after local-down.sh" 0 "$?"
expect_equal "every row, after local-down.sh and local-up.sh" "$ROWS_BEFORE" "$(rows_exactly)"
expect_equal "tenants" "$EXPECTED_TENANTS" "$(tenants_now)"
expect_equal "brands" "$EXPECTED_BRANDS" "$(brands_now)"
expect_salons_served "after starting again"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-072 "Started with a profile other than local against an empty database, the API creates the schema and no salon exists"
MIGRATIONS_LOCAL="$(printf "select version || ' ' || description || ' ' || checksum from flyway_schema_history where success order by installed_rank;\n" | owner_psql)"
TABLES_LOCAL="$(printf "select string_agg(table_name, ', ' order by table_name) from information_schema.tables where table_schema = 'public';\n" | owner_psql)"
for table in audit_entry flyway_schema_history outbox_message salon_brand scheduled_job tenant; do expect_contains "current committed schema contains $table" "$table" "$TABLES_LOCAL"; done
for version in {1..10}; do expect_matches "required delivered migration V$version exists in the comparison schema" "^$version " "$MIGRATIONS_LOCAL"; done
expect_equal "newest delivered migration in the comparison schema" 10 "$(printf '%s\n' "$MIGRATIONS_LOCAL" | tail -n 1 | cut -d ' ' -f 1)"
JDBC_EMPTY="jdbc:postgresql://localhost:${POSTGRES_PORT}/${EMPTY_DB}"
API_RESTORE_NEEDED=1
for profile in staging production; do
  "$SCRIPTS/local-service.sh" stop api >"$WORK/stop-api.log" 2>&1
  wait_for_port_closed "$API_PORT" 40 || environment_fault "the API did not stop"
  printf 'drop database if exists %s;\ncreate database %s;\n' "$EMPTY_DB" "$EMPTY_DB" | owner_psql_in postgres >"$WORK/create-db.log" 2>&1
  expect_equal "$profile: tables in the new database before the API starts" 0 "$(printf "select count(*) from information_schema.tables where table_schema = 'public';\n" | owner_psql_in "$EMPTY_DB")"
  # S05 staging instances do not migrate; its explicit migrate profile runs first.
  if [ "$profile" = staging ]; then
    with_identity_keys env SALON_TRUSTED_PROXIES=127.0.0.1 SALON_OTLP_ENDPOINT=http://127.0.0.1:43180 SALON_DB_URL="$JDBC_EMPTY" SPRING_PROFILES_ACTIVE=staging,migrate \
      bash -c '. infra/scripts/lib/common.sh; load_settings; resolve_java_home || exit 1; exec "$RESOLVED_JAVA_HOME/bin/java" -jar "$ROOT/apps/api/target/salon-api.jar"' \
      >"$REPORT_DIR/staging-empty-database-migrate.log" 2>&1 || environment_fault "explicit staging migration failed; see staging-empty-database-migrate.log"
  fi
  if with_identity_keys env SALON_TRUSTED_PROXIES=127.0.0.1 SALON_OTLP_ENDPOINT=http://127.0.0.1:43180 SPRING_PROFILES_ACTIVE="$profile" SALON_DB_URL="$JDBC_EMPTY" "$SCRIPTS/local-service.sh" start api >"$WORK/start-$profile.log" 2>&1; then
    right "$profile: the API started against the empty database"
  else
    wrong "$profile: the API did not start against the empty database: $(tail -n 4 "$WORK/start-$profile.log" | tr '\n' ' ')"
    continue
  fi
  expect_equal "$profile: GET /actuator/health" 200 "$(http_code "$API_URL/actuator/health")"
  # It is the other profile, and the other database, that answer.
  expect_equal "$profile: GET /actuator/info (served in the local profile only)" 404 "$(http_code "$API_URL/actuator/info")"
  expect_equal "$profile: the database the API's connections are in" "$EMPTY_DB" "$(printf "select coalesce(string_agg(distinct datname, ', '), '') from pg_stat_activity where usename = '%s' and backend_type = 'client backend';\n" "$DB_APP_USER" | owner_psql_in postgres)"
  # The schema was created: the same migrations, the two tables, and their protection.
  expect_equal "$profile: migrations applied to the empty database" "$MIGRATIONS_LOCAL" "$(printf "select version || ' ' || description || ' ' || checksum from flyway_schema_history where success order by installed_rank;\n" | owner_psql_in "$EMPTY_DB")"
  expect_equal "$profile: the tables match all current committed migrations" "$TABLES_LOCAL" "$(printf "select string_agg(table_name, ', ' order by table_name) from information_schema.tables where table_schema = 'public';\n" | owner_psql_in "$EMPTY_DB")"
  expect_equal "$profile: salon_brand's row-level security (enabled|forced|policies)" "true|true|tenant_isolation" "$(printf "select c.relrowsecurity || '|' || c.relforcerowsecurity || '|' || coalesce((select string_agg(polname, ',') from pg_policy where polrelid = c.oid), '') from pg_class c where c.oid = 'public.salon_brand'::regclass;\n" | owner_psql_in "$EMPTY_DB")"
  expect_equal "$profile: what the application's role may do there (salon_brand select|salon_brand write|tenant anything)" "true|false|false" "$(printf "select has_table_privilege('%s', 'public.salon_brand', 'select') || '|' || has_table_privilege('%s', 'public.salon_brand', 'insert, update, delete, truncate') || '|' || has_table_privilege('%s', 'public.tenant', 'select, insert, update, delete, truncate, references, trigger');\n" "$DB_APP_USER" "$DB_APP_USER" "$DB_APP_USER" | owner_psql_in "$EMPTY_DB")"
  # And no salon exists.
  expect_equal "$profile: tenants" 0 "$(printf 'select count(*) from tenant;\n' | owner_psql_in "$EMPTY_DB")"
  expect_equal "$profile: brand rows" 0 "$(printf 'select count(*) from salon_brand;\n' | owner_psql_in "$EMPTY_DB")"
  for slug in $SALON_SLUGS; do
    curl -s --max-time 10 -o "$WORK/none.json" "$(salon_api "$slug")"
    expect_equal "$profile: $(salon_value "$slug" HOST)" "404 not_found" "$(http_code "$(salon_api "$slug")") $(jq -r '.code' "$WORK/none.json" 2>/dev/null)"
  done
  expect_equal "$profile: GET /api/v1/meta on a salon's hostname" 200 "$(http_code "$(on_hostname "$API_URL" "$ALPHA_HOST")/api/v1/meta")"
  # Starting it a second time there still seeds nothing.
  "$SCRIPTS/local-service.sh" stop api >"$WORK/stop-api.log" 2>&1
  wait_for_port_closed "$API_PORT" 40 || environment_fault "the API did not stop"
  with_identity_keys env SALON_TRUSTED_PROXIES=127.0.0.1 SALON_OTLP_ENDPOINT=http://127.0.0.1:43180 SPRING_PROFILES_ACTIVE="$profile" SALON_DB_URL="$JDBC_EMPTY" "$SCRIPTS/local-service.sh" start api >"$WORK/start-$profile-again.log" 2>&1
  expect_equal "$profile: tenants after a second start" 0 "$(printf 'select count(*) from tenant;\n' | owner_psql_in "$EMPTY_DB")"
done

# Back to the local profile and the local database, which this never touched.
"$SCRIPTS/local-service.sh" stop api >"$WORK/stop-api.log" 2>&1
wait_for_port_closed "$API_PORT" 40 || environment_fault "the API did not stop"
env -u SPRING_PROFILES_ACTIVE -u SALON_DB_URL "$SCRIPTS/local-service.sh" start api >"$WORK/start-local.log" 2>&1 \
  && right "the API started again on the local profile" || wrong "the API did not start again on the local profile: $(tail -n 3 "$WORK/start-local.log" | tr '\n' ' ')"
API_RESTORE_NEEDED=0
expect_equal "errors dropping the empty database" "" "$(printf 'drop database if exists %s;\n' "$EMPTY_DB" | owner_psql_in postgres)"
expect_equal "afterwards: GET /actuator/info (the local profile again)" 200 "$(http_code "$API_URL/actuator/info")"
expect_equal "afterwards: every row of the local database" "$ROWS_BEFORE" "$(rows_exactly)"
expect_salons_served "afterwards"

finish
