#!/usr/bin/env bash
# F01-S01, AC-1: a process-id file left behind by an earlier run (BUG-F01-S01-002).
#
# Case: TC-F01-S01-060. Expected: local-up.sh reports a service as running only when it is, starts
# it otherwise, and local-down.sh stops only what the scripts started. An unrelated process whose
# id happens to be in a leftover file is never signalled.
#
# The check stops and starts the whole product, and leaves it as it found it.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool curl docker lsof pgrep

WORK="$(mktemp -d)"
UNRELATED=""
if product_is_up; then WAS_UP=1; else WAS_UP=0; fi

restore() {
  for pid in $UNRELATED; do kill "$pid" 2>/dev/null; done
  if [ "$WAS_UP" = 1 ]; then
    product_is_up || "$SCRIPTS/local-up.sh" >"$WORK/restore.log" 2>&1 \
      || printf 'ENVIRONMENT: could not start the product again; see %s\n' "$WORK/restore.log" >&2
  else
    "$SCRIPTS/local-down.sh" >/dev/null 2>&1
  fi
  rm -rf "$WORK"
}
trap restore EXIT

# A long-lived process that has nothing to do with the product. Its id is left in STRANGER_PID.
# (Not printed for a $(...) to capture: the background process would hold that pipe open.)
unrelated_process() {
  sleep 900 </dev/null >/dev/null 2>&1 &
  STRANGER_PID=$!
  UNRELATED="$UNRELATED $STRANGER_PID"
}
alive() { kill -0 "$1" 2>/dev/null; }

begin_case TC-F01-S01-060 "A process-id file left by an earlier run never makes a stopped service look running, and its process is never stopped"

# Round 1: the bug's own steps for the API (a bare id, as the old scripts wrote it), and the same
# for the web app with an id and a start time that is not that process's.
"$SCRIPTS/local-down.sh" >/dev/null 2>&1
mkdir -p "$RUN_DIR"
unrelated_process; API_STRANGER=$STRANGER_PID; unrelated_process; WEB_STRANGER=$STRANGER_PID
printf '%s' "$API_STRANGER" >"$RUN_DIR/api.pid"
printf '%s %s' "$WEB_STRANGER" "Thu Jan  1 00:00:00 1970" >"$RUN_DIR/web.pid"
alive "$API_STRANGER" && alive "$WEB_STRANGER" && right "before: two unrelated processes ($API_STRANGER, $WEB_STRANGER) are alive and named in the pid files" \
  || wrong "before: the unrelated processes are not alive, so this proves nothing"
UP="$("$SCRIPTS/local-up.sh" 2>&1)"; UP_EXIT=$?
printf '%s\n' "$UP" >"$REPORT_DIR/stale-pid-up.log"
expect_equal "exit code of local-up.sh" 0 "$UP_EXIT"
expect_contains "local-up.sh says it started the API" "api: started" "$UP"
expect_contains "local-up.sh says it started the web app" "web: started" "$UP"
expect_no_match "local-up.sh" 'already running' "$UP"
expect_equal "GET $API_URL/actuator/health" 200 "$(http_code "$API_URL/actuator/health")"
expect_equal "GET $API_URL/api/v1/meta" 200 "$(http_code "$API_URL/api/v1/meta")"
expect_not_equal "the process listening on the API's port" "$API_STRANGER" "$(listener_pid "$API_PORT")"
expect_contains "local-up.sh notes the leftover api.pid" "api.pid was left by an earlier run" "$UP"
DOWN="$("$SCRIPTS/local-down.sh" 2>&1)"; DOWN_EXIT=$?
expect_equal "exit code of local-down.sh" 0 "$DOWN_EXIT"
if alive "$API_STRANGER"; then right "the process named in the leftover api.pid is still alive"; else wrong "local-down.sh stopped process $API_STRANGER, which the scripts never started"; fi
if alive "$WEB_STRANGER"; then right "the process named in the leftover web.pid is still alive"; else wrong "local-down.sh stopped process $WEB_STRANGER, which the scripts never started"; fi
wait_for_port_closed "$API_PORT" 30; wait_for_port_closed "$WEB_PORT" 30
expect_equal "processes of the API or the web app after local-down.sh" "" "$(product_processes | tr '\n' ' ')"

# Round 2: a file naming a process that no longer exists, and a single-service stop with a leftover.
# The id of a process that has already ended. (Not a background process killed at once: killed
# before it has become `sleep`, it is still a copy of this shell and runs this shell's EXIT trap.)
DEAD="$(sh -c 'echo $$')"
kill -0 "$DEAD" 2>/dev/null && wrong "process $DEAD is alive, so it cannot stand for a dead one" || right "process $DEAD has ended"
printf '%s' "$DEAD" >"$RUN_DIR/api.pid"
UP="$("$SCRIPTS/local-up.sh" 2>&1)"; UP_EXIT=$?
expect_equal "a leftover naming a dead process: exit code of local-up.sh" 0 "$UP_EXIT"
expect_contains "a leftover naming a dead process: local-up.sh started the API" "api: started" "$UP"
expect_equal "a leftover naming a dead process: GET $API_URL/actuator/health" 200 "$(http_code "$API_URL/actuator/health")"
REAL_API="$(listener_pid "$API_PORT")"
# The web app is stopped properly first, so that the leftover below replaces nothing real.
"$SCRIPTS/local-service.sh" stop web >/dev/null 2>&1
wait_for_port_closed "$WEB_PORT" 30 && right "the web app was stopped before the leftover was written" || wrong "the web app did not stop"
unrelated_process; STRANGER=$STRANGER_PID
printf '%s' "$STRANGER" >"$RUN_DIR/web.pid"
STOP="$("$SCRIPTS/local-service.sh" stop web 2>&1)"; STOP_EXIT=$?
expect_equal "'stop web' with a leftover web.pid: exit code" 0 "$STOP_EXIT"
expect_contains "'stop web' with a leftover web.pid says the web app is not running" "web: not running" "$STOP"
if alive "$STRANGER"; then right "'stop web' left the unrelated process alone"; else wrong "'stop web' stopped process $STRANGER, which the scripts never started"; fi
expect_equal "the API was not touched by 'stop web'" "$REAL_API" "$(listener_pid "$API_PORT")"

finish
