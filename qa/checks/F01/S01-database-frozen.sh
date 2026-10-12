#!/usr/bin/env bash
# F01-S01, AC-2: what the API does while PostgreSQL accepts connections and answers nothing, and
# when it answers again (BUG-F01-S01-003).
#
# Case: TC-F01-S01-061. AC-2 says of a stopped PostgreSQL that health "returns 503 within 5 seconds
# and does not hang". A database that has gone silent is down in every way that matters to a
# caller, and the same is expected of it; recovery needs no restart.
#
# PostgreSQL is frozen and thawed through the developer's test hook,
# `infra/scripts/local-service.sh pause|resume postgres`. It is thawed when the check ends,
# however it ends.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool curl docker lsof jq

WORK="$(mktemp -d)"
LIMIT_SECONDS=5
HEALTH="$API_URL/actuator/health"
require_product_up

restore() {
  "$SCRIPTS/local-service.sh" resume postgres >/dev/null 2>&1
  "$SCRIPTS/local-service.sh" start postgres >/dev/null 2>&1
  wait_for_code "$HEALTH" 200 90 || printf 'ENVIRONMENT: health did not return to 200 after PostgreSQL was thawed\n' >&2
  rm -rf "$WORK"
}
trap restore EXIT

within_limit() { awk -v t="$1" -v limit="$LIMIT_SECONDS" 'BEGIN { exit !(t < limit) }'; }

# expect_quick_503 <label>: one health request, while frozen.
expect_quick_503() {
  local status seconds type
  read -r status seconds type <<<"$(timed_get "$HEALTH" "$WORK/health.json" 60)"
  expect_equal "$1: status" 503 "$status"
  if within_limit "${seconds:-99}"; then right "$1: answered in $seconds s"; else wrong "$1: took ${seconds:-more than 60} s, the limit is $LIMIT_SECONDS s"; fi
  expect_equal "$1: database component" DOWN "$(jq -r '.components.db.status' "$WORK/health.json" 2>/dev/null)"
}

begin_case TC-F01-S01-061 "With PostgreSQL frozen, health answers 503 within 5 seconds every time, and returns to 200 by itself when PostgreSQL answers again"

# The hang this case guards against comes from a request handed a connection the pool used a moment
# before (the pool skips its own aliveness test for a connection used in the last half second). So
# health is asked every tenth of a second, without a pause, across the moment PostgreSQL freezes:
# some requests are bound to land on a connection that was alive an instant earlier.
for _ in 1 2 3 4 5; do http_code "$HEALTH" >/dev/null; done
expect_equal "before: GET /actuator/health" 200 "$(http_code "$HEALTH")"
API_PID_BEFORE="$(listener_pid "$API_PORT")"

STREAM="$WORK/stream"; mkdir -p "$STREAM"
( index=0; end=$(( $(date +%s) + 12 ))
  while [ "$(date +%s)" -lt "$end" ]; do
    index=$((index + 1))
    ( timed_get "$HEALTH" /dev/null 60 >"$STREAM/$index.txt" ) &
    sleep 0.1
  done
  wait ) &
STREAM_PID=$!
sleep 3

PAUSE="$("$SCRIPTS/local-service.sh" pause postgres 2>&1)"; PAUSE_EXIT=$?
expect_equal "exit code of 'pause postgres'" 0 "$PAUSE_EXIT"
expect_equal "the container's state" paused "$(docker inspect --format '{{.State.Status}}' "$POSTGRES_CONTAINER" 2>/dev/null)"
if port_open "$POSTGRES_PORT"; then right "PostgreSQL's port still accepts connections: it is frozen, not stopped"; else wrong "PostgreSQL's port is closed, so this is the stopped case again and proves nothing new"; fi

wait "$STREAM_PID"
sent=0; slow=0; other=0; down=0; slowest=0
for file in "$STREAM"/*.txt; do
  sent=$((sent + 1))
  read -r status seconds _ <"$file" || true
  case "${status:-}" in 200) ;; 503) down=$((down + 1)) ;; *) other=$((other + 1)) ;; esac
  within_limit "${seconds:-99}" || slow=$((slow + 1))
  slowest="$(awk -v a="$slowest" -v b="${seconds:-99}" 'BEGIN { print (b > a) ? b : a }')"
done
[ "$sent" -ge 60 ] && right "$sent health requests were sent across the freeze" || wrong "only $sent health requests were sent, so the moment of the freeze was not covered"
[ "$down" -ge 1 ] && right "$down of them answered 503: the freeze was seen" || wrong "none answered 503, so the freeze was never seen and this proves nothing"
expect_equal "requests across the freeze answered with anything but 200 or 503 (000 is no answer within 60 s)" 0 "$other"
expect_equal "requests across the freeze that took $LIMIT_SECONDS s or longer" 0 "$slow"
note "the slowest request across the freeze took $slowest s"

AGAIN="$("$SCRIPTS/local-service.sh" pause postgres 2>&1)"; AGAIN_EXIT=$?
expect_equal "exit code of a second 'pause postgres'" 0 "$AGAIN_EXIT"

expect_quick_503 "a request once the freeze has settled"
cp "$WORK/health.json" "$REPORT_DIR/health-database-frozen.json" 2>/dev/null
expect_no_match "the 503 body" "$INTERNALS_RE" "$(cat "$WORK/health.json")"

REQUESTS=50
for index in $(seq 1 "$REQUESTS"); do ( timed_get "$HEALTH" /dev/null 60 >"$WORK/flood-$index.txt" ) & done
wait
not_503=0; slow=0; slowest=0
for index in $(seq 1 "$REQUESTS"); do
  read -r status seconds _ <"$WORK/flood-$index.txt" || true
  [ "${status:-}" = 503 ] || not_503=$((not_503 + 1))
  within_limit "${seconds:-99}" || slow=$((slow + 1))
  slowest="$(awk -v a="$slowest" -v b="${seconds:-99}" 'BEGIN { print (b > a) ? b : a }')"
done
expect_equal "fifty at once: answers that were not 503" 0 "$not_503"
expect_equal "fifty at once: answers that took $LIMIT_SECONDS s or longer" 0 "$slow"
note "the slowest of $REQUESTS took $slowest s"

# Past the driver's 30 s socket limit: health keeps answering quickly the whole time.
for at in 10 20 35; do
  sleep 10 2>/dev/null; [ "$at" = 35 ] && sleep 5
  expect_quick_503 "about $at s into the freeze"
done
read -r status seconds _ <<<"$(timed_get "$API_URL/api/v1/meta" /dev/null 10)"
expect_equal "meta, which needs no database, while frozen" 200 "$status"

RESUME="$("$SCRIPTS/local-service.sh" resume postgres 2>&1)"; RESUME_EXIT=$?
expect_equal "exit code of 'resume postgres'" 0 "$RESUME_EXIT"
STARTED=$(date +%s)
if wait_for_code "$HEALTH" 200 30; then right "health answered 200 again $(( $(date +%s) - STARTED )) s after the thaw"; else wrong "health did not return to 200 within 30 s of the thaw (it answers $(http_code "$HEALTH"))"; fi
expect_equal "the API's process, never restarted" "$API_PID_BEFORE" "$(listener_pid "$API_PORT")"
NOT_PAUSED="$("$SCRIPTS/local-service.sh" resume postgres 2>&1)"; NOT_PAUSED_EXIT=$?
expect_equal "exit code of 'resume postgres' when it is not paused" 0 "$NOT_PAUSED_EXIT"

# The hook refuses to freeze a PostgreSQL that is not running.
"$SCRIPTS/local-service.sh" stop postgres >/dev/null 2>&1
STOPPED="$("$SCRIPTS/local-service.sh" pause postgres 2>&1)"; STOPPED_EXIT=$?
expect_not_equal "exit code of 'pause postgres' while it is stopped" 0 "$STOPPED_EXIT"
"$SCRIPTS/local-service.sh" start postgres >/dev/null 2>&1
wait_for_code "$HEALTH" 200 60 && right "health is 200 after PostgreSQL was started again" || wrong "health did not return to 200 after PostgreSQL was started again"

finish
