#!/usr/bin/env bash
# Starts again from nothing: stops everything, deletes the local database's data, and starts the
# product, so the API applies every migration to an empty database.
#
# Usage: infra/scripts/local-reset.sh
#        SALON_LOCAL_INSTANCE=qa infra/scripts/local-reset.sh    the QA instance's database instead
#
# Only the chosen instance's own database volume is deleted (salon-postgres-data, or
# salon-qa-postgres-data for the QA instance); it says which before it starts. The other instance
# and everything outside this project are not touched.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

load_settings
require_prerequisites postgres api web
acquire_lock
describe_instance
require_own_containers

stop_web
stop_api
remove_postgres --volumes
say "postgres: stopped; its data is deleted"

start_postgres
start_api
start_web
print_addresses
