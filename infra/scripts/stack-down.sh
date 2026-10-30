#!/usr/bin/env bash
# Stops the production-like stack and removes its containers and networks. Its data is kept unless
# --delete-data is given, which removes the stack's volumes too, so the next start is empty.
#
# Usage: infra/scripts/stack-down.sh [--delete-data]
#
# Touches the salon-stack project only: never the local environment, never another project.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/stack.sh"

delete_data=0
case "${1:-}" in
  '') ;;
  --delete-data) delete_data=1 ;;
  *) fail "usage: infra/scripts/stack-down.sh [--delete-data]" ;;
esac

require_docker
require_env_file
load_stack_settings
acquire_stack_lock

if [ ! -f "$STACK_DEPLOYED" ]; then
  # Nothing deployed yet; the file only names versions, which `down` does not use.
  ensure_deployed_file
fi

# The surge profile too, so that a web-surge left by an interrupted deployment stops with the rest.
args=(--profile migrate --profile surge down --remove-orphans --timeout 45)
[ "$delete_data" -eq 1 ] && args+=(--volumes)
stack_compose "${args[@]}"

if [ "$delete_data" -eq 1 ]; then
  rm -f "$STACK_DEPLOYED"
  rm -rf "$STACK_TELEMETRY_DIR"
  say "stack: stopped, and its data deleted"
else
  say "stack: stopped (its data is kept; --delete-data removes it)"
fi
