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

# --- the database -----------------------------------------------------------------------------

# psql_value <sql>: one value from the local database, read the way the handoff says to read it.
psql_value() {
  docker exec "$POSTGRES_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -At -v ON_ERROR_STOP=1 -c "$1" 2>&1
}

# --- a copy of the committed tree -------------------------------------------------------------

# committed_copy <directory> [path...]: writes the committed tree (HEAD), or the named paths of it,
# into an empty directory outside the repository. A check alters files there, never here.
committed_copy() {
  local target="$1"; shift
  mkdir -p "$target"
  git -C "$REPO_ROOT" archive HEAD "$@" | tar -x -C "$target" \
    || environment_fault "could not copy the committed tree to $target"
}
