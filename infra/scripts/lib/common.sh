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
  export SALON_DB_APP_USER="${SALON_DB_APP_USER:-salon_app}"
  export SALON_DB_APP_PASSWORD="${SALON_DB_APP_PASSWORD:-salon-app-local-only}"
  if [ "$SALON_DB_APP_USER" = "$SALON_DB_USER" ]; then
    fail "SALON_DB_APP_USER and SALON_DB_USER are both '$SALON_DB_USER'. The API connects as a role of its own, which owns no table and cannot get round row-level security; the owning role runs the migrations. Give them different names (see .env.example)."
  fi
  export SALON_PLATFORM_DOMAIN="${SALON_PLATFORM_DOMAIN:-localhost}"
  case "${SALON_LISTEN_ON_NETWORK:-false}" in
    true|false) export SALON_LISTEN_ON_NETWORK="${SALON_LISTEN_ON_NETWORK:-false}" ;;
    *) fail "SALON_LISTEN_ON_NETWORK must be 'true' or 'false', not '${SALON_LISTEN_ON_NETWORK}' (see .env.example)." ;;
  esac
  # Remember whether the API will use the local database or one somebody pointed it at.
  if [ -z "${SALON_DB_URL:-}" ]; then DATABASE_IS_LOCAL=1; else DATABASE_IS_LOCAL=0; fi
  export SALON_DB_URL="${SALON_DB_URL:-jdbc:postgresql://localhost:${SALON_POSTGRES_PORT}/${SALON_DB_NAME}}"
  export SALON_API_BASE_URL="${SALON_API_BASE_URL:-http://localhost:${SALON_API_PORT}}"
  export SALON_IDENTITY_LOCAL_KEY_FILE="${SALON_IDENTITY_LOCAL_KEY_FILE:-$ROOT/.local/identity-keys.properties}"
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
  elif ! command -v corepack >/dev/null 2>&1; then
    say "Corepack is required and 'corepack' was not found. It provides pnpm at the version package.json pins; a pnpm installed any other way is not used. Corepack ships with Node.js $REQUIRED_NODE: if your Node.js came without it, install Corepack, then run 'corepack enable'."
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
  problem="$(ps_problem)"
  if [ -n "$problem" ]; then
    problems="${problems}  - ${problem}"$'\n'
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
# Never the `pnpm` that happens to be on PATH: one of another version fetches the pinned version
# itself and records that in pnpm-lock.yaml, so the committed file would change under a developer.
pnpm_run() {
  corepack pnpm "$@"
}

port_in_use() {
  (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null
}

pid_file() { printf '%s/%s.pid' "$RUN_DIR" "$1"; }

# When the process with this id started, as the system reports it; nothing when there is no such
# process. The system gives a process id to another process once the first is gone, but never the
# same id with the same start time: the two together name one process and no other.
#
# `ps` prints that time in the caller's time zone, so it is always asked in UTC: a service started
# by a shell with one TZ (`TZ=Pacific/Chatham local-service.sh start api`, to see that the API runs
# in UTC whatever the machine's zone is) must still be this script's own when another shell stops it.
process_started() {
  case "${1:-}" in ''|*[!0-9]*) return 0 ;; esac
  LC_ALL=C TZ=UTC ps -p "$1" -o lstart= 2>/dev/null | sed -e 's/^ *//' -e 's/ *$//' || true
}

# These scripts tell the processes they started from every other by when each one started. Where
# `ps` cannot say, they could no longer tell, and would report a running service as stopped; so
# that is a missing prerequisite, said aloud, and not something to carry on without.
ps_problem() {
  if [ -z "$(process_started "$$")" ]; then
    say "A 'ps' that reports when a process started is required, and 'ps -p <id> -o lstart=' printed nothing. macOS has one; on Linux it is in the procps package."
  fi
}

# --- one script at a time ---------------------------------------------------------------------

# Two of these scripts running at once build into the same folder, write the same pid files and
# stop each other's processes. So each takes a lock before it starts or stops anything, and a
# second one waits for the first to finish; it then finds things as the first left them.
#
# The lock is a symbolic link whose target names its holder (process id and start time). Creating
# a link either succeeds or fails because one is there, with nothing in between, and it carries
# the holder's name from the instant it exists.
LOCK="$RUN_DIR/lock"
LOCK_WAIT_SECONDS=900
LOCK_HOLDER=""

# Whether the process a lock or a pid file names ("<id> <start time>") is still that process.
is_same_process() {
  local pid="${1%% *}" started="${1#* }"
  [ -n "$started" ] && [ "$started" != "$1" ] && [ "$(process_started "$pid")" = "$started" ]
}

release_lock() {
  if [ -n "$LOCK_HOLDER" ] && [ "$(readlink "$LOCK" 2>/dev/null || true)" = "$LOCK_HOLDER" ]; then
    rm -f "$LOCK"
  fi
  LOCK_HOLDER=""
}

# Removes a lock whose holder ($1) is gone: a script that was killed before it could let go. One
# waiter at a time may do it, and only after looking again, so that a lock another script has
# taken in the meantime is never the one removed.
clear_abandoned_lock() {
  # A leftover of this very step lasts an instant; one that is a minute old is from a killed script.
  find "$LOCK.clearing" -maxdepth 0 -mmin +1 -exec rmdir {} \; 2>/dev/null || true
  mkdir "$LOCK.clearing" 2>/dev/null || return 0
  if [ "$(readlink "$LOCK" 2>/dev/null || true)" = "$1" ]; then
    rm -f "$LOCK"
  fi
  rmdir "$LOCK.clearing"
}

# Takes the lock, waiting for a script that holds it. Released when this script exits, however it
# exits. Call it once the prerequisites are known to be met and before starting or stopping anything.
acquire_lock() {
  local me holder problem waited=0 told=0
  [ -w "$RUN_DIR" ] || fail "cannot write to $RUN_DIR"
  problem="$(ps_problem)"
  [ -z "$problem" ] || fail "$problem"
  me="$$ $(process_started "$$")"
  while ! ln -s "$me" "$LOCK" 2>/dev/null; do
    holder="$(readlink "$LOCK" 2>/dev/null || true)"
    if [ -n "$holder" ] && ! is_same_process "$holder"; then
      clear_abandoned_lock "$holder"
    elif [ -n "$holder" ] && [ "$told" -eq 0 ]; then
      printf 'waiting for another of these scripts to finish (process %s)\n' "${holder%% *}" >&2
      told=1
    fi
    if [ "$waited" -ge "$LOCK_WAIT_SECONDS" ]; then
      fail "another of these scripts${holder:+ (process ${holder%% *})} is still running after $((LOCK_WAIT_SECONDS / 60)) minutes. Nothing was done. If none is running, delete ${LOCK#"$ROOT"/} and try again."
    fi
    sleep 1
    waited=$((waited + 1))
  done
  LOCK_HOLDER="$me"
  trap release_lock EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  trap 'exit 129' HUP
}

# Records a process these scripts started: its id, and when it started.
record_pid() {
  printf '%s %s' "$2" "$(process_started "$2")" >"$(pid_file "$1")"
}

# The pid of a service these scripts started and that is still that process; nothing otherwise.
#
# A pid file can outlive its process (the machine lost power, the process was killed), and by then
# its number may belong to something else. So a number alone is never trusted: unless the process
# with that id also started when the file says, the file is a leftover and is removed, and the
# process it happens to name is left alone.
running_pid() {
  local file recorded pid
  file="$(pid_file "$1")"
  [ -f "$file" ] || return 0
  recorded="$(cat "$file" 2>/dev/null || true)"
  pid="${recorded%% *}"
  if is_same_process "$recorded"; then
    printf '%s' "$pid"
    return 0
  fi
  if [ -n "$(process_started "$pid")" ]; then
    printf 'note: %s was left by an earlier run. Process %s is not something these scripts started and is left alone.\n' \
      "${file#"$ROOT"/}" "$pid" >&2
  fi
  rm -f "$file"
}

# Stops a process these scripts started, and its children. Only ever a process we recorded, and
# only while it is still that process: one that merely holds the same port, or has since been given
# the same process id, belongs to someone else.
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

# Waits until the URL answers 200, the process just started (its id is the fourth argument) dies,
# or the time runs out.
wait_until_up() {
  local name="$1" url="$2" seconds="$3" pid="$4" waited=0
  while [ "$waited" -lt "$seconds" ]; do
    if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url" 2>/dev/null)" = "200" ]; then
      return 0
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
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

postgres_is_paused() {
  [ -n "$(compose ps --status paused --quiet postgres 2>/dev/null)" ]
}

# Thaws a paused PostgreSQL, and does nothing when it is not paused. Done before anything else is
# done with the container: Docker refuses to start a paused one, and stopping or removing one as it
# is kills the database where it stands instead of letting it shut down.
thaw_postgres() {
  postgres_is_paused || return 0
  compose unpause postgres >>"$LOG_DIR/postgres.log" 2>&1 \
    || { tail -n 30 "$LOG_DIR/postgres.log" >&2; fail "postgres is paused and could not be resumed. See $LOG_DIR/postgres.log"; }
  say "postgres: resumed"
}

# Runs SQL from standard input in the local database as the owning role, through psql in this
# project's container. Further arguments go to psql.
psql_as_owner() {
  compose exec -T -e PGPASSWORD="$SALON_DB_PASSWORD" postgres \
    psql --no-psqlrc --quiet --set ON_ERROR_STOP=1 --username "$SALON_DB_USER" --dbname "$SALON_DB_NAME" "$@"
}

# Creates the role the API connects as, or brings an existing one into line: it can log in, and it
# is not a superuser, cannot bypass row-level security and owns nothing (infra/postgres/
# application-role.sql). Done whenever PostgreSQL or the API is started, so it holds on a new
# volume and on one made before the role existed. Its name and password are the local-only values
# in .env.example.
ensure_application_role() {
  psql_as_owner \
    --set app_user="$SALON_DB_APP_USER" --set app_password="$SALON_DB_APP_PASSWORD" --set db_name="$SALON_DB_NAME" \
    --file - <"$ROOT/infra/postgres/application-role.sql" >>"$LOG_DIR/postgres.log" 2>&1 \
    || { tail -n 30 "$LOG_DIR/postgres.log" >&2; fail "the application's database role '$SALON_DB_APP_USER' could not be created. See $LOG_DIR/postgres.log"; }
}

start_postgres() {
  thaw_postgres
  if postgres_is_running; then
    ensure_application_role
    say "postgres: already running"
    return 0
  fi
  if [ -z "$(compose ps --all --quiet postgres 2>/dev/null)" ]; then
    refuse_foreign_port postgres "$SALON_POSTGRES_PORT" SALON_POSTGRES_PORT
  fi
  compose up --detach --wait postgres >"$LOG_DIR/postgres.log" 2>&1 \
    || { tail -n 30 "$LOG_DIR/postgres.log" >&2; fail "postgres did not start. See $LOG_DIR/postgres.log"; }
  ensure_application_role
  say "postgres: started"
}

# Stops the container and keeps it, so that starting it again is quick and its data is still there.
stop_postgres() {
  thaw_postgres
  if postgres_is_running; then
    compose stop postgres >>"$LOG_DIR/postgres.log" 2>&1
    say "postgres: stopped"
  else
    say "postgres: not running"
  fi
}

# A hook for tests, not something the product does: freezes PostgreSQL where it stands, with
# `docker pause` on this project's own container and no other. It keeps its port and its open
# connections and answers nothing, which is how a database that is overloaded, or behind a network
# that drops packets without a word, looks to the API. `resume` thaws it, and so does `start`.
pause_postgres() {
  if postgres_is_paused; then
    say "postgres: already paused"
    return 0
  fi
  postgres_is_running \
    || fail "postgres cannot be paused: it is not running. Start it first: infra/scripts/local-service.sh start postgres"
  compose pause postgres >>"$LOG_DIR/postgres.log" 2>&1 \
    || { tail -n 30 "$LOG_DIR/postgres.log" >&2; fail "postgres could not be paused. See $LOG_DIR/postgres.log"; }
  say "postgres: paused; it accepts connections and answers nothing until it is resumed"
}

resume_postgres() {
  if postgres_is_paused; then
    thaw_postgres
  else
    say "postgres: not paused"
  fi
}

# Stops and removes the container, for local-down.sh and local-reset.sh. Arguments go to
# `docker compose down`.
remove_postgres() {
  thaw_postgres
  compose down "$@" >>"$LOG_DIR/postgres.log" 2>&1
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
  if [ "$DATABASE_IS_LOCAL" = "1" ] && postgres_is_running; then
    ensure_application_role
  fi
  say "api: building"
  ( cd "$ROOT/apps/api" && JAVA_HOME="$RESOLVED_JAVA_HOME" ./mvnw -q -DskipTests package ) >"$LOG_DIR/api-build.log" 2>&1 \
    || { tail -n 30 "$LOG_DIR/api-build.log" >&2; fail "the API did not build. See $LOG_DIR/api-build.log"; }
  ( cd "$ROOT/apps/api" && exec nohup "$RESOLVED_JAVA_HOME/bin/java" -jar target/salon-api.jar ) >"$LOG_DIR/api.log" 2>&1 &
  record_pid api "$!"
  wait_until_up api "http://localhost:${SALON_API_PORT}/actuator/health" 90 "$!"
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
  # Served by apps/web/scripts/serve.mjs, which listens on this machine's loopback addresses only
  # (IPv4 and IPv6 both) unless SALON_LISTEN_ON_NETWORK is true.
  if [ "$SALON_WEB_MODE" = "dev" ]; then
    ( cd "$ROOT/apps/web" && exec nohup node scripts/serve.mjs --dev --port "$SALON_WEB_PORT" ) >"$LOG_DIR/web.log" 2>&1 &
  else
    say "web: building"
    ( cd "$ROOT" && pnpm_run --filter @salon/web build ) >>"$LOG_DIR/web-build.log" 2>&1 \
      || { tail -n 30 "$LOG_DIR/web-build.log" >&2; fail "the web app did not build. See $LOG_DIR/web-build.log"; }
    ( cd "$ROOT/apps/web" && exec nohup node scripts/serve.mjs --port "$SALON_WEB_PORT" ) >"$LOG_DIR/web.log" 2>&1 &
  fi
  record_pid web "$!"
  wait_until_up web "http://localhost:${SALON_WEB_PORT}/" 90 "$!"
  say "web: started"
}

stop_web() { stop_process web; }

print_addresses() {
  say ""
  say "PostgreSQL  localhost:${SALON_POSTGRES_PORT}  (database ${SALON_DB_NAME}; owner ${SALON_DB_USER}, application role ${SALON_DB_APP_USER})"
  say "API         http://localhost:${SALON_API_PORT}  (health: http://localhost:${SALON_API_PORT}/actuator/health)"
  say "Web         http://localhost:${SALON_WEB_PORT}"
  if [ "$SPRING_PROFILES_ACTIVE" = "local" ]; then
    say "Salons      alpha, beta, gamma: http://alpha.${SALON_PLATFORM_DOMAIN}:${SALON_WEB_PORT}  (API: http://alpha.${SALON_PLATFORM_DOMAIN}:${SALON_API_PORT}/api/v1/salon)"
  fi
  if [ "$SALON_LISTEN_ON_NETWORK" = "true" ]; then
    say "Listening   on every network interface (SALON_LISTEN_ON_NETWORK=true): anyone on this network can reach the API and the web app"
  else
    say "Listening   on this machine only (set SALON_LISTEN_ON_NETWORK=true to test from a phone on the same network)"
  fi
  say "Logs        ${LOG_DIR}"
}
