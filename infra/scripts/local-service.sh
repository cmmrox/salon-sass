#!/usr/bin/env bash
# Starts or stops one service of the local environment, so that a test can take a dependency
# away and bring it back.
#
# Usage: infra/scripts/local-service.sh <start|stop> <postgres|api|web>
#        infra/scripts/local-service.sh <pause|resume> postgres
#
#   stop postgres    stops the container and keeps its data
#   start api        builds the API if needed and starts it; needs postgres to be running
#   start web        builds the web app and starts it; it starts whether or not the API is up
#   pause postgres   for tests: freezes PostgreSQL, which then accepts connections and answers
#                    nothing (`docker pause` on this project's container only)
#   resume postgres  thaws it again; `start postgres` does the same
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

usage() {
  printf 'usage: %s <start|stop> <postgres|api|web>\n' "$(basename "$0")" >&2
  printf '       %s <pause|resume> postgres\n' "$(basename "$0")" >&2
  exit 2
}

[ "$#" -eq 2 ] || usage
action="$1"
service="$2"
case "$action $service" in
  "start postgres"|"start api"|"start web") ;;
  "stop postgres"|"stop api"|"stop web") ;;
  "pause postgres"|"resume postgres") ;;
  *) usage ;;
esac

load_settings
if [ "$action" = "start" ] || [ "$service" = "postgres" ]; then
  require_prerequisites "$service"
fi
acquire_lock

"${action}_${service}"
