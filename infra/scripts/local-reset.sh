#!/usr/bin/env bash
# Starts again from nothing: stops everything, deletes the local database's data, and starts the
# product, so the API applies every migration to an empty database.
#
# Usage: infra/scripts/local-reset.sh
#
# Only this project's local database volume is deleted. Nothing outside it is touched.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

load_settings
require_prerequisites postgres api web

stop_web
stop_api
compose down --volumes >>"$LOG_DIR/postgres.log" 2>&1
say "postgres: stopped; its data is deleted"

start_postgres
start_api
start_web
print_addresses
