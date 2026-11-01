#!/usr/bin/env bash
# F04-S02 the brand-draft backfill on an existing database. Local machine only.
#   TC-F04-S02-023 AC-1: every salon that existed before the migration has a brand draft equal to its published brand after it
#
# A scratch database of its own (never the product's): migrated with the built jar to V18, the
# version before this stage, given salons the way an existing database holds them, then migrated
# to the current version. The jar runs with the `migrate` profile only (no local seed, no web
# server, no jobs), so it touches nothing but the scratch database, which is dropped on exit.
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

SCRATCH="qa_f04_brand_backfill_$$"
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

begin_case TC-F04-S02-023 "AC-1: every salon that existed before the migration has a brand draft equal to its published brand after it"
migrate 18 "$REPORT_DIR/F04-brand-backfill-migrate-18.log"
before="$(scratch_value "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
[ "$before" = 18 ] || environment_fault "the scratch database did not reach V18 (it is at '$before'); see F04-brand-backfill-migrate-18.log"
expect_equal "brand draft table before the stage's migration" "" "$(scratch_value "SELECT to_regclass('branding_brand_draft')")"

ONE="e5e5e5e5-0000-4000-8000-0000000000b1"
TWO="e5e5e5e5-0000-4000-8000-0000000000b2"
cat <<SQL | scratch_psql >/dev/null || environment_fault "could not arrange the existing salons"
INSERT INTO tenant (id, slug, status, name) VALUES
  ('$ONE', 'qa-brand-one', 'active', 'Registry One'),
  ('$TWO', 'qa-brand-two', 'active', 'Registry Two');
INSERT INTO salon_brand (tenant_id, display_name, brand_colour, created_at, updated_at) VALUES
  ('$ONE', 'Existing Brand One', '#1F2937', now(), now()),
  ('$TWO', 'Existing Brand & Two', '#FDE68A', now(), now());
SQL
expect_equal "existing salons arranged" 2 "$(scratch_value "SELECT count(*) FROM salon_brand")"

migrate latest "$REPORT_DIR/F04-brand-backfill-migrate-latest.log"
after="$(scratch_value "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
[ "$after" -gt 18 ] || { wrong "the stage's migrations did not run (still at '$after'); see F04-brand-backfill-migrate-latest.log"; finish; }
expect_equal "failed migrations" 0 "$(scratch_value "SELECT count(*) FROM flyway_schema_history WHERE NOT success")"

for salon in "$ONE" "$TWO"; do
  expect_equal "salon $salon: brand drafts" 1 "$(scratch_value "SELECT count(*) FROM branding_brand_draft WHERE tenant_id = '$salon'")"
  equal="$(scratch_value "SELECT count(*) FROM branding_brand_draft d JOIN salon_brand b ON b.tenant_id = d.tenant_id
      WHERE d.tenant_id = '$salon' AND d.display_name = b.display_name AND d.brand_colour = b.brand_colour
        AND d.tagline IS NULL AND d.logo_image_id IS NULL AND d.cover_image_id IS NULL")"
  expect_equal "salon $salon: the draft equals the published brand (name, colour, no tagline or images)" 1 "$equal"
  expect_equal "salon $salon: the published brand keeps its name and colour and gains no tagline or images" 1 \
    "$(scratch_value "SELECT count(*) FROM salon_brand WHERE tenant_id = '$salon' AND tagline IS NULL AND logo_image_id IS NULL AND cover_image_id IS NULL")"
done
expect_equal "published names unchanged" "Existing Brand One,Existing Brand & Two" \
  "$(scratch_value "SELECT string_agg(display_name, ',' ORDER BY tenant_id) FROM salon_brand")"
expect_equal "no image rows invented" 0 "$(scratch_value "SELECT count(*) FROM branding_image")"

finish
