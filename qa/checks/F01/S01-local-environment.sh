#!/usr/bin/env bash
# F01-S01, AC-1: the scripts that start and stop the product on a developer's machine.
#
# Cases: TC-F01-S01-001 to TC-F01-S01-008. Expected results come from AC-1 and the stage's scope
# ("Local environment"): local-up.sh starts PostgreSQL, the API and the web app and prints the
# address of each; with a prerequisite missing it exits non-zero and names what is missing.
#
# The check stops and starts the whole product. It ends with the product in the state it found it
# in: running if it was running, stopped if it was stopped.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool curl docker lsof python3 pgrep

WORK="$(mktemp -d)"
FOREIGN_PID=""
if product_is_up; then WAS_UP=1; else WAS_UP=0; fi
note "the product was $([ "$WAS_UP" = 1 ] && echo running || echo stopped) when this check began"

restore() {
  [ -n "$FOREIGN_PID" ] && kill "$FOREIGN_PID" 2>/dev/null
  if [ "$WAS_UP" = 1 ]; then
    product_is_up || "$SCRIPTS/local-up.sh" >"$WORK/restore.log" 2>&1 \
      || printf 'ENVIRONMENT: could not start the product again; see %s\n' "$WORK/restore.log" >&2
  else
    "$SCRIPTS/local-down.sh" >/dev/null 2>&1
  fi
}
trap restore EXIT

# Nothing of the product is running: no listener on its three ports, no process, no container.
expect_nothing_running() {
  local port
  for port in "$API_PORT" "$WEB_PORT" "$POSTGRES_PORT"; do
    if port_open "$port"; then wrong "$1: something still listens on port $port"; else right "$1: nothing listens on port $port"; fi
  done
  expect_equal "$1: processes of the API or the web app" "" "$(pgrep -f 'salon-api\.jar|next-server|next start' | tr '\n' ' ')"
  expect_equal "$1: running container $POSTGRES_CONTAINER" "" "$(docker ps --quiet --filter "name=^${POSTGRES_CONTAINER}$")"
}

# run_up <label> [VAR=value ...]: runs local-up.sh with extra environment; sets UP_EXIT and UP_OUTPUT.
run_up() {
  local label="$1"; shift
  UP_OUTPUT="$(env "$@" "$SCRIPTS/local-up.sh" 2>&1)"
  UP_EXIT=$?
  printf '%s\n' "$UP_OUTPUT" >"$REPORT_DIR/local-up-$label.log"
}

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-005 "local-down.sh stops all three services, and a second local-down.sh exits zero"
"$SCRIPTS/local-up.sh" >"$WORK/first-up.log" 2>&1 \
  || environment_fault "could not start the product to begin with: $(tail -n 5 "$WORK/first-up.log")"
product_is_up && right "before: the product is running" || wrong "before: the product is not running, so stopping it proves nothing"
DOWN_OUTPUT="$("$SCRIPTS/local-down.sh" 2>&1)"; DOWN_EXIT=$?
expect_equal "exit code of local-down.sh" 0 "$DOWN_EXIT"
wait_for_port_closed "$API_PORT" 30; wait_for_port_closed "$WEB_PORT" 30; wait_for_port_closed "$POSTGRES_PORT" 30
expect_nothing_running "after local-down.sh"
AGAIN_OUTPUT="$("$SCRIPTS/local-down.sh" 2>&1)"; AGAIN_EXIT=$?
expect_equal "exit code of a second local-down.sh" 0 "$AGAIN_EXIT"
expect_nothing_running "after a second local-down.sh"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-002 "With Docker unreachable, local-up.sh exits non-zero, names Docker and starts nothing"
run_up docker-unreachable DOCKER_HOST=unix:///nonexistent/docker.sock
expect_not_equal "exit code" 0 "$UP_EXIT"
expect_matches "the output" 'docker' "$UP_OUTPUT"
expect_nothing_running "after the refused start"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-003 "With a Java that is not 25, local-up.sh exits non-zero, names the Java it needs and starts nothing"
# A real JDK of another version when the machine has one; otherwise a stand-in that answers as one.
OTHER_JDK=""
for candidate in "${JAVA_HOME:-}" "${SDKMAN_DIR:-$HOME/.sdkman}"/candidates/java/* /Library/Java/JavaVirtualMachines/*/Contents/Home /usr/lib/jvm/*; do
  [ -x "$candidate/bin/java" ] || continue
  major="$("$candidate/bin/java" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java\.specification\.version/ { print $2; exit }')"
  if [ -n "$major" ] && [ "$major" != "25" ]; then OTHER_JDK="$candidate"; OTHER_MAJOR="$major"; break; fi
done
if [ -z "$OTHER_JDK" ]; then
  OTHER_JDK="$WORK/jdk-21"; OTHER_MAJOR=21
  mkdir -p "$OTHER_JDK/bin"
  printf '#!/bin/sh\nprintf "Property settings:\\n    java.specification.version = 21\\n    java.version = 21.0.9\\n" >&2\nprintf "openjdk version \\"21.0.9\\" 2025-10-21 LTS\\n" >&2\n' >"$OTHER_JDK/bin/java"
  chmod +x "$OTHER_JDK/bin/java"
  note "no JDK other than 25 is installed; using a stand-in that reports Java 21"
fi
note "the other Java is Java $OTHER_MAJOR at $OTHER_JDK"
run_up wrong-java JAVA25_HOME="$OTHER_JDK"
expect_not_equal "exit code" 0 "$UP_EXIT"
expect_matches "the output names the Java that is needed" '(jdk|java)[ -]?25' "$UP_OUTPUT"
expect_nothing_running "after the refused start"
# Both missing at once: each is named, so the developer fixes them in one go.
run_up docker-and-java DOCKER_HOST=unix:///nonexistent/docker.sock JAVA25_HOME="$OTHER_JDK"
expect_not_equal "exit code with Docker and Java both missing" 0 "$UP_EXIT"
expect_matches "the output with both missing names Docker" 'docker' "$UP_OUTPUT"
expect_matches "the output with both missing names Java 25" '(jdk|java)[ -]?25' "$UP_OUTPUT"
expect_nothing_running "after the start refused for both"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-006 "With its port taken by another program, local-up.sh exits non-zero, names the port and leaves that program alone"
python3 -m http.server "$API_PORT" --bind 127.0.0.1 >/dev/null 2>&1 &
FOREIGN_PID=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do port_open "$API_PORT" && break; sleep 1; done
port_open "$API_PORT" && right "before: another program listens on port $API_PORT" \
  || wrong "before: the other program did not start listening on port $API_PORT, so this proves nothing"
run_up port-taken
expect_not_equal "exit code" 0 "$UP_EXIT"
expect_contains "the output names the port" "$API_PORT" "$UP_OUTPUT"
expect_contains "the output names the setting that moves it" "SALON_API_PORT" "$UP_OUTPUT"
if kill -0 "$FOREIGN_PID" 2>/dev/null; then right "the other program is still running"; else wrong "the other program was stopped"; fi
expect_equal "who listens on port $API_PORT" "$FOREIGN_PID" "$(listener_pid "$API_PORT")"
expect_equal "processes of the API" "" "$(pgrep -f 'salon-api\.jar' | tr '\n' ' ')"
kill "$FOREIGN_PID" 2>/dev/null; wait "$FOREIGN_PID" 2>/dev/null; FOREIGN_PID=""
wait_for_port_closed "$API_PORT" 10
"$SCRIPTS/local-down.sh" >/dev/null 2>&1

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-001 "From stopped, local-up.sh starts PostgreSQL, the API and the web app and prints the address of each"
expect_nothing_running "before"
STARTED_AT=$(date +%s)
run_up clean-start
note "local-up.sh took $(( $(date +%s) - STARTED_AT )) seconds"
expect_equal "exit code" 0 "$UP_EXIT"
expect_contains "the output gives PostgreSQL's address" "localhost:${POSTGRES_PORT}" "$UP_OUTPUT"
expect_contains "the output gives the API's address" "$API_URL" "$UP_OUTPUT"
expect_contains "the output gives the web app's address" "$WEB_URL" "$UP_OUTPUT"
# Each printed address really answers, as soon as the script has returned.
expect_equal "GET $API_URL/actuator/health" 200 "$(http_code "$API_URL/actuator/health")"
expect_equal "GET $API_URL/api/v1/meta" 200 "$(http_code "$API_URL/api/v1/meta")"
expect_equal "GET $WEB_URL/" 200 "$(http_code "$WEB_URL/")"
expect_contains "the web app's home page" 'data-testid="status-panel"' "$(curl -s --max-time 10 "$WEB_URL/")"
expect_equal "PostgreSQL answers a query at its address" 1 "$(psql_value 'select 1')"
if port_open "$POSTGRES_PORT"; then right "PostgreSQL listens on port $POSTGRES_PORT"; else wrong "nothing listens on PostgreSQL's port $POSTGRES_PORT"; fi

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-004 "A second local-up.sh while the product is running changes nothing and exits zero"
API_PID_BEFORE="$(listener_pid "$API_PORT")"; WEB_PID_BEFORE="$(listener_pid "$WEB_PORT")"
PG_STARTED_BEFORE="$(docker inspect --format '{{.State.StartedAt}}' "$POSTGRES_CONTAINER" 2>/dev/null)"
[ -n "$API_PID_BEFORE" ] && [ -n "$WEB_PID_BEFORE" ] && [ -n "$PG_STARTED_BEFORE" ] \
  && right "before: the API is process $API_PID_BEFORE, the web app $WEB_PID_BEFORE, PostgreSQL started $PG_STARTED_BEFORE" \
  || wrong "before: could not identify the three running services, so 'unchanged' proves nothing"
run_up second
expect_equal "exit code" 0 "$UP_EXIT"
expect_contains "the output still gives the API's address" "$API_URL" "$UP_OUTPUT"
expect_contains "the output still gives the web app's address" "$WEB_URL" "$UP_OUTPUT"
expect_equal "the API's process" "$API_PID_BEFORE" "$(listener_pid "$API_PORT")"
expect_equal "the web app's process" "$WEB_PID_BEFORE" "$(listener_pid "$WEB_PORT")"
expect_equal "when PostgreSQL started" "$PG_STARTED_BEFORE" "$(docker inspect --format '{{.State.StartedAt}}' "$POSTGRES_CONTAINER" 2>/dev/null)"
expect_equal "GET $API_URL/actuator/health" 200 "$(http_code "$API_URL/actuator/health")"
expect_equal "GET $WEB_URL/" 200 "$(http_code "$WEB_URL/")"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-007 "After the API process dies, local-up.sh starts it again and leaves the rest alone"
kill -9 "$API_PID_BEFORE" 2>/dev/null
wait_for_port_closed "$API_PORT" 15 && right "before: the API process was killed and its port is closed" \
  || wrong "before: the API is still listening after its process was killed"
run_up after-crash
expect_equal "exit code" 0 "$UP_EXIT"
expect_equal "GET $API_URL/actuator/health" 200 "$(http_code "$API_URL/actuator/health")"
expect_not_equal "the API's process" "$API_PID_BEFORE" "$(listener_pid "$API_PORT")"
expect_equal "the web app's process" "$WEB_PID_BEFORE" "$(listener_pid "$WEB_PORT")"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-008 "local-service.sh stops and starts one service, leaves the others running, and refuses anything else"
API_PID_NOW="$(listener_pid "$API_PORT")"
STOP_OUTPUT="$("$SCRIPTS/local-service.sh" stop web 2>&1)"; expect_equal "exit code of 'stop web'" 0 "$?"
wait_for_port_closed "$WEB_PORT" 30 && right "the web app's port is closed" || wrong "the web app still listens after 'stop web'"
expect_equal "the API's process after 'stop web'" "$API_PID_NOW" "$(listener_pid "$API_PORT")"
expect_equal "GET $API_URL/actuator/health after 'stop web'" 200 "$(http_code "$API_URL/actuator/health")"
START_OUTPUT="$("$SCRIPTS/local-service.sh" start web 2>&1)"; expect_equal "exit code of 'start web'" 0 "$?"
expect_equal "GET $WEB_URL/ after 'start web'" 200 "$(http_code "$WEB_URL/")"
expect_equal "the API's process after 'start web'" "$API_PID_NOW" "$(listener_pid "$API_PORT")"
WEB_PID_NOW="$(listener_pid "$WEB_PORT")"
# pause and resume exist for PostgreSQL only (the test hook of BUG-F01-S01-003).
for arguments in "restart api" "stop redis" "start" "" "pause api" "resume web" "pause"; do
  # shellcheck disable=SC2086
  REFUSED_OUTPUT="$("$SCRIPTS/local-service.sh" $arguments 2>&1)"; REFUSED_EXIT=$?
  expect_not_equal "exit code of local-service.sh '$arguments'" 0 "$REFUSED_EXIT"
  expect_matches "what local-service.sh '$arguments' prints" 'start\|stop.*postgres\|api\|web' "$REFUSED_OUTPUT"
  expect_matches "what local-service.sh '$arguments' prints" 'pause\|resume.*postgres' "$REFUSED_OUTPUT"
done
expect_equal "the API's process after the refused commands" "$API_PID_NOW" "$(listener_pid "$API_PORT")"
expect_equal "the web app's process after the refused commands" "$WEB_PID_NOW" "$(listener_pid "$WEB_PORT")"

finish
