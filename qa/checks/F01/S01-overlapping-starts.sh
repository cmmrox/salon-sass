#!/usr/bin/env bash
# F01-S01, AC-1: the local scripts when more than one of them runs at a time (BUG-F01-S01-001).
#
# Cases: TC-F01-S01-058 and TC-F01-S01-059. Expected results come from AC-1 (local-up.sh starts
# PostgreSQL, the API and the web app) and from what a second start does once the first has
# finished (TC-F01-S01-004): a start that overlaps another must not undo it, and local-down.sh
# stops everything that was started.
#
# The check stops and starts the whole product, and leaves it as it found it.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool curl docker lsof pgrep

WORK="$(mktemp -d)"
LOCK="$RUN_DIR/lock"
if product_is_up; then WAS_UP=1; else WAS_UP=0; fi
note "the product was $([ "$WAS_UP" = 1 ] && echo running || echo stopped) when this check began"

restore() {
  rm -f "$LOCK.qa-placed"
  if [ "$WAS_UP" = 1 ]; then
    product_is_up || "$SCRIPTS/local-up.sh" >"$WORK/restore.log" 2>&1 \
      || printf 'ENVIRONMENT: could not start the product again; see %s\n' "$WORK/restore.log" >&2
  else
    "$SCRIPTS/local-down.sh" >/dev/null 2>&1
  fi
  rm -rf "$WORK"
}
trap restore EXIT

# Runs one of the scripts in the background; its output goes to <label>.out and its exit code to <label>.exit.
in_background() {
  local label="$1"; shift
  ( "$@" >"$WORK/$label.out" 2>&1; echo $? >"$WORK/$label.exit" ) &
}
exit_of() { cat "$WORK/$1.exit" 2>/dev/null || echo none; }

stopped_cleanly() {
  "$SCRIPTS/local-down.sh" >"$WORK/down.log" 2>&1 || true
  wait_for_port_closed "$API_PORT" 30; wait_for_port_closed "$WEB_PORT" 30
  [ -z "$(product_processes)" ] && ! port_open "$API_PORT" && ! port_open "$WEB_PORT" \
    || environment_fault "the product could not be stopped before the case: $(product_processes | tr '\n' ' ')"
}

# The product is running once, and only once, and the scripts know which processes are theirs.
expect_running_once() {
  local api_processes
  expect_equal "$1: GET $API_URL/actuator/health" 200 "$(http_code "$API_URL/actuator/health")"
  expect_equal "$1: GET $WEB_URL/" 200 "$(http_code "$WEB_URL/")"
  api_processes="$(pgrep -f 'salon-api\.jar' | wc -l | tr -d ' ')"
  expect_equal "$1: API processes" 1 "$api_processes"
  expect_equal "$1: the API process the scripts recorded is the one listening on port $API_PORT" "$(listener_pid "$API_PORT")" "$(recorded_pid api)"
  expect_equal "$1: the web process the scripts recorded is the one listening on port $WEB_PORT" "$(listener_pid "$WEB_PORT")" "$(recorded_pid web)"
  if [ -e "$LOCK" ] || [ -L "$LOCK" ]; then wrong "$1: a lock is left behind at ${LOCK#"$PRODUCT_ROOT"/}"; else right "$1: no lock is left behind"; fi
}

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-058 "Starts and stops that overlap leave the product running once, and local-down.sh still stops everything"

for delay in 5 0; do
  stopped_cleanly
  if [ "$delay" = 0 ]; then
    label="three starts at the same instant"
    in_background a "$SCRIPTS/local-up.sh"; in_background b "$SCRIPTS/local-up.sh"; in_background c "$SCRIPTS/local-up.sh"
    wait
    runs="a b c"
  else
    label="a second start ${delay} s into the first (while the API builds)"
    in_background a "$SCRIPTS/local-up.sh"
    sleep "$delay"
    in_background b "$SCRIPTS/local-up.sh"
    wait
    runs="a b"
  fi
  for run in $runs; do
    expect_equal "$label: exit code of run $run" 0 "$(exit_of "$run")"
    expect_contains "$label: run $run prints the API's address" "$API_URL" "$(cat "$WORK/$run.out")"
    cp "$WORK/$run.out" "$REPORT_DIR/overlap-$delay-$run.log"
  done
  expect_running_once "$label"
  if [ "$delay" != 0 ]; then
    expect_contains "$label: the second run says it is waiting for the first" "waiting for another of these scripts to finish" "$(cat "$WORK/b.out")"
  fi
done

# local-down.sh issued while a start is still going: afterwards, nothing is left running.
stopped_cleanly
in_background up "$SCRIPTS/local-up.sh"
sleep 5
in_background down "$SCRIPTS/local-down.sh"
wait
expect_equal "a stop issued during a start: exit code of the stop" 0 "$(exit_of down)"
wait_for_port_closed "$API_PORT" 30; wait_for_port_closed "$WEB_PORT" 30
for port in "$API_PORT" "$WEB_PORT" "$POSTGRES_PORT"; do
  if port_open "$port"; then wrong "a stop issued during a start: something still listens on port $port"; else right "a stop issued during a start: nothing listens on port $port"; fi
done
expect_equal "a stop issued during a start: processes of the API or the web app left behind" "" "$(product_processes | tr '\n' ' ')"
note "the start it overlapped exited $(exit_of up)"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-059 "A lock left by a script that was killed does not stop the next run, and is removed"
stopped_cleanly
mkdir -p "$RUN_DIR"
for holder in "999999 Thu Jan  1 00:00:00 1970" "1 Thu Jan  1 00:00:00 1970"; do
  # The first names a process that does not exist; the second a live process (process 1) that did
  # not start at that time: both are what a killed script leaves behind once its id is reused.
  rm -f "$LOCK"; ln -s "$holder" "$LOCK"
  [ -L "$LOCK" ] && right "before: a lock naming '$holder' is in place" || wrong "before: could not place the lock, so this proves nothing"
  started=$(date +%s)
  in_background left "$SCRIPTS/local-up.sh"
  waited=0
  while [ ! -f "$WORK/left.exit" ] && [ "$waited" -lt 240 ]; do sleep 1; waited=$((waited + 1)); done
  if [ ! -f "$WORK/left.exit" ]; then
    wrong "lock naming '$holder': local-up.sh had not finished after 240 s; it is waiting on a lock nobody holds"
    pkill -TERM -f "$SCRIPTS/local-up.sh" 2>/dev/null; wait
    rm -f "$LOCK"
  else
    wait
    expect_equal "lock naming '$holder': exit code of local-up.sh" 0 "$(exit_of left)"
    note "it took $(( $(date +%s) - started )) s"
    expect_running_once "lock naming '$holder'"
  fi
  rm -f "$WORK/left.exit"
  stopped_cleanly
done

finish
