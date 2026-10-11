#!/usr/bin/env bash
# Shared by the local-*.sh scripts. Sourced, never run.
#
# Settings come from the environment first, then from .env at the repository root, then from the
# defaults below. .env.example documents every one of them.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
STATE_DIR="$ROOT/.local"
RUN_DIR="$STATE_DIR/run"
LOG_DIR="$STATE_DIR/logs"
COMPOSE_PROJECT="salon-local"
REQUIRED_JAVA="25"
REQUIRED_NODE="24"

# Corepack downloads the pnpm version package.json pins without stopping to ask.
export COREPACK_ENABLE_DOWNLOAD_PROMPT=0

say()  { printf '%s\n' "$*"; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }

# --- settings ---------------------------------------------------------------------------------

# Reads KEY=VALUE lines from .env. A variable already set in the environment wins.
load_env_file() {
  local file="$ROOT/.env" line key value
  [ -f "$file" ] || return 0
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|\#*) continue ;; esac
    key="${line%%=*}"
    value="${line#*=}"
    case "$key" in *[!A-Za-z0-9_]*|'') continue ;; esac
    value="${value%\"}"; value="${value#\"}"
    if [ -z "${!key+set}" ]; then
      export "$key=$value"
    fi
  done < "$file"
}

load_settings() {
  load_env_file
  export SALON_POSTGRES_PORT="${SALON_POSTGRES_PORT:-5440}"
  export SALON_API_PORT="${SALON_API_PORT:-8090}"
  export SALON_WEB_PORT="${SALON_WEB_PORT:-3100}"
  export SALON_DB_NAME="${SALON_DB_NAME:-salon}"
  export SALON_DB_USER="${SALON_DB_USER:-salon}"
  export SALON_DB_PASSWORD="${SALON_DB_PASSWORD:-salon-local-only}"
  # Remember whether the API will use the local database or one somebody pointed it at.
  if [ -z "${SALON_DB_URL:-}" ]; then DATABASE_IS_LOCAL=1; else DATABASE_IS_LOCAL=0; fi
  export SALON_DB_URL="${SALON_DB_URL:-jdbc:postgresql://localhost:${SALON_POSTGRES_PORT}/${SALON_DB_NAME}}"
  export SALON_API_BASE_URL="${SALON_API_BASE_URL:-http://localhost:${SALON_API_PORT}}"
  export SALON_WEB_MODE="${SALON_WEB_MODE:-production}"
  export SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-local}"
  mkdir -p "$RUN_DIR" "$LOG_DIR"
}

# --- prerequisites ----------------------------------------------------------------------------

# Prints the major version of the JDK at $1, or nothing when there is no JDK there.
java_major_of() {
  [ -n "${1:-}" ] && [ -x "$1/bin/java" ] || return 0
  "$1/bin/java" -XshowSettings:properties -version 2>&1 \
    | awk -F'= ' '/java\.specification\.version/ { print $2; exit }'
}

# Finds JDK 25 without changing the machine's default Java. Sets RESOLVED_JAVA_HOME, or sets
# JAVA_PROBLEM and returns 1.
#
#   1. JAVA25_HOME, when set. It is then the only place looked at: an override that points at the
#      wrong JDK is an error, not something to route around.
#   2. The SDKMAN candidate named in .sdkmanrc.
#   3. JAVA_HOME, when it already is JDK 25.
#   4. macOS: /usr/libexec/java_home -v 25.
#   5. The `java` on PATH, when it is JDK 25.
resolve_java_home() {
  local found candidate sdkman_version path_home
  RESOLVED_JAVA_HOME=""
  JAVA_PROBLEM=""

  if [ -n "${JAVA25_HOME:-}" ]; then
    found="$(java_major_of "$JAVA25_HOME")"
    if [ "$found" = "$REQUIRED_JAVA" ]; then
      RESOLVED_JAVA_HOME="$JAVA25_HOME"
      return 0
    fi
    if [ -n "$found" ]; then found="Java $found"; else found="not a JDK"; fi
    JAVA_PROBLEM="JDK $REQUIRED_JAVA is required (wrong Java version): JAVA25_HOME points at '$JAVA25_HOME', which is $found. Point JAVA25_HOME at a JDK $REQUIRED_JAVA, or unset it."
    return 1
  fi

  sdkman_version="$(sed -n 's/^java=//p' "$ROOT/.sdkmanrc" 2>/dev/null | head -n 1)"
  path_home=""
  if command -v java >/dev/null 2>&1; then
    path_home="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java\.home/ { print $2; exit }')"
  fi

  for candidate in \
    "${sdkman_version:+${SDKMAN_DIR:-$HOME/.sdkman}/candidates/java/$sdkman_version}" \
    "${JAVA_HOME:-}" \
    "$( [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v "$REQUIRED_JAVA" 2>/dev/null || true )" \
    "$path_home"
  do
    if [ "$(java_major_of "$candidate")" = "$REQUIRED_JAVA" ]; then
      RESOLVED_JAVA_HOME="$candidate"
      return 0
    fi
  done

  found="$(java_major_of "${JAVA_HOME:-$path_home}")"
  JAVA_PROBLEM="JDK $REQUIRED_JAVA is required and was not found${found:+ (wrong Java version: the default Java here is $found)}. Install it, for example with SDKMAN: \`sdk install java ${sdkman_version:-$REQUIRED_JAVA}\` (it does not have to become the default), or set JAVA25_HOME to a JDK $REQUIRED_JAVA."
  return 1
}

docker_problem() {
  if ! command -v docker >/dev/null 2>&1; then
    say "Docker is not installed (the 'docker' command was not found)."
  elif ! docker info >/dev/null 2>&1; then
    say "Docker is not running. Start Docker and try again."
  elif ! docker compose version >/dev/null 2>&1; then
    say "Docker Compose is missing ('docker compose' does not work). It ships with current Docker."
  fi
}

node_problem() {
  local major
  if ! command -v node >/dev/null 2>&1; then
    say "Node.js $REQUIRED_NODE or later is required and 'node' was not found."
    return
  fi
  major="$(node -p 'process.versions.node.split(".")[0]' 2>/dev/null)"
  if [ -z "$major" ] || [ "$major" -lt "$REQUIRED_NODE" ]; then
    say "Node.js $REQUIRED_NODE or later is required; this is Node.js $(node -v 2>/dev/null)."
  elif ! command -v corepack >/dev/null 2>&1 && ! command -v pnpm >/dev/null 2>&1; then
    say "pnpm is not available. It comes through Corepack, which ships with Node.js: run 'corepack enable'."
  fi
}

# Checks the prerequisites the named services need, reports every one that is missing, and exits
# non-zero if any is. Nothing has been started when it does.
require_prerequisites() {
  local service problem problems=""
  for service in "$@"; do
    case "$service" in
      postgres) problem="$(docker_problem)" ;;
      api)      resolve_java_home || true; problem="$JAVA_PROBLEM" ;;
      web)      problem="$(node_problem)" ;;
      *)        problem="" ;;
    esac
    if [ -n "$problem" ]; then
      problems="${problems}  - ${problem}"$'\n'
    fi
  done
  if ! command -v curl >/dev/null 2>&1; then
    problems="${problems}  - curl is required and was not found."$'\n'
  fi
  if [ -n "$problems" ]; then
    printf 'error: a prerequisite is missing. Nothing was started.\n%s' "$problems" >&2
    printf 'The prerequisites are listed in README.md.\n' >&2
    exit 1
  fi
}

# --- helpers ----------------------------------------------------------------------------------

compose() {
  docker compose -p "$COMPOSE_PROJECT" -f "$ROOT/infra/docker-compose.yml" "$@"
}

# pnpm at the version package.json pins, through Corepack, whether or not its shims are installed.
pnpm_run() {
  if command -v corepack >/dev/null 2>&1; then
    corepack pnpm "$@"
  else
    pnpm "$@"
  fi
}

port_in_use() {
  (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

pid_file() { printf '%s/%s.pid' "$RUN_DIR" "$1"; }

# The pid of a service this script started and that is still alive; nothing otherwise.
running_pid() {
  local file pid
  file="$(pid_file "$1")"
  [ -f "$file" ] || return 0
  pid="$(cat "$file" 2>/dev/null)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    printf '%s' "$pid"
  else
    rm -f "$file"
  fi
}

# Stops a process this script started, and its children. Only ever a pid we recorded: a process
# that merely holds the same port belongs to someone else.
stop_process() {
  local name="$1" pid waited=0
  pid="$(running_pid "$name")"
  if [ -z "$pid" ]; then
    say "$name: not running"
    return 0
  fi
  pkill -TERM -P "$pid" 2>/dev/null || true
  kill -TERM "$pid" 2>/dev/null || true
  while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 30 ]; do
    sleep 1
    waited=$((waited + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    pkill -KILL -P "$pid" 2>/dev/null || true
    kill -KILL "$pid" 2>/dev/null || true
  fi
  rm -f "$(pid_file "$name")"
  say "$name: stopped"
}

# Waits until the URL answers 200, the process dies, or the time runs out.
wait_until_up() {
  local name="$1" url="$2" seconds="$3" pid waited=0
  pid="$(running_pid "$name")"
  while [ "$waited" -lt "$seconds" ]; do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url" 2>/dev/null)" = "200" ]; then
      return 0
    fi
    if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
      break
    fi
    sleep 1
    waited=$((waited + 1))
  done
  printf 'error: %s did not come up at %s. The last lines of %s/%s.log:\n' "$name" "$url" "$LOG_DIR" "$name" >&2
  tail -n 30 "$LOG_DIR/$name.log" >&2 2>/dev/null || true
  stop_process "$name" >/dev/null 2>&1 || true
  exit 1
}

refuse_foreign_port() {
  local name="$1" port="$2" variable="$3"
  if port_in_use "$port"; then
    fail "$name cannot start: port $port is already in use by something this script did not start. Free it, or set $variable to another port (see .env.example)."
  fi
}

# --- the three services -----------------------------------------------------------------------

postgres_is_running() {
  [ -n "$(compose ps --status running --quiet postgres 2>/dev/null)" ]
}

start_postgres() {
  if postgres_is_running; then
    say "postgres: already running"
    return 0
  fi
  if [ -z "$(compose ps --all --quiet postgres 2>/dev/null)" ]; then
    refuse_foreign_port postgres "$SALON_POSTGRES_PORT" SALON_POSTGRES_PORT
  fi
  compose up --detach --wait postgres >"$LOG_DIR/postgres.log" 2>&1 \
    || { tail -n 30 "$LOG_DIR/postgres.log" >&2; fail "postgres did not start. See $LOG_DIR/postgres.log"; }
  say "postgres: started"
}

# Stops the container and keeps it, so that starting it again is quick and its data is still there.
stop_postgres() {
  if postgres_is_running; then
    compose stop postgres >>"$LOG_DIR/postgres.log" 2>&1
    say "postgres: stopped"
  else
    say "postgres: not running"
  fi
}

start_api() {
  if [ -n "$(running_pid api)" ]; then
    say "api: already running"
    return 0
  fi
  refuse_foreign_port api "$SALON_API_PORT" SALON_API_PORT
  if [ "$DATABASE_IS_LOCAL" = "1" ] && ! port_in_use "$SALON_POSTGRES_PORT"; then
    fail "api cannot start: it needs PostgreSQL, and nothing is listening on port $SALON_POSTGRES_PORT. Start it first: infra/scripts/local-service.sh start postgres"
  fi
  say "api: building"
  ( cd "$ROOT/apps/api" && JAVA_HOME="$RESOLVED_JAVA_HOME" ./mvnw -q -DskipTests package ) >"$LOG_DIR/api-build.log" 2>&1 \
    || { tail -n 30 "$LOG_DIR/api-build.log" >&2; fail "the API did not build. See $LOG_DIR/api-build.log"; }
  ( cd "$ROOT/apps/api" && exec nohup "$RESOLVED_JAVA_HOME/bin/java" -jar target/salon-api.jar ) >"$LOG_DIR/api.log" 2>&1 &
  printf '%s' "$!" >"$(pid_file api)"
  wait_until_up api "http://localhost:${SALON_API_PORT}/actuator/health" 90
  say "api: started"
}

stop_api() { stop_process api; }

start_web() {
  if [ -n "$(running_pid web)" ]; then
    say "web: already running"
    return 0
  fi
  refuse_foreign_port web "$SALON_WEB_PORT" SALON_WEB_PORT
  say "web: installing dependencies"
  ( cd "$ROOT" && pnpm_run install --frozen-lockfile ) >"$LOG_DIR/web-build.log" 2>&1 \
    || { tail -n 30 "$LOG_DIR/web-build.log" >&2; fail "pnpm install failed. See $LOG_DIR/web-build.log"; }
  if [ "$SALON_WEB_MODE" = "dev" ]; then
    ( cd "$ROOT/apps/web" && exec nohup node_modules/.bin/next dev --port "$SALON_WEB_PORT" ) >"$LOG_DIR/web.log" 2>&1 &
  else
    say "web: building"
    ( cd "$ROOT" && pnpm_run --filter @salon/web build ) >>"$LOG_DIR/web-build.log" 2>&1 \
      || { tail -n 30 "$LOG_DIR/web-build.log" >&2; fail "the web app did not build. See $LOG_DIR/web-build.log"; }
    ( cd "$ROOT/apps/web" && exec nohup node_modules/.bin/next start --port "$SALON_WEB_PORT" ) >"$LOG_DIR/web.log" 2>&1 &
  fi
  printf '%s' "$!" >"$(pid_file web)"
  wait_until_up web "http://localhost:${SALON_WEB_PORT}/" 90
  say "web: started"
}

stop_web() { stop_process web; }

print_addresses() {
  say ""
  say "PostgreSQL  localhost:${SALON_POSTGRES_PORT}  (database ${SALON_DB_NAME}, user ${SALON_DB_USER})"
  say "API         http://localhost:${SALON_API_PORT}  (health: http://localhost:${SALON_API_PORT}/actuator/health)"
  say "Web         http://localhost:${SALON_WEB_PORT}"
  say "Logs        ${LOG_DIR}"
}
