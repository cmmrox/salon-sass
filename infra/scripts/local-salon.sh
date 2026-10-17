#!/usr/bin/env bash
# A hook for tests, for the local environment only, and not something the product does: suspends
# or reactivates one salon of the local database, so that a test can see what a suspended salon's
# web address shows, or changes its stored brand colour, so that a test can see a salon's colour
# change its sign-in and workspace (CR-013). Suspending a salon for real is an operator's action
# and arrives with the operator console; a salon edits its own colour with F04's brand settings.
#
# Usage: infra/scripts/local-salon.sh <suspend|activate> <slug>
#        infra/scripts/local-salon.sh colour <slug> <#RRGGBB>
#
#   suspend gamma            gamma.localhost then answers "temporarily unavailable" on the web app
#                            and 403 salon_suspended on the API, on the next request; nothing is
#                            restarted
#   activate gamma           gamma answers as before, on the next request
#   colour beta '#0F766E'    beta's published brand colour becomes #0F766E (stored upper case) on
#                            the next request, on its sign-in and workspace; nothing is restarted
#
# suspend/activate change that salon's status in the registry, and colour changes that salon's
# brand colour, as the role that owns the tables, and nothing else: no other salon, no name, no
# other column (updated_at excepted). It refuses a slug that is not a salon and a colour that is not
# #RRGGBB. It only ever reaches PostgreSQL in this project's own container (salon-postgres), which
# must be running. local-reset.sh puts everything back: the three synthetic salons, all active, with
# their seeded colours (alpha #D6086A, beta #1F2937, gamma #FDE68A).
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

usage() {
  printf 'usage: %s <suspend|activate> <slug>\n' "$(basename "$0")" >&2
  printf '       %s colour <slug> <#RRGGBB>\n' "$(basename "$0")" >&2
  exit 2
}

[ "$#" -ge 2 ] || usage
action="$1"
slug="$2"
case "$action" in
  suspend)  [ "$#" -eq 2 ] || usage; wanted="suspended" ;;
  activate) [ "$#" -eq 2 ] || usage; wanted="active" ;;
  colour)
    [ "$#" -eq 3 ] || usage
    # #RRGGBB in either case, stored in upper case: the one spelling the table accepts.
    [[ "$3" =~ ^#[0-9A-Fa-f]{6}$ ]] \
      || fail "'$3' is not a #RRGGBB colour. Nothing was changed."
    colour="$(printf '%s' "$3" | tr '[:lower:]' '[:upper:]')"
    ;;
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

what="status"; [ "$action" = "colour" ] && what="colour"
if postgres_is_paused; then
  fail "the salon's $what cannot be changed: postgres is paused. Resume it first: infra/scripts/local-service.sh resume postgres"
fi
postgres_is_running \
  || fail "the salon's $what cannot be changed: postgres is not running. Start it first: infra/scripts/local-up.sh"

if [ "$action" = "colour" ]; then
  # One transaction: set that salon for row-level security (forced on salon_brand, so it binds the
  # owner too), then change only its colour. Without such a salon no tenant is set, no row is
  # visible and nothing is changed. It prints the slug when the colour was changed.
  changed="$(printf '%s\n' \
    "BEGIN;" \
    "\\o /dev/null" \
    "SELECT set_config('app.tenant_id', id::text, true) FROM tenant WHERE slug = :'slug';" \
    "\\o" \
    "UPDATE salon_brand SET brand_colour = :'colour', updated_at = now() WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid RETURNING :'slug';" \
    "COMMIT;" \
    | psql_as_owner --set slug="$slug" --set colour="$colour" --tuples-only --no-align 2>>"$LOG_DIR/postgres.log")" \
    || { tail -n 30 "$LOG_DIR/postgres.log" >&2; fail "the colour of '$slug' could not be changed. See $LOG_DIR/postgres.log"; }
  [ "$changed" = "$slug" ] || not_a_salon
  say "$slug: colour $colour"
  exit 0
fi

# One statement: the status of the one salon with this slug. It prints the slug when there is such
# a salon and nothing when there is not.
changed="$(printf '%s\n' "UPDATE tenant SET status = :'wanted' WHERE slug = :'slug' RETURNING slug;" \
  | psql_as_owner --set slug="$slug" --set wanted="$wanted" --tuples-only --no-align 2>>"$LOG_DIR/postgres.log")" \
  || { tail -n 30 "$LOG_DIR/postgres.log" >&2; fail "the status of '$slug' could not be changed. See $LOG_DIR/postgres.log"; }

[ "$changed" = "$slug" ] || not_a_salon
say "$slug: $wanted"
