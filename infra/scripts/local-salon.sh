#!/usr/bin/env bash
# A hook for tests, for the local environment only, and not something the product does: suspends
# or reactivates one salon of the local database, so that a test can see what a suspended salon's
# web address shows. Suspending a salon for real is an operator's action and arrives with the
# operator console.
#
# Usage: infra/scripts/local-salon.sh <suspend|activate> <slug>
#
#   suspend gamma    gamma.localhost then answers "temporarily unavailable" on the web app and
#                    403 salon_suspended on the API, on the next request; nothing is restarted
#   activate gamma   gamma answers as before, on the next request
#
# It changes that salon's status in the registry, as the role that owns the tables, and nothing
# else: no other salon, no brand, no other column. It refuses a slug that is not a salon. It only
# ever reaches PostgreSQL in this project's own container (salon-postgres), which must be running.
# local-reset.sh puts everything back: the three synthetic salons, all active.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

usage() {
  printf 'usage: %s <suspend|activate> <slug>\n' "$(basename "$0")" >&2
  exit 2
}

[ "$#" -eq 2 ] || usage
action="$1"
slug="$2"
case "$action" in
  suspend)  wanted="suspended" ;;
  activate) wanted="active" ;;
  *) usage ;;
esac

# A slug is 3 to 30 lowercase letters, digits and hyphens, beginning and ending with a letter or
# digit (docs/architecture/multi-tenancy.md). Anything else cannot be a salon; say so without
# asking the database. The slug reaches SQL as a quoted psql variable in any case, never as text.
not_a_salon() {
  fail "'$slug' is not a salon. Nothing was changed. The local salons are alpha, beta and gamma."
}
case "$slug" in
  *[!a-z0-9-]*|-*|*-|'') not_a_salon ;;
esac
if [ "${#slug}" -lt 3 ] || [ "${#slug}" -gt 30 ]; then
  not_a_salon
fi

load_settings
require_prerequisites postgres
acquire_lock

if postgres_is_paused; then
  fail "the salon's status cannot be changed: postgres is paused. Resume it first: infra/scripts/local-service.sh resume postgres"
fi
postgres_is_running \
  || fail "the salon's status cannot be changed: postgres is not running. Start it first: infra/scripts/local-up.sh"

# One statement: the status of the one salon with this slug. It prints the slug when there is such
# a salon and nothing when there is not.
changed="$(printf '%s\n' "UPDATE tenant SET status = :'wanted' WHERE slug = :'slug' RETURNING slug;" \
  | psql_as_owner --set slug="$slug" --set wanted="$wanted" --tuples-only --no-align 2>>"$LOG_DIR/postgres.log")" \
  || { tail -n 30 "$LOG_DIR/postgres.log" >&2; fail "the status of '$slug' could not be changed. See $LOG_DIR/postgres.log"; }

[ "$changed" = "$slug" ] || not_a_salon
say "$slug: $wanted"
