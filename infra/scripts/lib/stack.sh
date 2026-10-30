#!/usr/bin/env bash
# Shared by the stack-*.sh and images-*.sh scripts. Sourced, never run.
#
# The production-like stack (infra/stack) is a Compose project of its own, `salon-stack`. Its
# settings come from .env.stack at the repository root (.env.stack.example lists them) and from
# nothing else: Compose is run with an empty environment, so neither the shell's variables nor the
# local environment's .env can reach it. What these scripts deployed is in .stack/deployed.env.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
STACK_PROJECT="salon-stack"
STACK_COMPOSE_FILE="$ROOT/infra/stack/compose.yaml"
STACK_ENV_FILE="$ROOT/.env.stack"
STACK_STATE_DIR="$ROOT/.stack"
STACK_DEPLOYED="$STACK_STATE_DIR/deployed.env"
STACK_LOG_DIR="$STACK_STATE_DIR/logs"
STACK_TELEMETRY_DIR="$STACK_STATE_DIR/telemetry"
# The instances a deployment replaces, in the order it replaces them: the API before the web app, so
# that a new web app never calls an old API.
STACK_INSTANCES=(api-1 api-2 web-1 web-2)
# The images' names, before the registry prefix.
API_IMAGE="salon-api"
WEB_IMAGE="salon-web"

# Tools run from their official images, pinned (decision 030). Nothing is installed on the machine.
TRIVY_IMAGE="aquasec/trivy:0.75.0"
ACTIONLINT_IMAGE="rhysd/actionlint:1.7.12"

say()  { printf '%s\n' "$*"; }
fail() { printf 'error: %s\n' "$*" >&2; exit 1; }

require_docker() {
  command -v docker >/dev/null 2>&1 || fail "Docker is not installed (the 'docker' command was not found)."
  docker info >/dev/null 2>&1 || fail "Docker is not running. Start Docker and try again."
  docker compose version >/dev/null 2>&1 || fail "Docker Compose is missing ('docker compose' does not work). It ships with current Docker."
}

# --- versions --------------------------------------------------------------------------------------

# The development version: the API build's own (apps/api/pom.xml, <revision>).
default_version() {
  sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' "$ROOT/apps/api/pom.xml" | head -n 1
}

# A version is an image tag: letters, digits, dots, hyphens and underscores, beginning with a letter
# or a digit, at most 128 characters.
require_version_form() {
  [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]] \
    || fail "'$1' is not a version: use letters, digits, dots, hyphens and underscores, beginning with a letter or a digit."
}

image_ref() { # <api|web> <version>
  local name="$API_IMAGE"
  [ "$1" = "web" ] && name="$WEB_IMAGE"
  printf '%s%s:%s' "${SALON_IMAGE_PREFIX:-}" "$name" "$2"
}

image_exists() { docker image inspect "$1" >/dev/null 2>&1; }

# --- settings --------------------------------------------------------------------------------------

require_env_file() {
  [ -f "$STACK_ENV_FILE" ] || fail "there is no .env.stack. Copy .env.stack.example to .env.stack and give every secret in it a value (for example: openssl rand -hex 24). Nothing was started."
}

# The value of KEY in a KEY=VALUE file ($2, the stack's environment file by default), as Compose
# reads it: the last line wins, and surrounding quotes are removed.
file_setting() {
  local key="$1" file="${2:-$STACK_ENV_FILE}" value
  [ -f "$file" ] || return 0
  value="$(sed -n "s/^${key}=//p" "$file" | tail -n 1)"
  value="${value%\"}"; value="${value#\"}"
  value="${value%\'}"; value="${value#\'}"
  printf '%s' "$value"
}

load_stack_settings() {
  require_env_file
  SALON_IMAGE_PREFIX="$(file_setting SALON_IMAGE_PREFIX)"
  SALON_PLATFORM_DOMAIN="$(file_setting SALON_PLATFORM_DOMAIN)"
  SALON_STACK_ENV="$(file_setting SALON_STACK_ENV)"
  SALON_STACK_ENV="${SALON_STACK_ENV:-local}"
  case "$SALON_STACK_ENV" in
    local) STACK_EDGE_SERVICE="edge-http" ;;
    staging) STACK_EDGE_SERVICE="edge-staging" ;;
    *) fail "SALON_STACK_ENV must be local or staging (found '$SALON_STACK_ENV')." ;;
  esac
  SALON_EDGE_HTTP_PORT="$(file_setting SALON_EDGE_HTTP_PORT)"
  SALON_EDGE_PUBLISH_ADDRESS="$(file_setting SALON_EDGE_PUBLISH_ADDRESS)"
  mkdir -p "$STACK_STATE_DIR" "$STACK_LOG_DIR"
}

stack_public_url() {
  local host="$1"
  if [ "$SALON_STACK_ENV" = "staging" ]; then
    [ "$(file_setting SALON_WEB_LOCAL_HTTP)" = "false" ] || fail "Staging requires SALON_WEB_LOCAL_HTTP=false"
    printf 'https://%s' "$host"
  else
    printf 'http://%s:%s' "$host" "$SALON_EDGE_HTTP_PORT"
  fi
}

# --- addresses on the edge network ----------------------------------------------------------------

# The fixed addresses of the edge and of every instance on the edge network, and the range Docker
# may give everything else, from SALON_STACK_EDGE_SUBNET: printed as NAME=value lines.
#
# The edge proxies to these addresses and the web app calls the edge at its own, never at a name
# (BUG-F01-009). An instance's name does not exist while it is replaced (and web-surge's while no
# deployment runs), and Docker's DNS then asks the host's resolvers, which could answer with an
# address outside the stack. An address of a stopped instance is simply refused. Docker gives other
# containers addresses from the subnet's lower half only, so none can ever take one of these, which
# are the last of the upper half: the edge's the highest.
edge_ip() { printf '%d.%d.%d.%d' $(( ($1 >> 24) & 255 )) $(( ($1 >> 16) & 255 )) $(( ($1 >> 8) & 255 )) $(( $1 & 255 )); }
edge_addresses() { # [subnet]
  local subnet="${1:-$(file_setting SALON_STACK_EDGE_SUBNET)}" a b c d prefix network size top i name
  if ! [[ "$subnet" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})/([0-9]{1,2})$ ]]; then
    printf 'error: SALON_STACK_EDGE_SUBNET must be an IPv4 range such as 10.89.43.0/24 (found "%s").\n' "$subnet" >&2
    return 1
  fi
  a="${BASH_REMATCH[1]}" b="${BASH_REMATCH[2]}" c="${BASH_REMATCH[3]}" d="${BASH_REMATCH[4]}" prefix="${BASH_REMATCH[5]}"
  if [ "$a" -gt 255 ] || [ "$b" -gt 255 ] || [ "$c" -gt 255 ] || [ "$d" -gt 255 ] || [ "$prefix" -lt 16 ] || [ "$prefix" -gt 26 ]; then
    printf 'error: SALON_STACK_EDGE_SUBNET must be an IPv4 range from /16 to /26 (found "%s").\n' "$subnet" >&2
    return 1
  fi
  size=$(( 1 << (32 - prefix) ))
  network=$(( ((a << 24) | (b << 16) | (c << 8) | d) & ~(size - 1) & 0xFFFFFFFF ))
  if [ "$network" -ne $(( (a << 24) | (b << 16) | (c << 8) | d )) ]; then
    printf 'error: SALON_STACK_EDGE_SUBNET must start at its network address (found "%s").\n' "$subnet" >&2
    return 1
  fi
  printf 'SALON_STACK_EDGE_DYNAMIC_RANGE=%s/%d\n' "$(edge_ip "$network")" $(( prefix + 1 ))
  top=$(( network + size - 2 )) # the last address before the broadcast address
  i=0
  for name in EDGE API_1 API_2 WEB_1 WEB_2 WEB_SURGE; do
    printf 'SALON_STACK_%s_ADDRESS=%s\n' "$name" "$(edge_ip $(( top - i )))"
    i=$(( i + 1 ))
  done
}

# Docker Compose for the stack, with the stack's environment file and the versions deployed, and an
# otherwise empty environment. Leading NAME=value arguments are put in that environment, for a
# command that hands a value to a container by name (`exec -e NAME`) without it appearing on any
# command line.
stack_compose() {
  local -a pass=(PATH="$PATH" HOME="$HOME")
  local name
  for name in DOCKER_HOST DOCKER_CONTEXT DOCKER_CONFIG DOCKER_CERT_PATH DOCKER_TLS_VERIFY; do
    if [ -n "${!name:-}" ]; then pass+=("$name=${!name}"); fi
  done
  while [ $# -gt 0 ] && [[ "$1" == *=* ]] && [[ "$1" != -* ]]; do
    pass+=("$1")
    shift
  done
  # The addresses on the edge network (edge_addresses); without a valid subnet Compose itself says
  # which setting is missing.
  local addresses line
  if addresses="$(edge_addresses 2>/dev/null)"; then
    while IFS= read -r line; do pass+=("$line"); done <<<"$addresses"
  fi
  local -a files=(--profile "$SALON_STACK_ENV" --env-file "$STACK_ENV_FILE")
  [ -f "$STACK_DEPLOYED" ] && files+=(--env-file "$STACK_DEPLOYED")
  env -i "${pass[@]}" docker compose -p "$STACK_PROJECT" -f "$STACK_COMPOSE_FILE" "${files[@]}" "$@"
}

# Refuses to go on when a setting the stack needs is missing, naming it. Compose checks every
# required setting of every service (infra/stack/compose.yaml) before it starts anything.
require_settings() {
  local problem
  edge_addresses >/dev/null || { printf 'Nothing was started. Every setting is described in .env.stack.example.\n' >&2; exit 1; }
  if ! problem="$(stack_compose --profile migrate config --quiet 2>&1)"; then
    # Compose names the setting once for every place it is used; it is said once.
    printf 'error: the stack cannot start: %s\n' "$(printf '%s\n' "$problem" | sed 's/^error while interpolating [^:]*: //' | awk '!seen[$0]++')" >&2
    printf 'Nothing was started. Every setting is described in .env.stack.example.\n' >&2
    exit 1
  fi
  if [ "$SALON_STACK_ENV" = "staging" ]; then
    local setting
    for setting in SALON_EDGE_ACME_EMAIL SALON_EDGE_DNS_PROVIDER SALON_EDGE_DNS_CREDENTIALS; do
      [ -n "$(file_setting "$setting")" ] || fail "the staging edge requires $setting in .env.stack. Nothing was started."
    done
    [ -n "$(file_setting SALON_EDGE_IMAGE)" ] || fail "the staging edge requires SALON_EDGE_IMAGE with the selected DNS provider module in .env.stack. Nothing was started."
  fi
}

# --- what is deployed ------------------------------------------------------------------------------

version_key() { # <service> -> the setting that names its version
  case "$1" in
    api-1) printf 'SALON_API_1_VERSION' ;;
    api-2) printf 'SALON_API_2_VERSION' ;;
    web-1) printf 'SALON_WEB_1_VERSION' ;;
    web-2) printf 'SALON_WEB_2_VERSION' ;;
    web-surge) printf 'SALON_WEB_SURGE_VERSION' ;;
    migrate) printf 'SALON_MIGRATE_VERSION' ;;
    *) fail "no such instance: $1" ;;
  esac
}

deployed_version() { file_setting "$(version_key "$1")" "$STACK_DEPLOYED"; }

# Sets one instance's version in .stack/deployed.env, replacing the file in one step.
set_deployed_version() { # <service> <version>
  local key value tmp
  key="$(version_key "$1")"
  value="$2"
  tmp="$(mktemp "$STACK_STATE_DIR/deployed.XXXXXX")"
  { grep -v "^${key}=" "$STACK_DEPLOYED" 2>/dev/null || true; printf '%s=%s\n' "$key" "$value"; } >"$tmp"
  mv "$tmp" "$STACK_DEPLOYED"
}

# The first start: every instance at SALON_VERSION from the environment file.
ensure_deployed_file() {
  [ -f "$STACK_DEPLOYED" ] && return 0
  local version
  version="$(file_setting SALON_VERSION)"
  [ -n "$version" ] || fail "SALON_VERSION is not set in .env.stack: the stack does not know which version to start (.env.stack.example)."
  require_version_form "$version"
  {
    printf '# The version each instance of the stack runs. Written by infra/scripts/stack-*.sh.\n'
    for service in migrate "${STACK_INSTANCES[@]}"; do
      printf '%s=%s\n' "$(version_key "$service")" "$version"
    done
  } >"$STACK_DEPLOYED"
}

# Makes sure an image is on this machine: pulled from the registry when the images come from one.
# Returns non-zero when it is not there afterwards.
ensure_image() {
  image_exists "$1" && return 0
  if [ -n "${SALON_IMAGE_PREFIX:-}" ]; then
    docker pull --quiet "$1" >/dev/null 2>&1 || true
  fi
  image_exists "$1"
}

# --- running state ---------------------------------------------------------------------------------

container_running() { [ "$(docker inspect -f '{{.State.Running}}' "salon-stack-$1" 2>/dev/null)" = "true" ]; }

# Waits until an instance's health check says it is ready, at most <seconds>. Returns non-zero when
# the time runs out or the container stops. An instance that reports unhealthy on the way is waited
# for still: a new instance is given traffic as soon as it first answers ready, and while it warms
# up under that traffic its health check can time out a few times before it settles. Compose's own
# `--wait` gives up at the first unhealthy report (BUG-F01-009).
wait_until_ready() { # <service> <seconds>
  local service="$1" limit="$2" waited=0 state
  while [ "$waited" -lt "$limit" ]; do
    state="$(docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "salon-stack-$service" 2>/dev/null || true)"
    case "$state" in
      "running healthy") return 0 ;;
      "running "*) ;;
      *) printf 'salon-stack-%s is no longer running (%s)\n' "$service" "${state:-gone}" >>"$STACK_LOG_DIR/deploy.log"; return 1 ;;
    esac
    sleep 1
    waited=$((waited + 1))
  done
  printf 'salon-stack-%s was not ready after %s s (%s)\n' "$service" "$limit" "$state" >>"$STACK_LOG_DIR/deploy.log"
  return 1
}

port_in_use() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

# One of these scripts at a time: a second waits for the first. The lock is a directory holding the
# holder's process id; a lock whose holder is gone is taken over.
STACK_LOCK="$STACK_STATE_DIR/lock"
acquire_stack_lock() {
  local waited=0 holder
  mkdir -p "$STACK_STATE_DIR"
  while ! mkdir "$STACK_LOCK" 2>/dev/null; do
    holder="$(cat "$STACK_LOCK/pid" 2>/dev/null || true)"
    if [ -n "$holder" ] && ! kill -0 "$holder" 2>/dev/null; then
      rm -rf "$STACK_LOCK"
      continue
    fi
    [ "$waited" -eq 0 ] && printf 'waiting for another stack script to finish (process %s)\n' "${holder:-unknown}" >&2
    [ "$waited" -ge 1800 ] && fail "another stack script is still running after 30 minutes. If none is, delete .stack/lock."
    sleep 1
    waited=$((waited + 1))
  done
  printf '%s' "$$" >"$STACK_LOCK/pid"
  trap 'rm -rf "$STACK_LOCK"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
}

# Runs SQL from standard input in the stack's database as the owning role, through psql in the
# stack's PostgreSQL container. The password reaches psql through the environment only. Further
# arguments go to psql.
psql_as_owner() {
  stack_compose PGPASSWORD="$(file_setting SALON_DB_PASSWORD)" exec -T -e PGPASSWORD postgres \
    psql --no-psqlrc --quiet --set ON_ERROR_STOP=1 --host localhost \
    --username "$(file_setting SALON_DB_USER)" --dbname "$(file_setting SALON_DB_NAME)" "$@"
}
