#!/usr/bin/env bash
# F01-S01, AC-2: what the API does while PostgreSQL is stopped, and when it comes back.
#
# Cases: TC-F01-S01-024 to TC-F01-S01-027. Expected results come from AC-2: "With PostgreSQL
# stopped it returns 503 within 5 seconds and does not hang." Recovery without a restart is the
# resilience rule in docs/qa/test-types.md ("recovery needs no manual repair").
#
# PostgreSQL is stopped and started through the developer's script. It is started again when the
# check ends, however it ends.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool curl docker lsof jq

WORK="$(mktemp -d)"
LIMIT_SECONDS=5
HEALTH="$API_URL/actuator/health"
require_product_up

restore() {
  "$SCRIPTS/local-service.sh" start postgres >/dev/null 2>&1
  wait_for_code "$HEALTH" 200 90 || printf 'ENVIRONMENT: health did not return to 200 after PostgreSQL was started again\n' >&2
}
trap restore EXIT

within_limit() { awk -v t="$1" -v limit="$LIMIT_SECONDS" 'BEGIN { exit !(t < limit) }'; }

API_PID_BEFORE="$(listener_pid "$API_PORT")"
"$SCRIPTS/local-service.sh" stop postgres >"$WORK/stop.log" 2>&1 \
  || environment_fault "could not stop PostgreSQL: $(cat "$WORK/stop.log")"
port_open "$POSTGRES_PORT" && environment_fault "PostgreSQL was stopped but port $POSTGRES_PORT still answers"
[ -z "$(docker ps --quiet --filter "name=^${POSTGRES_CONTAINER}$")" ] \
  || environment_fault "PostgreSQL was stopped but its container is still running"
note "PostgreSQL is stopped"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-024 "With PostgreSQL stopped, health answers 503 within 5 seconds with the database component DOWN"
# The first request after the stop, then again: once the pool knows, and once it has had time to forget.
for attempt in first second third; do
  read -r status seconds content_type <<<"$(timed_get "$HEALTH" "$WORK/health-$attempt.json" 20)"
  expect_equal "$attempt request: status" 503 "$status"
  if within_limit "$seconds"; then right "$attempt request: answered in $seconds s"; else wrong "$attempt request: took $seconds s, the limit is $LIMIT_SECONDS s"; fi
  expect_equal "$attempt request: overall status in the body" DOWN "$(jq -r '.status' "$WORK/health-$attempt.json" 2>/dev/null)"
  expect_equal "$attempt request: database component in the body" DOWN "$(jq -r '.components.db.status' "$WORK/health-$attempt.json" 2>/dev/null)"
  [ "$attempt" = second ] && sleep 3
done
cp "$WORK/health-first.json" "$REPORT_DIR/health-database-down.json" 2>/dev/null

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-025 "With PostgreSQL stopped, fifty health requests at once each answer 503 within 5 seconds"
REQUESTS=50
for index in $(seq 1 "$REQUESTS"); do
  ( timed_get "$HEALTH" /dev/null 30 >"$WORK/flood-$index.txt" ) &
done
wait
answered=0; slow=0; not_503=0; slowest=0
for index in $(seq 1 "$REQUESTS"); do
  read -r status seconds _ <"$WORK/flood-$index.txt" || true
  [ -n "${status:-}" ] && answered=$((answered + 1))
  [ "${status:-}" = 503 ] || not_503=$((not_503 + 1))
  within_limit "${seconds:-99}" || slow=$((slow + 1))
  slowest="$(awk -v a="$slowest" -v b="${seconds:-99}" 'BEGIN { print (b > a) ? b : a }')"
done
expect_equal "requests that got an answer" "$REQUESTS" "$answered"
expect_equal "requests answered with anything but 503" 0 "$not_503"
expect_equal "requests that took $LIMIT_SECONDS s or longer" 0 "$slow"
note "the slowest of $REQUESTS took $slowest s"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-026 "With PostgreSQL stopped, no answer shows how the product is built, and the rest of the API still answers"
BODY="$(cat "$WORK/health-first.json")"
[ -n "$BODY" ] && right "there is a 503 body to inspect" || wrong "the 503 had no body, so there is nothing to inspect"
expect_no_match "the 503 body" "$INTERNALS_RE" "$BODY"
read -r status seconds content_type <<<"$(timed_get "$API_URL/api/v1/does-not-exist" "$WORK/unknown.json" 20)"
expect_equal "an unknown path: status" 404 "$status"
expect_matches "an unknown path: content type" '^application/problem\+json' "$content_type"
expect_equal "an unknown path: code" not_found "$(jq -r '.code' "$WORK/unknown.json" 2>/dev/null)"
if within_limit "$seconds"; then right "an unknown path: answered in $seconds s"; else wrong "an unknown path: took $seconds s"; fi
# meta needs no database. It may serve or say the service is unavailable; it may not fail or hang.
read -r status seconds content_type <<<"$(timed_get "$API_URL/api/v1/meta" "$WORK/meta.json" 20)"
case "$status" in
  200) right "meta: still answers 200" ;;
  503) expect_matches "meta: a 503 is problem details" '^application/problem\+json' "$content_type"
       expect_no_match "meta: the 503 body" "$INTERNALS_RE" "$(cat "$WORK/meta.json")" ;;
  *)   wrong "meta: answered $status; expected 200, or 503 as problem details" ;;
esac
if within_limit "$seconds"; then right "meta: answered in $seconds s"; else wrong "meta: took $seconds s"; fi

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-027 "When PostgreSQL comes back, health returns to 200 UP without the API being restarted"
"$SCRIPTS/local-service.sh" start postgres >"$WORK/start.log" 2>&1
expect_equal "exit code of 'start postgres'" 0 "$?"
RECOVERY_STARTED=$(date +%s)
if wait_for_code "$HEALTH" 200 60; then
  right "health answered 200 again after $(( $(date +%s) - RECOVERY_STARTED )) s"
else
  wrong "health did not return to 200 within 60 s of PostgreSQL starting (it answers $(http_code "$HEALTH"))"
fi
read -r status seconds content_type <<<"$(timed_get "$HEALTH" "$WORK/health-after.json" 20)"
expect_equal "overall status in the body" UP "$(jq -r '.status' "$WORK/health-after.json" 2>/dev/null)"
expect_equal "database component in the body" UP "$(jq -r '.components.db.status' "$WORK/health-after.json" 2>/dev/null)"
expect_equal "the API's process, which was never restarted" "$API_PID_BEFORE" "$(listener_pid "$API_PORT")"

finish
