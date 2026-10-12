#!/usr/bin/env bash
# Starts the product on this machine: PostgreSQL in Docker, then the API, then the web app.
# Safe to run again: whatever is already running is left alone. Safe to run twice at once, too:
# these scripts take turns, so a second one waits for the first and then finds everything running.
#
# Usage: infra/scripts/local-up.sh
#
# Checks the prerequisites first (Docker running, JDK 25, Node.js with pnpm through Corepack) and
# exits non-zero, naming what is missing, before starting anything.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

load_settings
require_prerequisites postgres api web
acquire_lock

start_postgres
start_api
start_web
print_addresses
