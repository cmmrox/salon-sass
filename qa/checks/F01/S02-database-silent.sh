#!/usr/bin/env bash
# F01-S02, AC-10 (NFR-15, CR-003): what a salon's request is answered while PostgreSQL accepts
# connections and says nothing, when it speaks again, and while it is stopped.
#
# Cases: TC-F01-S02-050 to TC-F01-S02-053. "With PostgreSQL frozen, GET /api/v1/salon on
# alpha.localhost answers 503 service_unavailable within 5 seconds, both for requests already in
# flight at the moment of the freeze and for requests made during it, and never hangs. Within 5
# seconds of resume postgres it answers 200 again without the API being restarted. With PostgreSQL
# stopped it answers 503 within 5 seconds."
#
# How requests are known to be in flight at the freeze. A stream of callers asks all three salons
# without a pause, each asking again the moment it is answered. Just before the freeze the brand
# table is locked from another session, so every request then in progress stops in the middle of
# its query, holding its connection or waiting for one. PostgreSQL is frozen while they wait. A
# request that began before the freeze was asked for and was still unanswered after it was in
# place was in flight when it happened, whichever instant that was. (The lock is in a transaction
# of the owning role that is never committed, and ends when PostgreSQL is thawed.)
#
# The freeze, the thaw, the stop and the start go through the developer's script,
# infra/scripts/local-service.sh. PostgreSQL is thawed and started again when the check ends,
# however it ends.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool curl docker lsof jq node

WORK="$(mktemp -d)"
LIMIT_MS=5000
CALLERS=24
STREAM="$WORK/stream.jsonl"
STOP="$WORK/stop"
STREAM_PID=""
require_product_up
require_database
load_salons

restore() {
  touch "$STOP"
  "$SCRIPTS/local-service.sh" resume postgres >/dev/null 2>&1
  "$SCRIPTS/local-service.sh" start postgres >/dev/null 2>&1
  [ -n "$STREAM_PID" ] && wait "$STREAM_PID" 2>/dev/null
  wait_for_code "$(salon_api alpha)" 200 90 || printf 'ENVIRONMENT: %s did not return to 200 after PostgreSQL was restored\n' "$(salon_api alpha)" >&2
  cp "$STREAM" "$REPORT_DIR/database-silent-stream.jsonl" 2>/dev/null
  rm -rf "$WORK"
}
trap restore EXIT

# Every salon answers before anything is taken away: a 503 below is then the database's doing.
URLS=()
for slug in $SALON_SLUGS; do
  URLS+=("$(salon_api "$slug")")
  expect_equal_quietly() { [ "$1" = "$2" ] || environment_fault "before the check, $3 answered '$2', expected '$1'"; }
  expect_equal_quietly "$(salon_value "$slug" NAME)" "$(curl -s --max-time 10 "$(salon_api "$slug")" | jq -r '.name' 2>/dev/null)" "$(salon_api "$slug")"
done
API_PID_BEFORE="$(listener_pid "$API_PORT")"
[ -n "$API_PID_BEFORE" ] || environment_fault "nothing listens on the API's port $API_PORT"

# The salons, for jq: which name each address must answer with.
EXPECTED="$(for slug in $SALON_SLUGS; do jq -n --arg url "$(salon_api "$slug")" --arg slug "$slug" --arg name "$(salon_value "$slug" NAME)" --arg colour "$(salon_value "$slug" COLOUR)" '{($url): {slug: $slug, name: $name, brandColour: $colour}}'; done | jq -s 'add')"

# stream_count <jq filter over the array of requests>, with the marks of time as $variables.
MARKS=()
mark() { MARKS+=(--argjson "$1" "$2"); }
stream_query() { jq -s -r "${MARKS[@]}" --argjson expected "$EXPECTED" --argjson limit "$LIMIT_MS" "$1" "$STREAM"; }
# A request "is this salon's brand" when its body is exactly the three fields its address expects.
JQ_DEFS='def own: .status == 200 and ((.body | try fromjson catch null) == $expected[.url]);
def refused: .status == 503 and ((.body | try fromjson catch null | .code?) == "service_unavailable");
def quick: .ms < $limit;'

frozen() { [ "$(docker inspect --format '{{.State.Status}}' "$POSTGRES_CONTAINER" 2>/dev/null)" = paused ]; }

freeze() {
  local out; out="$("$SCRIPTS/local-service.sh" pause postgres 2>&1)" || environment_fault "could not freeze PostgreSQL: $out"
  frozen || environment_fault "PostgreSQL was frozen by the script but its container is '$(docker inspect --format '{{.State.Status}}' "$POSTGRES_CONTAINER" 2>/dev/null)'"
  port_open "$POSTGRES_PORT" || environment_fault "PostgreSQL's port is closed while frozen: this is the stopped case, not the silent one"
}
thaw() {
  local out; out="$("$SCRIPTS/local-service.sh" resume postgres 2>&1)" || environment_fault "could not thaw PostgreSQL: $out"
  frozen && environment_fault "PostgreSQL is still frozen after 'resume postgres'"
}

# wait_for_stream <n>: until the stream has recorded that many more answers.
wait_for_stream() {
  local target=$(( $(wc -l <"$STREAM" 2>/dev/null || echo 0) + $1 )) waited=0
  while [ "$(wc -l <"$STREAM" 2>/dev/null || echo 0)" -lt "$target" ]; do
    sleep 0.2; waited=$((waited + 1))
    [ "$waited" -lt 150 ] || environment_fault "the stream of requests recorded no more answers within 30 s"
  done
}

: >"$STREAM"
# Each caller waits 25 ms between an answer and its next request: several hundred requests a second
# before the freeze, and a new request from every caller within 25 ms of the last at any moment.
node "$QA_ROOT/shared/request-stream.mjs" --out "$STREAM" --stop "$STOP" --callers "$CALLERS" --pause-ms 25 --limit-ms 60000 "${URLS[@]}" &
STREAM_PID=$!
wait_for_stream 600
mark warm "$(now_ms)"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-050 "Requests in flight at the moment PostgreSQL freezes are each answered 503 service_unavailable within 5 seconds"
# Stop every request in the middle of its query, then freeze.
( printf "begin;\nlock table salon_brand in access exclusive mode;\nselect 'locked';\n"; sleep 4 ) \
  | docker exec -i "$POSTGRES_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -X -At -q >"$WORK/lock.out" 2>&1 &
LOCK_PID=$!
waited=0
until grep -q '^locked$' "$WORK/lock.out" 2>/dev/null; do
  sleep 0.05; waited=$((waited + 1))
  [ "$waited" -lt 100 ] || environment_fault "could not lock salon_brand to hold requests in flight: $(cat "$WORK/lock.out")"
done
mark locked "$(now_ms)"
sleep 0.3
mark freeze_asked "$(now_ms)"
freeze
mark frozen "$(now_ms)"
sleep 7
mark thaw_asked "$(now_ms)"
thaw
mark thawed "$(now_ms)"
wait "$LOCK_PID" 2>/dev/null
expect_equal "locks still held on salon_brand by the session that held the requests (it ended with the thaw)" 0 "$(printf "select count(*) from pg_locks where relation = 'public.salon_brand'::regclass and mode = 'AccessExclusiveLock';\n" | owner_psql)"
sleep 8
mark settled "$(now_ms)"

IN_FLIGHT="$JQ_DEFS map(select(.start < \$freeze_asked and .end > \$frozen))"
expect_equal "requests that began before the freeze was asked for and were unanswered once it was in place: at least one" true "$(stream_query "$IN_FLIGHT | length >= 1")"
note "$(stream_query "$IN_FLIGHT | length") requests were in flight at the freeze; they were answered after $(stream_query "$IN_FLIGHT | map(.ms) | \"\(min) to \(max)\"") ms"
expect_equal "of those, answered with anything but 503 service_unavailable" 0 "$(stream_query "$IN_FLIGHT | map(select(refused | not)) | length")"
expect_equal "of those, answered after $LIMIT_MS ms or more" 0 "$(stream_query "$IN_FLIGHT | map(select(quick | not)) | length")"
expect_equal "of those, on each salon's hostname (every salon had a request in flight)" "$(printf '%s\n' "${URLS[@]}" | sort | tr '\n' ' ')" "$(stream_query "$IN_FLIGHT | map(.url) | unique | sort | .[]" | tr '\n' ' ')"
expect_equal "of those, the slowest was answered before the thaw (the answer came from the limit, not from the database coming back)" true "$(stream_query "$IN_FLIGHT | map(.end) | max < \$thaw_asked")"
stream_query "$IN_FLIGHT | .[0]" >"$REPORT_DIR/database-silent-in-flight-example.json"
IN_FLIGHT_BODY="$(stream_query "$IN_FLIGHT | .[0].body")"
expect_no_match "the body of a 503 given to a request in flight" "$INTERNALS_RE" "$IN_FLIGHT_BODY"
expect_no_match "the body of that 503, for any salon's name or id" "$ALPHA_NAME|Beta Hair|$GAMMA_NAME|$ALPHA_ID|$BETA_ID|$GAMMA_ID" "$IN_FLIGHT_BODY"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-051 "Requests made while PostgreSQL is frozen are each answered 503 within 5 seconds, for as long as it stays frozen, and nothing hangs"
# A second freeze, a long one: past the 30 seconds at which the database driver gives up on a socket.
mark second_freeze_asked "$(now_ms)"
freeze
mark second_frozen "$(now_ms)"
# While it is frozen: what one caller sees, with the headers.
read -r status seconds content_type <<<"$(timed_get "$(salon_api alpha)" "$WORK/frozen.json" 30)"
expect_equal "a request on alpha's hostname while frozen: status" 503 "$status"
expect_matches "its content type" '^application/problem\+json' "$content_type"
expect_equal "its code" service_unavailable "$(jq -r '.code' "$WORK/frozen.json" 2>/dev/null)"
expect_equal "the status in its body" 503 "$(jq -r '.status' "$WORK/frozen.json" 2>/dev/null)"
awk -v t="${seconds:-99}" 'BEGIN { exit !(t < 5) }' && right "it was answered in $seconds s" || wrong "it took ${seconds:-more than 30} s, the limit is 5 s"
expect_no_match "its body" "$INTERNALS_RE" "$(cat "$WORK/frozen.json")"
cp "$WORK/frozen.json" "$REPORT_DIR/salon-database-frozen.json" 2>/dev/null
# What needs no database still answers on a salon's hostname, and health says the database is down.
expect_equal "meta on alpha's hostname while frozen" 200 "$(http_code "$(on_hostname "$API_URL" "$ALPHA_HOST")/api/v1/meta" 10)"
expect_equal "health on alpha's hostname while frozen" 503 "$(http_code "$(on_hostname "$API_URL" "$ALPHA_HOST")/actuator/health" 10)"
sleep 36
mark second_thaw_asked "$(now_ms)"
thaw
mark second_thawed "$(now_ms)"
sleep 8
mark second_settled "$(now_ms)"
touch "$STOP"; wait "$STREAM_PID" 2>/dev/null; STREAM_PID=""

# Requests whose whole life was inside a freeze: they began after it was in place and were answered before the thaw was asked for.
DURING="$JQ_DEFS map(select((.start > \$frozen and .end < \$thaw_asked) or (.start > \$second_frozen and .end < \$second_thaw_asked)))"
expect_equal "requests made and answered while frozen: at least 300" true "$(stream_query "$DURING | length >= 300")"
note "$(stream_query "$DURING | length") requests were made and answered while PostgreSQL was frozen; the slowest took $(stream_query "$DURING | map(.ms) | max") ms"
expect_equal "of those, answered with anything but 503 service_unavailable" 0 "$(stream_query "$DURING | map(select(refused | not)) | length")"
expect_equal "of those, answered after $LIMIT_MS ms or more" 0 "$(stream_query "$DURING | map(select(quick | not)) | length")"
LATE="$JQ_DEFS map(select(.start > \$second_frozen + 31000 and .end < \$second_thaw_asked))"
expect_equal "requests made more than 31 s into the freeze: at least 20" true "$(stream_query "$LATE | length >= 20")"
expect_equal "of those, not a 503 within $LIMIT_MS ms" 0 "$(stream_query "$LATE | map(select((refused and quick) | not)) | length")"
# Nothing hung, at any time: every request of the whole stream was answered, and quickly.
expect_equal "requests of the whole stream that got no answer at all" 0 "$(stream_query "$JQ_DEFS map(select(.status == 0)) | length")"
expect_equal "requests of the whole stream answered after $LIMIT_MS ms or more" 0 "$(stream_query "$JQ_DEFS map(select(quick | not)) | length")"
expect_equal "answers of the whole stream that were neither the salon's own brand nor 503 service_unavailable" 0 "$(stream_query "$JQ_DEFS map(select((own or refused) | not)) | length")"
stream_query "$JQ_DEFS map(select((own or refused) | not)) | .[0:5]" >"$REPORT_DIR/database-silent-unexpected-answers.json"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-052 "Within 5 seconds of PostgreSQL being thawed every salon is answered 200 with its own brand again, and the API was never restarted"
# The five seconds are counted from the moment `resume postgres` was asked for, the strictest
# reading: the script's own time to thaw the container counts against the product.
recovery() { # <label> <the mark the thaw was asked at> <the mark the stream ran to>
  local label="$1" asked="$2" until="$3" first
  first="$(stream_query "$JQ_DEFS (map(select(.start >= \$$asked and own)) | min_by(.end) | .end) as \$first | if \$first == null then \"never\" else \$first - \$$asked end")"
  if [ "$first" != never ] && [ "$first" -lt "$LIMIT_MS" ]; then right "$label: the first 200 came $first ms after 'resume postgres' was asked for"; else wrong "$label: the first 200 came '$first' ms after 'resume postgres' was asked for, the limit is $LIMIT_MS ms"; fi
  expect_equal "$label: requests made 5 s or more after it was asked for: at least 100" true "$(stream_query "$JQ_DEFS map(select(.start >= \$$asked + \$limit and .start < \$$until)) | length >= 100")"
  expect_equal "$label: of those, answered with anything but the salon's own brand" 0 "$(stream_query "$JQ_DEFS map(select(.start >= \$$asked + \$limit and .start < \$$until)) | map(select(own | not)) | length")"
  expect_equal "$label: salons answered with their own brand after the thaw" "$(printf '%s\n' "${URLS[@]}" | sort | tr '\n' ' ')" "$(stream_query "$JQ_DEFS map(select(.start >= \$$asked and own)) | map(.url) | unique | sort | .[]" | tr '\n' ' ')"
}
recovery "after the first freeze" thaw_asked settled
recovery "after the long freeze" second_thaw_asked second_settled
# Whatever a frozen request left behind, no salon was ever answered with another's brand: before, between and after.
expect_equal "200 answers of the whole stream: at least 1000" true "$(stream_query "$JQ_DEFS map(select(.status == 200)) | length >= 1000")"
expect_equal "200 answers of the whole stream that were not exactly the brand of the hostname asked" 0 "$(stream_query "$JQ_DEFS map(select(.status == 200 and (own | not))) | length")"
expect_equal "the API's process, never restarted" "$API_PID_BEFORE" "$(listener_pid "$API_PORT")"
for slug in $SALON_SLUGS; do
  expect_equal "$slug's hostname, asked once more" "$(salon_value "$slug" NAME)" "$(curl -s --max-time 10 "$(salon_api "$slug")" | jq -r '.name' 2>/dev/null)"
done

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-053 "With PostgreSQL stopped, a salon's request is answered 503 service_unavailable within 5 seconds, and 200 again once it is started"
"$SCRIPTS/local-service.sh" stop postgres >"$WORK/stop.log" 2>&1 || environment_fault "could not stop PostgreSQL: $(cat "$WORK/stop.log")"
port_open "$POSTGRES_PORT" && environment_fault "PostgreSQL was stopped but port $POSTGRES_PORT still answers"
for attempt in first second third; do
  for slug in $SALON_SLUGS; do
    read -r status seconds content_type <<<"$(timed_get "$(salon_api "$slug")" "$WORK/stopped.json" 30)"
    expect_equal "$attempt request on $slug's hostname: status" 503 "$status"
    expect_equal "$attempt request on $slug's hostname: code" service_unavailable "$(jq -r '.code' "$WORK/stopped.json" 2>/dev/null)"
    awk -v t="${seconds:-99}" 'BEGIN { exit !(t < 5) }' && right "$attempt request on $slug's hostname: answered in $seconds s" || wrong "$attempt request on $slug's hostname: took ${seconds:-more than 30} s, the limit is 5 s"
  done
  [ "$attempt" = second ] && sleep 3
done
expect_matches "the 503's content type" '^application/problem\+json' "$content_type"
expect_no_match "the 503's body" "$INTERNALS_RE" "$(cat "$WORK/stopped.json")"
cp "$WORK/stopped.json" "$REPORT_DIR/salon-database-stopped.json" 2>/dev/null
# Forty at once, across the salons.
for index in $(seq 1 40); do ( timed_get "${URLS[$((index % ${#URLS[@]}))]}" /dev/null 30 >"$WORK/flood-$index.txt" ) & done
wait
not_503=0; slow=0; slowest=0
for index in $(seq 1 40); do
  read -r status seconds _ <"$WORK/flood-$index.txt" || true
  [ "${status:-}" = 503 ] || not_503=$((not_503 + 1))
  awk -v t="${seconds:-99}" 'BEGIN { exit !(t < 5) }' || slow=$((slow + 1))
  slowest="$(awk -v a="$slowest" -v b="${seconds:-99}" 'BEGIN { print (b > a) ? b : a }')"
done
expect_equal "forty at once: answers that were not 503" 0 "$not_503"
expect_equal "forty at once: answers that took 5 s or longer" 0 "$slow"
note "the slowest of forty took $slowest s"
expect_equal "meta on alpha's hostname while PostgreSQL is stopped" 200 "$(http_code "$(on_hostname "$API_URL" "$ALPHA_HOST")/api/v1/meta" 10)"

"$SCRIPTS/local-service.sh" start postgres >"$WORK/start.log" 2>&1 || wrong "could not start PostgreSQL again: $(tail -n 3 "$WORK/start.log")"
STARTED="$(now_ms)"
if wait_for_code "$(salon_api alpha)" 200 30; then right "alpha's hostname answered 200 again $(( $(now_ms) - STARTED )) ms after PostgreSQL was started"; else wrong "alpha's hostname did not return to 200 within 30 s of PostgreSQL being started (it answers $(http_code "$(salon_api alpha)"))"; fi
for slug in $SALON_SLUGS; do
  expect_equal "$slug's hostname afterwards" "$(salon_value "$slug" NAME)" "$(curl -s --max-time 10 "$(salon_api "$slug")" | jq -r '.name' 2>/dev/null)"
done
expect_equal "the API's process, never restarted" "$API_PID_BEFORE" "$(listener_pid "$API_PORT")"

finish
