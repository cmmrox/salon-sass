#!/usr/bin/env bash
# F01-S01, AC-6: the baseline migration, on an empty database and on a restart.
#
# Cases: TC-F01-S01-029 to TC-F01-S01-031. Expected results from AC-6: "Starting the API against
# an empty database applies the baseline migration, recorded in flyway_schema_history. Starting it
# a second time applies nothing and does not fail." The stage's scope adds that local-down.sh keeps
# the database's data and local-reset.sh deletes it.
#
# The local database is emptied by this check (through the developer's local-reset.sh). In this
# stage it holds nothing but the migration history. The product is running when the check ends.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool curl docker

WORK="$(mktemp -d)"
MARKER="qa_marker_$$_$(date +%s)"
require_product_up

restore() {
  product_is_up || "$SCRIPTS/local-up.sh" >"$WORK/restore.log" 2>&1 \
    || printf 'ENVIRONMENT: could not start the product again; see %s\n' "$WORK/restore.log" >&2
  psql_value "drop table if exists $MARKER" >/dev/null 2>&1
}
trap restore EXIT

history() { psql_value "select version || '|' || description || '|' || success || '|' || installed_on from flyway_schema_history order by installed_rank"; }
history_count() { psql_value "select count(*) from flyway_schema_history"; }
user_tables() { psql_value "select string_agg(table_name, ',' order by table_name) from information_schema.tables where table_schema = 'public'"; }

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-031 "local-down.sh followed by local-up.sh keeps the database's data"
psql_value "create table $MARKER (note text); insert into $MARKER values ('kept')" >/dev/null
expect_equal "before: the row QA wrote" kept "$(psql_value "select note from $MARKER")"
HISTORY_BEFORE="$(history)"
"$SCRIPTS/local-down.sh" >"$WORK/down.log" 2>&1; expect_equal "exit code of local-down.sh" 0 "$?"
expect_equal "running container $POSTGRES_CONTAINER after local-down.sh" "" "$(docker ps --quiet --filter "name=^${POSTGRES_CONTAINER}$")"
"$SCRIPTS/local-up.sh" >"$WORK/up.log" 2>&1; expect_equal "exit code of local-up.sh" 0 "$?"
expect_equal "after: the row QA wrote" kept "$(psql_value "select note from $MARKER")"
expect_equal "after: the migration history" "$HISTORY_BEFORE" "$(history)"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-029 "On an empty database the API applies the baseline migration and records it once in flyway_schema_history"
"$SCRIPTS/local-reset.sh" >"$WORK/reset.log" 2>&1; expect_equal "exit code of local-reset.sh" 0 "$?"
expect_equal "GET $API_URL/actuator/health after the reset" 200 "$(http_code "$API_URL/actuator/health")"
# The database really was emptied: what QA wrote before is gone, and the history is a new one.
expect_equal "tables in the database" flyway_schema_history "$(user_tables)"
HISTORY_AFTER_RESET="$(history)"
expect_not_equal "the migration history, against the one before the reset" "$HISTORY_BEFORE" "$HISTORY_AFTER_RESET"
expect_equal "rows in flyway_schema_history" 1 "$(history_count)"
expect_equal "the row's version" 1 "$(psql_value "select version from flyway_schema_history")"
expect_equal "the row's success flag" t "$(psql_value "select success from flyway_schema_history")"
expect_matches "the row's description" 'baseline' "$(psql_value "select description from flyway_schema_history")"
expect_equal "the row's type" SQL "$(psql_value "select type from flyway_schema_history")"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-030 "Starting the API a second time applies nothing and does not fail"
for round in second third; do
  "$SCRIPTS/local-service.sh" stop api >"$WORK/stop-$round.log" 2>&1
  wait_for_port_closed "$API_PORT" 40 && right "$round start: the API was stopped first" || wrong "$round start: the API did not stop, so this is not a second start"
  "$SCRIPTS/local-service.sh" start api >"$WORK/start-$round.log" 2>&1
  expect_equal "$round start: exit code of 'start api'" 0 "$?"
  expect_equal "$round start: GET $API_URL/actuator/health" 200 "$(http_code "$API_URL/actuator/health")"
  expect_equal "$round start: rows in flyway_schema_history" 1 "$(history_count)"
  expect_equal "$round start: the migration history, unchanged to the timestamp" "$HISTORY_AFTER_RESET" "$(history)"
  expect_equal "$round start: tables in the database" flyway_schema_history "$(user_tables)"
done

finish
