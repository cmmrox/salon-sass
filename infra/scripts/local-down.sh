#!/usr/bin/env bash
# Stops the product on this machine: the web app, the API, then PostgreSQL. The database container
# is removed and its data is kept (the volume stays), so the next local-up.sh finds the same data.
# local-reset.sh is the one that deletes it.
#
# Usage: infra/scripts/local-down.sh
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

load_settings

stop_web
stop_api
problem="$(docker_problem)"
if [ -n "$problem" ]; then
  say "postgres: left as it is ($problem)"
else
  compose down >>"$LOG_DIR/postgres.log" 2>&1
  say "postgres: stopped and removed; its data is kept"
fi
