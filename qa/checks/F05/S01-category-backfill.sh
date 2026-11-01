#!/usr/bin/env bash
# F05-S01 the default categories for salons that existed before the stage's migration. Local machine only.
#   TC-F05-S01-002 AC-1: every salon that existed before the migration has the ten default categories, in order, on its default branch, exactly once
#
# A scratch database of its own (never the product's): migrated with the built jar to the version
# before this stage, given salons the way an existing database holds them (an active, a suspended
# and a read-only salon, each with its default branch), then migrated to the current version. The
# jar runs with the `migrate` profile only (no local seed, no web server, no jobs), so it touches
# nothing but the scratch database, which is dropped on exit.
#
# Names to confirm against the F05-S01 handoff (qa/shared/catalogue.ts, PENDING_F05_S01): the
# version before the stage (BEFORE), the category table and its `name`, `position` and `branch_id`
# columns.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool java docker
require_product_up
require_database
. "$SCRIPTS/lib/common.sh"
load_settings
resolve_java_home || environment_fault "JDK 25 is required to run the API's migrations"
JAR="$PRODUCT_ROOT/apps/api/target/salon-api.jar"
[ -f "$JAR" ] || environment_fault "built API jar unavailable at $JAR"

BEFORE=23
CATEGORY_TABLE=catalogue_category
EXPECTED="Cuts & styling|Colour|Facials & skin|Nails|Brows & lashes|Waxing|Makeup & draping|Massage & spa|Bridal|Consultations"

SCRATCH="qa_f05_backfill_$$"
WORK="$(mktemp -d)"
restore() {
  docker exec "$POSTGRES_CONTAINER" psql -U "$DB_USER" -d postgres -qc "DROP DATABASE IF EXISTS $SCRATCH" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap restore EXIT

scratch_psql() { docker exec -i "$POSTGRES_CONTAINER" psql -U "$DB_USER" -d "$SCRATCH" -X -At -q -v ON_ERROR_STOP=1 2>&1; }
scratch_value() { printf '%s;\n' "$1" | scratch_psql; }
# migrate <target|latest> <log>: the jar's own Flyway run against the scratch database. The process
# may stop after migrating (the entities expect the latest schema); the schema history is the result.
migrate() {
  local target=()
  [ "$1" = latest ] || target=(--spring.flyway.target="$1")
  ( cd "$WORK" && SALON_DB_URL="jdbc:postgresql://localhost:${SALON_POSTGRES_PORT}/${SCRATCH}" \
      timeout 180 "$RESOLVED_JAVA_HOME/bin/java" -jar "$JAR" --spring.profiles.active=migrate ${target[@]+"${target[@]}"} ) >"$2" 2>&1 || true
}

docker exec "$POSTGRES_CONTAINER" psql -U "$DB_USER" -d postgres -qc "CREATE DATABASE $SCRATCH OWNER $DB_USER" >/dev/null \
  || environment_fault "could not create the scratch database $SCRATCH"

begin_case TC-F05-S01-002 "AC-1: every salon that existed before the migration has the ten default categories, in order, on its default branch, exactly once"
migrate "$BEFORE" "$REPORT_DIR/F05-backfill-migrate-$BEFORE.log"
before="$(scratch_value "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
[ "$before" = "$BEFORE" ] || environment_fault "the scratch database did not reach V$BEFORE (it is at '$before'); see F05-backfill-migrate-$BEFORE.log"
expect_equal "category table before the stage's migration" "" "$(scratch_value "SELECT to_regclass('$CATEGORY_TABLE')")"

# Existing salons as an existing database holds them: a registry row and a default branch (decision
# 083), one active, one suspended, one read-only after billing.
ACTIVE="e5e5e5e5-0000-4000-8000-0000000000b1"
SUSPENDED="e5e5e5e5-0000-4000-8000-0000000000b2"
READ_ONLY="e5e5e5e5-0000-4000-8000-0000000000b3"
cat <<SQL | scratch_psql >/dev/null || environment_fault "could not arrange the existing salons"
INSERT INTO tenant (id, slug, status, name, access_state) VALUES
  ('$ACTIVE', 'qa-existing-active', 'active', 'Existing Active', 'full'),
  ('$SUSPENDED', 'qa-existing-suspended', 'suspended', 'Existing Suspended', 'full'),
  ('$READ_ONLY', 'qa-existing-read-only', 'active', 'Existing Read-only', 'read_only');
INSERT INTO branding_branch (tenant_id, id, name, is_default, created_at, updated_at) VALUES
  ('$ACTIVE', 'e5e5e5e5-0000-4000-8000-0000000000c1', 'Existing Active', true, now(), now()),
  ('$SUSPENDED', 'e5e5e5e5-0000-4000-8000-0000000000c2', 'Existing Suspended', true, now(), now()),
  ('$READ_ONLY', 'e5e5e5e5-0000-4000-8000-0000000000c3', 'Existing Read-only', true, now(), now());
SQL
expect_equal "existing salons arranged" 3 "$(scratch_value "SELECT count(*) FROM tenant")"

migrate latest "$REPORT_DIR/F05-backfill-migrate-latest.log"
after="$(scratch_value "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
[ "$after" -gt "$BEFORE" ] || { wrong "the stage's migrations did not run (still at '$after'); see F05-backfill-migrate-latest.log"; finish; }
expect_equal "failed migrations" 0 "$(scratch_value "SELECT count(*) FROM flyway_schema_history WHERE NOT success")"

for salon in "$ACTIVE" "$SUSPENDED" "$READ_ONLY"; do
  expect_equal "salon $salon: the ten, in order" "$EXPECTED" \
    "$(scratch_value "SELECT string_agg(name, '|' ORDER BY position) FROM $CATEGORY_TABLE WHERE tenant_id = '$salon'")"
  expect_equal "salon $salon: no position shared" 10 \
    "$(scratch_value "SELECT count(DISTINCT position) FROM $CATEGORY_TABLE WHERE tenant_id = '$salon'")"
  expect_equal "salon $salon: every category on the default branch" 10 \
    "$(scratch_value "SELECT count(*) FROM $CATEGORY_TABLE c JOIN branding_branch b ON b.tenant_id = c.tenant_id AND b.id = c.branch_id
        WHERE c.tenant_id = '$salon' AND b.is_default")"
done
expect_equal "categories in the whole database (ten per salon, none twice)" 30 "$(scratch_value "SELECT count(*) FROM $CATEGORY_TABLE")"
expect_equal "no category without a salon" 0 \
  "$(scratch_value "SELECT count(*) FROM $CATEGORY_TABLE c WHERE NOT EXISTS (SELECT 1 FROM tenant t WHERE t.id = c.tenant_id)")"

finish
