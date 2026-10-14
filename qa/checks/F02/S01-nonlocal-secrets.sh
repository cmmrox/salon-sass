#!/usr/bin/env bash
# TC-F02-S01-030: local machine, separate API process. Never a remote environment.
# Uses built artifact only; no Maven/package build, no replacement of the running product.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool java node curl jq
require_product_up
require_database
WORK="$(mktemp -d)"
PID=""
restore() {
  if [ -n "$PID" ]; then kill "$PID" 2>/dev/null || true; wait "$PID" 2>/dev/null || true; fi
  rm -rf "$WORK"
}
trap restore EXIT
# Existing runtime helper selects JDK25 and local DB settings; nothing here installs software.
SCRIPTS="${QA_PRODUCT_ROOT:-$REPO_ROOT}/infra/scripts"
. "$SCRIPTS/lib/common.sh"
load_settings
resolve_java_home || environment_fault "JDK25 required for separate nonlocal-profile observation"
JAR="$ROOT/apps/api/target/salon-api.jar"
[ -f "$JAR" ] || environment_fault "built API jar unavailable; lead must prepare final runtime artifact"
PORT="$(node -e 'const n=require("net");const s=n.createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()});')"
begin_case TC-F02-S01-030 "Nonlocal startup requires configured independent keys and exposes no local identity hooks"
SALON_IDENTITY_SIGNING_KEY= SALON_IDENTITY_CHALLENGE_KEY= SALON_IDENTITY_ENCRYPTION_KEY= \
  "$RESOLVED_JAVA_HOME/bin/java" -jar "$JAR" --spring.profiles.active=qa-nonlocal --server.port="$PORT" --spring.flyway.enabled=false >"$WORK/missing.log" 2>&1 &
PID=$!
for n in $(seq 1 100); do kill -0 "$PID" 2>/dev/null || break; sleep 0.1; done
if kill -0 "$PID" 2>/dev/null; then wrong "unconfigured nonlocal startup remained running"; kill "$PID"; fi
wait "$PID" 2>/dev/null; EXIT_CODE=$?; PID=""
[ "$EXIT_CODE" -ne 0 ] && right "unconfigured startup exits nonzero" || wrong "unconfigured startup exits zero"
if grep -q 'SALON_IDENTITY_.*_KEY' "$WORK/missing.log"; then right "refusal names the required identity setting"; else wrong "startup failure did not identify missing identity keys"; fi
# Throwaway independent keys: values are never echoed or copied into report evidence.
export SALON_IDENTITY_SIGNING_KEY="$(node -e 'process.stdout.write(require("crypto").randomBytes(32).toString("hex"))')"
export SALON_IDENTITY_CHALLENGE_KEY="$(node -e 'process.stdout.write(require("crypto").randomBytes(32).toString("hex"))')"
export SALON_IDENTITY_ENCRYPTION_KEY="$(node -e 'process.stdout.write(require("crypto").randomBytes(32).toString("hex"))')"
export SALON_IDENTITY_PROXY_KEY="$(node -e 'process.stdout.write(require("crypto").randomBytes(32).toString("hex"))')"
"$RESOLVED_JAVA_HOME/bin/java" -jar "$JAR" --spring.profiles.active=qa-nonlocal --server.port="$PORT" --spring.flyway.enabled=false >"$WORK/configured.log" 2>&1 &
PID=$!
wait_for_code "http://localhost:$PORT/actuator/health" 200 40 || environment_fault "configured separate API did not become healthy"
load_salons
for endpoint in /local/identity /local/identity/seed; do
  read -r status seconds type <<<"$(timed_get "http://localhost:$PORT$endpoint" "$WORK/response.json" 10)"
  expect_equal "nonlocal$endpoint" 404 "$status"
  expect_equal "stable refusal$endpoint" not_found "$(jq -r '.code' "$WORK/response.json")"
done
expect_equal "nonlocal fixture POST" 404 "$(curl -s --max-time 10 -o "$WORK/response.json" -w '%{http_code}' -H "Host: $ALPHA_HOST" -H 'Content-Type: application/json' -d '{"accounts":[]}' "http://localhost:$PORT/local/identity/seed")"
expect_equal "nonlocal reset DELETE" 404 "$(curl -s --max-time 10 -o "$WORK/response.json" -w '%{http_code}' -X DELETE -H "Host: $ALPHA_HOST" "http://localhost:$PORT/local/identity")"
kill "$PID"; wait "$PID" 2>/dev/null || true; PID=""
finish
