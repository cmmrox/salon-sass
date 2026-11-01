#!/usr/bin/env bash
# F04-S01 the default-branch backfill on an existing database. Local machine only.
#   TC-F04-S01-028 AC-1: every salon that existed before the migration has exactly one default branch and an empty draft after it
#
# A scratch database of its own (never the product's): migrated with the built jar to V17, the
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

SCRATCH="qa_f04_backfill_$$"
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

begin_case TC-F04-S01-028 "AC-1: every salon that existed before the migration has exactly one default branch and an empty draft after it"
migrate 17 "$REPORT_DIR/F04-backfill-migrate-17.log"
before="$(scratch_value "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
[ "$before" = 17 ] || environment_fault "the scratch database did not reach V17 (it is at '$before'); see F04-backfill-migrate-17.log"
expect_equal "branch table before the stage's migration" "" "$(scratch_value "SELECT to_regclass('branding_branch')")"

# Existing salons as an existing database holds them: two with a published brand, one created
# before brands existed (registry row only), each in its own state.
WITH_BRAND_1="d4d4d4d4-0000-4000-8000-0000000000a1"
WITH_BRAND_2="d4d4d4d4-0000-4000-8000-0000000000a2"
NO_BRAND="d4d4d4d4-0000-4000-8000-0000000000a3"
cat <<SQL | scratch_psql >/dev/null || environment_fault "could not arrange the existing salons"
INSERT INTO tenant (id, slug, status, name) VALUES
  ('$WITH_BRAND_1', 'qa-existing-one', 'active', 'Registry Name One'),
  ('$WITH_BRAND_2', 'qa-existing-two', 'active', 'Registry Name Two'),
  ('$NO_BRAND', 'qa-existing-three', 'active', 'Registry Name Three');
INSERT INTO salon_brand (tenant_id, display_name, brand_colour, created_at, updated_at) VALUES
  ('$WITH_BRAND_1', 'Published One', '#D6086A', now(), now()),
  ('$WITH_BRAND_2', 'Published Two & Co', '#1F2937', now(), now());
SQL
salons="$(scratch_value "SELECT count(*) FROM tenant")"
expect_equal "existing salons arranged" 3 "$salons"

migrate latest "$REPORT_DIR/F04-backfill-migrate-latest.log"
after="$(scratch_value "SELECT max(version::int) FROM flyway_schema_history WHERE success")"
[ "$after" -gt 17 ] || { wrong "the stage's migrations did not run (still at '$after'); see F04-backfill-migrate-latest.log"; finish; }
failed="$(scratch_value "SELECT count(*) FROM flyway_schema_history WHERE NOT success")"
expect_equal "failed migrations" 0 "$failed"

for salon in "$WITH_BRAND_1" "$WITH_BRAND_2" "$NO_BRAND"; do
  row="$(scratch_value "SELECT
      (SELECT count(*) FROM branding_branch WHERE tenant_id = '$salon') || ':' ||
      (SELECT count(*) FROM branding_branch WHERE tenant_id = '$salon' AND is_default) || ':' ||
      (SELECT count(*) FROM branding_profile_draft WHERE tenant_id = '$salon') || ':' ||
      (SELECT count(*) FROM branding_branch_contact_draft c JOIN branding_branch b ON b.tenant_id = c.tenant_id AND b.id = c.branch_id
         WHERE c.tenant_id = '$salon' AND b.is_default) || ':' ||
      (SELECT count(*) FROM branding_profile_published WHERE tenant_id = '$salon') || ':' ||
      (SELECT count(*) FROM branding_branch_contact_published WHERE tenant_id = '$salon')")"
  expect_equal "salon $salon: branches:default:profile draft:default branch contact draft:published:published contacts" "1:1:1:1:0:0" "$row"
  empty="$(scratch_value "SELECT count(*) FROM branding_profile_draft WHERE tenant_id = '$salon'
      AND description IS NULL AND instagram_url IS NULL AND facebook_url IS NULL AND website_url IS NULL")"
  expect_equal "salon $salon: the profile draft is empty" 1 "$empty"
  contact="$(scratch_value "SELECT count(*) FROM branding_branch_contact_draft WHERE tenant_id = '$salon'
      AND address IS NULL AND latitude IS NULL AND longitude IS NULL AND phone IS NULL AND whatsapp IS NULL")"
  expect_equal "salon $salon: the branch contact draft is empty" 1 "$contact"
done
# The spec's design notes: the default branch is named after the salon's display name.
expect_equal "branch name of a salon with a published brand" "Published One" \
  "$(scratch_value "SELECT name FROM branding_branch WHERE tenant_id = '$WITH_BRAND_1'")"
expect_equal "branch name with characters a name may hold" "Published Two & Co" \
  "$(scratch_value "SELECT name FROM branding_branch WHERE tenant_id = '$WITH_BRAND_2'")"
expect_not_equal "branch name of a salon without a brand row" "" \
  "$(scratch_value "SELECT coalesce(name, '') FROM branding_branch WHERE tenant_id = '$NO_BRAND'")"
expect_equal "branches in the whole database (one per salon, none twice)" 3 "$(scratch_value "SELECT count(*) FROM branding_branch")"

finish
