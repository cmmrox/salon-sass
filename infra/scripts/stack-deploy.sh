#!/usr/bin/env bash
# Deploys a version to the running stack without a moment of downtime (NFR-18):
#
#   1. Refuses, changing nothing, unless the version's API and web images both exist (pulled first
#      when the images come from a registry) and the stack is running.
#   2. Runs the version's migrations once, while the instances already running keep serving. They
#      must be backward compatible (docs/engineering/backend.md): both versions answer at once
#      until the last instance is replaced.
#   3. Replaces the instances one at a time, the API's before the web app's. Each is stopped (its
#      readiness fails at once and it drains while the edge takes it out of the rotation, then it
#      finishes what it accepted), started again on the new version, and waited for until it is ready
#      and back in the rotation; only then is the next one replaced. Before the first web app
#      instance is replaced, a third one (web-surge) is started on the new version, and it is stopped
#      after the last: a web app instance is one Node.js process, so one alone would be the tier at
#      half its capacity while its partner is replaced (BUG-F01-009). The API's instances each use
#      the whole machine, and one carries the tier while the other is replaced.
#
# Deploying the previous version the same way is the rollback. Its migrations find nothing to do:
# the database stays as the newer version's migrations left it.
#
# Usage: infra/scripts/stack-deploy.sh <version>
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/stack.sh"

# How long after an instance is ready the next one is taken out of the rotation. The edge, which
# checks every second, sends the new instance traffic within a second or two; the rest is for it to
# warm up (a JVM compiling its hot code, a web server its first pages), which costs it and the
# machine CPU for a while. Taking the next one out sooner leaves its partner to carry the whole tier
# while it is still at its slowest (BUG-F01-009).
EDGE_SETTLE_SECONDS=15
# How long a started instance may take to become ready.
READY_TIMEOUT_SECONDS=300

[ $# -eq 1 ] || fail "usage: infra/scripts/stack-deploy.sh <version>"
version="$1"
require_version_form "$version"

require_docker
load_stack_settings
acquire_stack_lock
[ -f "$STACK_DEPLOYED" ] || fail "nothing is deployed yet. Start the stack first: infra/scripts/stack-up.sh"
require_settings

api_image="$(image_ref api "$version")"
web_image="$(image_ref web "$version")"
missing=()
ensure_image "$api_image" || missing+=("$api_image")
ensure_image "$web_image" || missing+=("$web_image")
if [ "${#missing[@]}" -gt 0 ]; then
  fail "version $version cannot be deployed: these images do not exist: ${missing[*]}. Nothing was changed; the stack keeps serving what it served."
fi

for service in postgres edge "${STACK_INSTANCES[@]}"; do
  container_running "$service" || fail "salon-stack-$service is not running. Start the stack with infra/scripts/stack-up.sh and deploy again. Nothing was changed."
done

started=$(date +%s)
previous_migrate="$(deployed_version migrate)"
say "deploy: version $version (running: api $(deployed_version api-1)/$(deployed_version api-2), web $(deployed_version web-1)/$(deployed_version web-2))"

say "migrations: version $version, while every instance keeps serving"
set_deployed_version migrate "$version"
log="$STACK_LOG_DIR/migrate-$version.log"
if ! stack_compose --profile migrate run --rm --no-deps migrate >"$log" 2>&1; then
  set_deployed_version migrate "$previous_migrate"
  tail -n 30 "$log" >&2
  fail "the migrations of version $version failed (see .stack/logs/migrate-$version.log). No instance was replaced; the stack keeps serving what it served."
fi

# web-surge exists only while a deployment runs: one left by an earlier deployment that was
# interrupted is removed now, whatever this one replaces, and this one removes its own when it ends,
# failed or not, so that no instance on another version stays in the rotation.
remove_surge() {
  stack_compose --profile surge stop --timeout 45 web-surge >>"$STACK_LOG_DIR/deploy.log" 2>&1 || true
  stack_compose --profile surge rm --force web-surge >>"$STACK_LOG_DIR/deploy.log" 2>&1 || true
}
if docker inspect salon-stack-web-surge >/dev/null 2>&1; then
  say "web-surge: left by an earlier deployment; draining and removing it"
  remove_surge
fi
surge=0
end_deploy() {
  local status=$?
  if [ "$surge" -eq 1 ]; then
    say "web-surge: draining and removing"
    remove_surge
  fi
  rm -rf "$STACK_LOCK"
  exit "$status"
}
trap end_deploy EXIT

# Starts <service> on the new version and waits until it is ready and settled in the rotation.
start_instance() { # <service> [compose options...]
  local service="$1"; shift
  set_deployed_version "$service" "$version"
  say "$service: starting $version"
  if ! stack_compose "$@" up --detach --no-deps "$service" >>"$STACK_LOG_DIR/deploy.log" 2>&1 \
      || ! wait_until_ready "$service" "$READY_TIMEOUT_SECONDS"; then
    tail -n 30 "$STACK_LOG_DIR/deploy.log" >&2
    fail "$service did not become ready on version $version (docker logs salon-stack-$service). The instances not yet replaced keep serving; deploy the previous version to roll back."
  fi
  sleep "$EDGE_SETTLE_SECONDS"
}

for service in "${STACK_INSTANCES[@]}"; do
  if [ "$(deployed_version "$service")" = "$version" ] && container_running "$service"; then
    say "$service: already $version"
    continue
  fi
  if [[ "$service" == web-* ]] && [ "$surge" -eq 0 ]; then
    instance_started=$(date +%s)
    surge=1
    start_instance web-surge --profile surge
    say "web-surge: ready on $version ($(( $(date +%s) - instance_started )) s); three web app instances serve"
  fi
  instance_started=$(date +%s)
  say "$service: draining and stopping"
  stack_compose stop --timeout 45 "$service" >>"$STACK_LOG_DIR/deploy.log" 2>&1
  start_instance "$service"
  say "$service: ready on $version ($(( $(date +%s) - instance_started )) s)"
done

if [ "$surge" -eq 1 ]; then
  say "web-surge: draining and removing"
  remove_surge
  surge=0
fi

say "deploy: version $version is running everywhere ($(( $(date +%s) - started )) s)"
