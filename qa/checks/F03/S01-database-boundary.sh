#!/usr/bin/env bash
# F03-S01 database boundary and startup refusal. Local machine only; never a remote environment.
#   TC-F03-S01-027 tenant and platform database roles cannot cross the platform boundary
#   TC-F03-S01-028 the API refuses to start with an owning, RLS-bypassing or missing platform role
#   TC-F03-S01-029 (database half) platform audit rows cannot be changed or removed
#   TC-F03-S01-039 (non-local half) a non-local start serves no local operator hook and creates no operator
#
# Every statement that could change data runs in a transaction that is rolled back. Separate API
# processes are started from the built jar on a free port with jobs and outbox delivery off, so
# they never act on the running product's data; temporary roles are dropped on exit.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool java node curl jq
require_product_up
require_database
. "$SCRIPTS/lib/common.sh"
load_settings
resolve_java_home || environment_fault "JDK 25 is required to start a separate API process"
JAR="$ROOT/apps/api/target/salon-api.jar"
[ -f "$JAR" ] || environment_fault "built API jar unavailable at $JAR"
PLATFORM_USER="${SALON_DB_PLATFORM_USER:-salon_platform}"
PLATFORM_PASSWORD="${SALON_DB_PLATFORM_PASSWORD:-salon-platform-local-only}"
BYPASS_ROLE="qa_f03_bypass_$$"
WORK="$(mktemp -d)"
PID=""
restore() {
  if [ -n "$PID" ]; then kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; fi
  printf 'DROP ROLE IF EXISTS %s;\n' "$BYPASS_ROLE" | owner_psql >/dev/null
  rm -rf "$WORK"
}
trap restore EXIT

platform_psql() {
  docker exec -i -e PGPASSWORD="$PLATFORM_PASSWORD" "$POSTGRES_CONTAINER" psql -h localhost -U "$PLATFORM_USER" -d "$DB_NAME" -X -At -q 2>&1
}
platform_tx() { printf 'begin;\n%s\n;\nrollback;\n' "$1" | platform_psql; }
denied() { # denied <label> <output>
  case "$2" in *"permission denied"*|*"insufficient_privilege"*|*"must be owner"*) right "$1 refused";; *) wrong "$1 was not refused: $(printf '%s' "$2" | head -n 2)";; esac
}
random_key() { node -e 'process.stdout.write(require("crypto").randomBytes(32).toString("hex"))'; }
free_port() { node -e 'const n=require("net");const s=n.createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()});'; }
PLATFORM_TABLES="operator_user operator_session operator_refresh_history operator_mfa_challenge operator_mfa_step operator_rate_bucket plan provisioning_receipt platform_audit_entry subscription invoice_number invoice charge_attempt invoice_payment provider_event billing_reminder salon_lifecycle_event salon_export salon_export_part"
PLATFORM_FUNCTIONS="$(printf "SELECT p.oid::regprocedure FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public' AND has_function_privilege('%s', p.oid, 'EXECUTE') AND p.proname IN ('identity_bootstrap_owner','branding_bootstrap_brand','identity_active_staff_count','outbox_enqueue_for_salon','identity_revoke_closed_salon_sessions','outbox_cancel_closed_salon','identity_erase_salon_rows','branding_erase_salon_rows','audit_erase_salon_rows','outbox_erase_salon_rows');\n" "$PLATFORM_USER" | owner_psql)"

begin_case TC-F03-S01-027 "Tenant and platform database roles cannot cross the platform boundary"
load_salons
ALPHA="$(salon_value alpha ID)"
FIXED_BEFORE="$(printf "SELECT string_agg(id::text||':'||slug||':'||status, ',' ORDER BY slug) FROM tenant WHERE slug IN ('alpha','beta','gamma');\n" | owner_psql)"
STAFF_BEFORE="$(printf "SELECT count(*) FROM identity_staff WHERE tenant_id = '%s';\n" "$ALPHA" | owner_psql)"
[ "${STAFF_BEFORE:-0}" -gt 0 ] || environment_fault "alpha has no staff rows to protect; seed the F02 fixtures first"
for table in $PLATFORM_TABLES; do
  denied "tenant role reads $table" "$(as_salon "$ALPHA" "select count(*) from $table")"
  denied "tenant role deletes from $table" "$(as_salon "$ALPHA" "delete from $table")"
done
[ -n "$PLATFORM_FUNCTIONS" ] || wrong "no platform bootstrap or eraser function was found executable by $PLATFORM_USER"
while IFS= read -r fn; do
  [ -n "$fn" ] || continue
  expect_equal "tenant role cannot execute $fn" f "$(printf "SELECT has_function_privilege('%s', '%s'::regprocedure, 'EXECUTE');\n" "$DB_APP_USER" "$fn" | owner_psql)"
  expect_equal "PUBLIC cannot execute $fn" 0 "$(printf "SELECT count(*) FROM pg_proc p, aclexplode(coalesce(p.proacl, acldefault('f', p.proowner))) a WHERE p.oid = '%s'::regprocedure AND a.grantee = 0 AND a.privilege_type = 'EXECUTE';\n" "$fn" | owner_psql)"
done <<<"$PLATFORM_FUNCTIONS"
denied "tenant role runs identity_bootstrap_owner" "$(as_salon "$ALPHA" "select identity_bootstrap_owner(gen_random_uuid(), gen_random_uuid(), 'QA', 'qa@example.invalid', 'x', now())")"
for table in identity_staff identity_session identity_customer salon_brand audit_entry outbox_message; do
  denied "platform role reads $table" "$(platform_tx "select count(*) from $table")"
done
denied "platform role writes salon_brand" "$(platform_tx "update salon_brand set updated_at = updated_at")"
denied "platform role writes identity_staff" "$(platform_tx "update identity_staff set updated_at = updated_at")"
ATTRS="$(printf "SELECT rolsuper::text||rolbypassrls::text||rolcreaterole::text FROM pg_roles WHERE rolname = '%s';\n" "$PLATFORM_USER" | owner_psql)"
expect_equal "platform role is not superuser, has no BYPASSRLS and cannot create roles" falsefalsefalse "$ATTRS"
expect_equal "platform role owns no table" 0 "$(printf "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner = '%s';\n" "$PLATFORM_USER" | owner_psql)"
expect_equal "every tenant table still forces RLS" 0 "$(printf "SELECT count(*) FROM pg_class c JOIN information_schema.columns k ON k.table_name = c.relname AND k.column_name = 'tenant_id' AND k.table_schema = 'public' WHERE c.relkind = 'r' AND NOT (c.relrowsecurity AND c.relforcerowsecurity);\n" | owner_psql)"
expect_equal "fixed salons intact" "$FIXED_BEFORE" "$(printf "SELECT string_agg(id::text||':'||slug||':'||status, ',' ORDER BY slug) FROM tenant WHERE slug IN ('alpha','beta','gamma');\n" | owner_psql)"
expect_equal "alpha staff rows intact" "$STAFF_BEFORE" "$(printf "SELECT count(*) FROM identity_staff WHERE tenant_id = '%s';\n" "$ALPHA" | owner_psql)"

begin_case TC-F03-S01-029 "Platform audit rows cannot be changed or removed (database half)"
[ "$(printf 'SELECT count(*) FROM platform_audit_entry;\n' | owner_psql)" -gt 0 ] || environment_fault "no platform audit rows yet; run after operator activity (the API half of TC-F03-S01-029)"
denied "platform role updates platform audit" "$(platform_tx "update platform_audit_entry set action = action")"
denied "platform role deletes platform audit" "$(platform_tx "delete from platform_audit_entry")"
OWNER_UPDATE="$(owner_tx "update platform_audit_entry set action = action")"
case "$OWNER_UPDATE" in *ERROR*) right "even the owning role cannot update an audit row";; *) wrong "the owning role updated an audit row: $OWNER_UPDATE";; esac

# Separate API processes: never the running product's port, jobs and outbox delivery off.
start_api() { # start_api <log> -- environment assignments follow as name=value
  local log="$1"; shift
  PORT="$(free_port)"
  env "$@" "$RESOLVED_JAVA_HOME/bin/java" -jar "$JAR" --spring.profiles.active=qa-nonlocal --server.port="$PORT" \
    --spring.flyway.enabled=false --salon.jobs.enabled=false --salon.outbox.enabled=false >"$log" 2>&1 &
  PID=$!
}
refuses() { # refuses <label> <log>
  local waited=0
  while kill -0 "$PID" 2>/dev/null && [ "$waited" -lt 90 ]; do
    [ "$(http_code "http://localhost:$PORT/actuator/health" 2)" = 200 ] && break
    sleep 1; waited=$((waited + 1))
  done
  if kill -0 "$PID" 2>/dev/null; then
    wrong "$1: the API kept running (health $(http_code "http://localhost:$PORT/actuator/health" 2))"
    kill "$PID"; wait "$PID" 2>/dev/null
  else
    wait "$PID" 2>/dev/null; right "$1: startup refused (exit $?)"
  fi
  PID=""
}
# The retained local identity keys (values never printed), as with_identity_keys reads them, but in
# this shell so the started process and its pid stay here.
IDENTITY_KEYS=()
IDENTITY_KEY_FILE="${SALON_IDENTITY_LOCAL_KEY_FILE:-$PRODUCT_ROOT/.local/identity-keys.properties}"
[ -f "$IDENTITY_KEY_FILE" ] || environment_fault "retained identity key file is absent"
while IFS='=' read -r key value; do
  case "$key" in signing) IDENTITY_KEYS+=(SALON_IDENTITY_SIGNING_KEY="$value");; challenge) IDENTITY_KEYS+=(SALON_IDENTITY_CHALLENGE_KEY="$value");;
    encryption) IDENTITY_KEYS+=(SALON_IDENTITY_ENCRYPTION_KEY="$value");; proxy) IDENTITY_KEYS+=(SALON_IDENTITY_PROXY_KEY="$value");; esac
done <"$IDENTITY_KEY_FILE"
[ "${#IDENTITY_KEYS[@]}" = 4 ] || environment_fault "all four retained identity keys are required"
KEYS=("${IDENTITY_KEYS[@]}" SALON_OPERATOR_SIGNING_KEY="$(random_key)" SALON_OPERATOR_CHALLENGE_KEY="$(random_key)" SALON_OPERATOR_ENCRYPTION_KEY="$(random_key)"
  SALON_OPERATOR_PROXY_KEY="$(random_key)" SALON_OPERATOR_FINGERPRINT_KEY="$(random_key)" SALON_EXPORT_KEY="$(random_key)")

begin_case TC-F03-S01-028 "The API refuses to start with an owning, RLS-bypassing or missing platform role"
printf "CREATE ROLE %s LOGIN PASSWORD 'qa-bypass-local-only' BYPASSRLS;\n" "$BYPASS_ROLE" | owner_psql >/dev/null
start_api "$WORK/owner.log" "${KEYS[@]}" SALON_DB_PLATFORM_USER="$DB_USER" SALON_DB_PLATFORM_PASSWORD="${SALON_DB_PASSWORD:-}"
refuses "platform connection as the table owner" "$WORK/owner.log"
start_api "$WORK/bypass.log" "${KEYS[@]}" SALON_DB_PLATFORM_USER="$BYPASS_ROLE" SALON_DB_PLATFORM_PASSWORD=qa-bypass-local-only
refuses "platform connection with BYPASSRLS" "$WORK/bypass.log"
start_api "$WORK/missing.log" "${KEYS[@]}" SALON_DB_PLATFORM_USER= SALON_DB_PLATFORM_PASSWORD=
refuses "platform connection not configured" "$WORK/missing.log"

begin_case TC-F03-S01-039 "A non-local start serves no local operator hook and creates no operator (non-local half)"
OPERATORS_BEFORE="$(printf 'SELECT count(*) FROM operator_user;\n' | owner_psql)"
start_api "$WORK/configured.log" "${KEYS[@]}" SALON_DB_PLATFORM_USER="$PLATFORM_USER" SALON_DB_PLATFORM_PASSWORD="$PLATFORM_PASSWORD"
if wait_for_code "http://localhost:$PORT/actuator/health" 200 90; then
  right "the correctly configured start is healthy (TC-F03-S01-028 restored start)"
  for hook in "POST /local/operator/seed" "DELETE /local/operator/fixtures" "PUT /local/operator/bootstrap-failure" "POST /local/operator/reset" \
      "POST /local/operator/billing/run" "POST /local/operator/exports/run" "GET /local/operator/emails"; do
    set -- $hook
    code="$(curl -s -o "$WORK/hook.json" -w '%{http_code}' --max-time 10 -X "$1" -H "Host: console.${SALON_PLATFORM_DOMAIN}" -H 'Content-Type: application/json' -d '{}' "http://localhost:$PORT$2")"
    expect_equal "non-local $hook" 404 "$code"
  done
  expect_equal "no operator created by a start" "$OPERATORS_BEFORE" "$(printf 'SELECT count(*) FROM operator_user;\n' | owner_psql)"
else
  wrong "the configured non-local start never became healthy: $(tail -n 5 "$WORK/configured.log")"
fi
kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null; PID=""
finish
