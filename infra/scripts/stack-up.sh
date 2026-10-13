#!/usr/bin/env bash
# Starts the production-like stack (infra/stack): PostgreSQL, the database's two roles, the
# migrations of the version deployed, the collector, two API instances, two web app instances and
# the edge, and waits until every instance is ready. Safe to run again: what is running is left alone.
#
# Usage: infra/scripts/stack-up.sh
#
# Reads .env.stack (copy .env.stack.example). Refuses to start, naming the setting, when one is
# missing, and starts nothing when an image is missing or a port is taken. No salon exists until
# infra/scripts/stack-seed.sh creates the synthetic ones.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/stack.sh"

require_docker
load_stack_settings
acquire_stack_lock
ensure_deployed_file
require_settings

# Every image the stack will run is here before anything starts.
missing=()
for service in migrate "${STACK_INSTANCES[@]}"; do
  kind="api"; [[ "$service" == web-* ]] && kind="web"
  image="$(image_ref "$kind" "$(deployed_version "$service")")"
  ensure_image "$image" || missing+=("$image")
done
if [ "${#missing[@]}" -gt 0 ]; then
  fail "these images do not exist: ${missing[*]}. Build them with infra/scripts/images-build.sh <version>. Nothing was started."
fi

# The edge's ports are free, unless the stack's own edge already holds them.
if ! container_running edge; then
  for setting in SALON_EDGE_HTTPS_PORT SALON_EDGE_HTTP_PORT; do
    if port_in_use "${!setting}"; then
      fail "port ${!setting} is already in use by something else. Free it, or set $setting in .env.stack to another port. Nothing was started."
    fi
  done
fi

mkdir -p "$STACK_TELEMETRY_DIR"
# The collector runs as its own unprivileged user and writes here.
chmod 1777 "$STACK_TELEMETRY_DIR"

say "postgres: starting"
stack_compose up --detach --wait postgres >"$STACK_LOG_DIR/up.log" 2>&1 \
  || { tail -n 30 "$STACK_LOG_DIR/up.log" >&2; fail "PostgreSQL did not start. See .stack/logs/up.log"; }

say "postgres: the owning role and the application's role (infra/postgres/provision-roles.sql)"
stack_compose \
  SALON_DB_NAME="$(file_setting SALON_DB_NAME)" \
  SALON_DB_USER="$(file_setting SALON_DB_USER)" SALON_DB_PASSWORD="$(file_setting SALON_DB_PASSWORD)" \
  SALON_DB_APP_USER="$(file_setting SALON_DB_APP_USER)" SALON_DB_APP_PASSWORD="$(file_setting SALON_DB_APP_PASSWORD)" \
  exec -T -e SALON_DB_NAME -e SALON_DB_USER -e SALON_DB_PASSWORD -e SALON_DB_APP_USER -e SALON_DB_APP_PASSWORD \
  postgres sh -c 'psql --no-psqlrc --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --file -' \
  <"$ROOT/infra/postgres/provision-roles.sql" >>"$STACK_LOG_DIR/up.log" 2>&1 \
  || { tail -n 30 "$STACK_LOG_DIR/up.log" >&2; fail "the database's roles could not be provisioned. See .stack/logs/up.log"; }

version="$(deployed_version migrate)"
say "migrations: version $version"
stack_compose --profile migrate run --rm --no-deps migrate >"$STACK_LOG_DIR/migrate-$version.log" 2>&1 \
  || { tail -n 30 "$STACK_LOG_DIR/migrate-$version.log" >&2; fail "the migrations of version $version failed. See .stack/logs/migrate-$version.log"; }

say "collector, API, web app and edge: starting"
stack_compose up --detach --wait --wait-timeout 300 collector "${STACK_INSTANCES[@]}" edge >>"$STACK_LOG_DIR/up.log" 2>&1 \
  || { tail -n 30 "$STACK_LOG_DIR/up.log" >&2; fail "the stack did not become ready. See .stack/logs/up.log and docker logs salon-stack-<service>"; }

if [ "$SALON_EDGE_CERTIFICATES" = "internal" ]; then
  # The stack's own certificate authority: its root, for the tests that must trust it.
  for _ in $(seq 1 60); do
    if stack_compose cp edge:/data/caddy/pki/authorities/local/root.crt "$STACK_ROOT_CERT" >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  [ -s "$STACK_ROOT_CERT" ] || fail "the edge did not create its certificate authority. See docker logs salon-stack-edge"
fi

https="https://${SALON_PLATFORM_DOMAIN}:${SALON_EDGE_HTTPS_PORT}"
say ""
say "Stack       ${https}  (status page)"
say "Salons      https://<slug>.${SALON_PLATFORM_DOMAIN}:${SALON_EDGE_HTTPS_PORT}  (none until infra/scripts/stack-seed.sh)"
say "HTTP        http://${SALON_PLATFORM_DOMAIN}:${SALON_EDGE_HTTP_PORT}  (redirects to HTTPS)"
say "Versions    api $(deployed_version api-1)/$(deployed_version api-2), web $(deployed_version web-1)/$(deployed_version web-2)"
if [ "$SALON_EDGE_CERTIFICATES" = "internal" ]; then
  say "Trust       .stack/edge-root.crt  (curl --cacert .stack/edge-root.crt ${https}/api/v1/meta)"
fi
say "Telemetry   .stack/telemetry  (traces.jsonl, metrics.jsonl); logs: docker logs salon-stack-<service>"
