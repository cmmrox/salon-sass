#!/usr/bin/env bash
# Shared by the shell checks under qa/checks/. Sourced, never run.
#
# A check prints one line per case (PASS or FAIL, the case id, what was found), exits non-zero when
# any case failed, and leaves the environment as it found it. A fault of the environment (the
# product is not running, a tool is missing) is reported as ENVIRONMENT and exits 3, so that it is
# never mistaken for a failure of the product or for a pass.
#
# Addresses come from here and nowhere else: a variable in the shell, then qa/.env.local, then the
# root .env the product's own scripts read, then the product's documented defaults.

QA_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$QA_ROOT/.." && pwd)"
SCRIPTS="$REPO_ROOT/infra/scripts"
# The checkout whose scripts are under test, and where those scripts keep their process ids and
# their lock. Derived from SCRIPTS, so that a check run against another checkout's scripts (to see
# it fail on an older version of them) looks at that checkout's state and not this one's.
PRODUCT_ROOT="$(cd "$SCRIPTS/../.." && pwd)"
RUN_DIR="$PRODUCT_ROOT/.local/run"

_setting() {
  local name="$1" file value
  if [ -n "${!name:-}" ]; then printf '%s' "${!name}"; return; fi
  for file in "$QA_ROOT/.env.local" "$REPO_ROOT/.env"; do
    [ -f "$file" ] || continue
    value="$(sed -n "s/^[[:space:]]*${name}[[:space:]]*=[[:space:]]*//p" "$file" | tail -n 1)"
    value="${value%\"}"; value="${value#\"}"
    if [ -n "$value" ]; then printf '%s' "$value"; return; fi
  done
  printf '%s' "${2:-}"
}

API_PORT="$(_setting SALON_API_PORT 8090)"
WEB_PORT="$(_setting SALON_WEB_PORT 3100)"
POSTGRES_PORT="$(_setting SALON_POSTGRES_PORT 5440)"
DB_NAME="$(_setting SALON_DB_NAME salon)"
DB_USER="$(_setting SALON_DB_USER salon)"
# The application's role: what the API connects as. Its name and local-only password are in the
# product's .env.example.
DB_APP_USER="$(_setting SALON_DB_APP_USER salon_app)"
DB_APP_PASSWORD="$(_setting SALON_DB_APP_PASSWORD salon-app-local-only)"
API_URL="$(_setting QA_API_URL "http://localhost:${API_PORT}")"; API_URL="${API_URL%/}"
WEB_URL="$(_setting QA_WEB_URL "http://localhost:${WEB_PORT}")"; WEB_URL="${WEB_URL%/}"
POSTGRES_CONTAINER="salon-postgres"

# Where this run keeps its evidence. qa/scripts/sdlc run sets QA_REPORT_DIR.
REPORT_DIR="${QA_REPORT_DIR:-$QA_ROOT/reports/adhoc}"
mkdir -p "$REPORT_DIR"

FAILED_CASES=0
CURRENT_CASE=""
CURRENT_TITLE=""
CURRENT_FAILED=0

# Scoped synthetic nonlocal startup fixture. Never export keys into missing-secret negative cases.
with_identity_keys() (
  local file="${SALON_IDENTITY_LOCAL_KEY_FILE:-$PRODUCT_ROOT/.local/identity-keys.properties}" key value name count=0
  [ -f "$file" ] || { printf 'ENVIRONMENT: retained synthetic identity key file is absent\n' >&2; exit 3; }
  while IFS='=' read -r key value; do
    case "$key" in signing) name=SALON_IDENTITY_SIGNING_KEY;; challenge) name=SALON_IDENTITY_CHALLENGE_KEY;; encryption) name=SALON_IDENTITY_ENCRYPTION_KEY;; proxy) name=SALON_IDENTITY_PROXY_KEY;; *) continue;; esac
    [[ "$value" =~ ^[0-9a-fA-F]{64}$ ]] || { printf 'ENVIRONMENT: retained identity key format is invalid\n' >&2; exit 3; }
    export "$name=$value"; count=$((count+1))
  done < "$file"
  [ "$count" = 4 ] || { printf 'ENVIRONMENT: all four retained identity fixture keys are required\n' >&2; exit 3; }
  "$@"
)

note() { printf '      %s\n' "$*"; }

# A fault of the environment, not of the product. Nothing more is tested.
environment_fault() {
  printf 'ENVIRONMENT: %s\n' "$*" >&2
  exit 3
}

require_tool() {
  local tool
  for tool in "$@"; do
    command -v "$tool" >/dev/null 2>&1 || environment_fault "the tool '$tool' is needed by this check and is not on PATH"
  done
}

begin_case() {
  end_case
  CURRENT_CASE="$1"; CURRENT_TITLE="$2"; CURRENT_FAILED=0
  printf -- '--- %s %s\n' "$CURRENT_CASE" "$CURRENT_TITLE"
}

end_case() {
  [ -n "$CURRENT_CASE" ] || return 0
  if [ "$CURRENT_FAILED" -eq 0 ]; then
    printf 'PASS %s %s\n' "$CURRENT_CASE" "$CURRENT_TITLE"
  else
    printf 'FAIL %s %s\n' "$CURRENT_CASE" "$CURRENT_TITLE"
    FAILED_CASES=$((FAILED_CASES + 1))
  fi
  CURRENT_CASE=""
}

# One assertion failed. The case goes on, so that everything wrong is reported in one run.
wrong() {
  CURRENT_FAILED=1
  printf '  WRONG: %s\n' "$*"
}

right() { printf '  ok: %s\n' "$*"; }

# expect_equal <what> <expected> <actual>
expect_equal() {
  if [ "$2" = "$3" ]; then right "$1 is '$3'"; else wrong "$1: expected '$2', got '$3'"; fi
}

# expect_not_equal <what> <unexpected> <actual>
expect_not_equal() {
  if [ "$2" != "$3" ]; then right "$1 is '$3', not '$2'"; else wrong "$1: must not be '$2'"; fi
}

# expect_contains <what> <needle (fixed text)> <haystack>
expect_contains() {
  if printf '%s' "$3" | grep -qF -- "$2"; then right "$1 contains '$2'"; else wrong "$1 does not contain '$2'. It was: $(printf '%s' "$3" | head -c 600)"; fi
}

# expect_matches <what> <extended regex, case-insensitive> <haystack>
expect_matches() {
  if printf '%s' "$3" | grep -qiE -- "$2"; then right "$1 matches /$2/"; else wrong "$1 does not match /$2/. It was: $(printf '%s' "$3" | head -c 600)"; fi
}

# expect_no_match <what> <extended regex, case-insensitive> <haystack>
expect_no_match() {
  local found
  found="$(printf '%s' "$3" | grep -oiE -- "$2" | head -n 1 || true)"
  if [ -z "$found" ]; then right "$1 shows none of /$2/"; else wrong "$1 shows '$found'"; fi
}

# What an error must never show a caller: how the product is built.
INTERNALS_RE='exception|stack ?trace|\bat [a-z0-9_.$]+\(|org\.(springframework|apache|hibernate|postgresql|flywaydb)|com\.(salonplatform|zaxxer)|java(x)?\.[a-z]+\.|jakarta\.|jdbc|hikari|tomcat|spring|hibernate|flyway|postgres|[0-9]+\.[0-9]+\.[0-9]+|localhost:[0-9]+|/Users/|/home/'

finish() {
  end_case
  if [ "$FAILED_CASES" -gt 0 ]; then
    printf '\n%s case(s) FAILED\n' "$FAILED_CASES"
    exit 1
  fi
  printf '\nall cases passed\n'
  exit 0
}

# --- HTTP -------------------------------------------------------------------------------------

# Prints the HTTP status of a GET, or 000 when nothing answered within the time (default 10 s).
http_code() {
  curl -s -o /dev/null -w '%{http_code}' --max-time "${2:-10}" "$1" 2>/dev/null || true
}

# timed_get <url> <body file> <max seconds>: prints "<status> <seconds> <content type>".
timed_get() {
  curl -s -o "$2" -w '%{http_code} %{time_total} %{content_type}' --max-time "${3:-15}" "$1" 2>/dev/null || true
}

# wait_for_code <url> <status> <seconds>: waits for the thing itself, never a fixed time.
wait_for_code() {
  local waited=0
  while [ "$waited" -lt "$3" ]; do
    [ "$(http_code "$1" 3)" = "$2" ] && return 0
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

port_open() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# wait_for_port_closed <port> <seconds>
wait_for_port_closed() {
  local waited=0
  while [ "$waited" -lt "$2" ]; do
    port_open "$1" || return 0
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

# The process id listening on a port, or nothing.
listener_pid() { lsof -nP -iTCP:"$1" -sTCP:LISTEN -t 2>/dev/null | head -n 1 || true; }

product_is_up() {
  [ "$(http_code "$API_URL/actuator/health" 5)" = "200" ] && [ "$(http_code "$WEB_URL/" 10)" = "200" ]
}

# A check of the running product can only fail meaningfully if the product was there to begin with.
require_product_up() {
  product_is_up || environment_fault "the product is not running (health at $API_URL/actuator/health answered $(http_code "$API_URL/actuator/health" 5), the web app at $WEB_URL/ answered $(http_code "$WEB_URL/" 10)). Start it with infra/scripts/local-up.sh and run again."
}

# The process id a pid file of the scripts names (the first word of the file), or nothing.
recorded_pid() { [ -f "$RUN_DIR/$1.pid" ] && cut -d' ' -f1 <"$RUN_DIR/$1.pid" || true; }

# Processes running the API's jar or the web app's server, one id per line.
product_processes() { pgrep -f 'salon-api\.jar|next-server|next start' || true; }

# path_without <directory> <tool...>: fills the directory with links to every program in /usr/bin,
# /bin, /usr/sbin and /sbin except the named ones, plus node, corepack and docker from this PATH,
# so that a script can be run on "this machine, without curl" (or without ps).
path_without() {
  local target="$1" dir file name tool skip; shift
  mkdir -p "$target"
  for dir in /usr/bin /bin /usr/sbin /sbin; do
    for file in "$dir"/*; do
      name="${file##*/}"; skip=0
      for tool in "$@"; do [ "$name" = "$tool" ] && skip=1; done
      [ "$skip" = 1 ] || [ -e "$target/$name" ] || ln -s "$file" "$target/$name"
    done
  done
  for tool in node corepack docker; do
    skip=0; for name in "$@"; do [ "$name" = "$tool" ] && skip=1; done
    [ "$skip" = 1 ] || { command -v "$tool" >/dev/null 2>&1 && ln -sf "$(command -v "$tool")" "$target/$tool"; }
  done
}

# --- the database -----------------------------------------------------------------------------

# psql_value <sql>: one value from the local database, read the way the handoff says to read it.
psql_value() {
  docker exec "$POSTGRES_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -At -v ON_ERROR_STOP=1 -c "$1" 2>&1
}

# Both roles are reached the way the handoff of F01-S02 says: through psql in the container. The
# output is what psql printed, rows unaligned and errors included, so a refusal can be asserted.

# owner_psql: runs the SQL on standard input as the owning role, statement by statement.
owner_psql() {
  docker exec -i "$POSTGRES_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -X -At -q 2>&1
}

# app_psql: runs the SQL on standard input as the application's role, over a password login.
app_psql() {
  docker exec -i -e PGPASSWORD="$DB_APP_PASSWORD" "$POSTGRES_CONTAINER" \
    psql -h localhost -U "$DB_APP_USER" -d "$DB_NAME" -X -At -q 2>&1
}

# as_salon <tenant id | none> <sql>: the SQL as the application's role, in one transaction with the
# tenant set for that transaction only (or with none), which is then rolled back whatever happened.
as_salon() {
  local setting=""
  [ "$1" = none ] || setting="select set_config('app.tenant_id', '$1', true) \\g /dev/null"
  printf 'begin;\n%s\n%s\n;\nrollback;\n' "$setting" "$2" | app_psql
}

# owner_tx <sql>: the SQL as the owning role in one transaction that is rolled back whatever
# happened. For proving a point that needs a grant or an owner the product does not have, without
# changing the product: nothing the SQL does outlives it or is seen by any other connection.
owner_tx() {
  printf 'begin;\n%s\n;\nrollback;\n' "$1" | owner_psql
}

# require_database: the database answers both roles; otherwise nothing about it can be tested.
require_database() {
  [ "$(printf 'select 1;\n' | owner_psql)" = 1 ] \
    || environment_fault "the owning role cannot reach the database through the $POSTGRES_CONTAINER container: $(printf 'select 1;\n' | owner_psql | head -n 2)"
  [ "$(printf 'select 1;\n' | app_psql)" = 1 ] \
    || environment_fault "the application's role ($DB_APP_USER) cannot log in to the database: $(printf 'select 1;\n' | app_psql | head -n 2)"
}

# --- the synthetic salons ---------------------------------------------------------------------

# load_salons: the salons of docs/qa/test-plan.md, from shared/tenants.ts and nowhere else. Sets
# SALON_SLUGS, <SLUG>_ID, <SLUG>_SLUG, <SLUG>_NAME, <SLUG>_COLOUR, <SLUG>_HOST for each salon,
# NIL_TENANT_ID, PLATFORM_DOMAIN and RESERVED_SLUGS.
load_salons() {
  local assignments
  assignments="$(NODE_NO_WARNINGS=1 node "$QA_ROOT/shared/tenants-shell.mjs" 2>&1)" \
    || environment_fault "could not read the salons from shared/tenants.ts: $assignments"
  eval "$assignments"
  [ -n "${SALON_SLUGS:-}" ] || environment_fault "shared/tenants.ts gave no salons"
}

# salon_value <slug> <ID|SLUG|NAME|COLOUR|HOST>
salon_value() {
  local name; name="$(printf '%s_%s' "$1" "$2" | tr '[:lower:]' '[:upper:]')"
  printf '%s' "${!name}"
}

# on_hostname <service url> <hostname>: http://localhost:8090 and alpha.localhost give
# http://alpha.localhost:8090.
on_hostname() {
  printf '%s' "$1" | sed -E "s#^([a-z][a-z0-9+.-]*://)(\\[[^]]*\\]|[^/:?\#]*)#\\1$2#"
}

# salon_api <slug>: where that salon's brand is served.
salon_api() { printf '%s/api/v1/salon' "$(on_hostname "$API_URL" "$(salon_value "$1" HOST)")"; }

# now_ms: the time in milliseconds.
now_ms() { node -e 'process.stdout.write(String(Date.now()))'; }

# --- a copy of the committed tree -------------------------------------------------------------

# committed_copy <directory> [path...]: writes the committed tree (HEAD), or the named paths of it,
# into an empty directory outside the repository. A check alters files there, never here.
committed_copy() {
  local target="$1"; shift
  mkdir -p "$target"
  git -C "$REPO_ROOT" archive HEAD "$@" | tar -x -C "$target" \
    || environment_fault "could not copy the committed tree to $target"
}
