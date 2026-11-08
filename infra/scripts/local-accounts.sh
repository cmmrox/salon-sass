#!/usr/bin/env bash
# The fixed local account roster (decision 099): infra/local/accounts.json, five staff per synthetic
# salon and one operator per operator role, every password 123456@321.
#
# Usage: infra/scripts/local-accounts.sh          creates every roster account the running instance
#                                                 does not have yet; an account that exists is never
#                                                 changed, re-keyed or removed, so it is safe to run again
#        infra/scripts/local-accounts.sh keys     prints each authenticator's otpauth:// link (each salon
#                                                 Owner and every operator), and a QR code to scan when
#                                                 qrencode is installed; needs nothing running
#
# SALON_LOCAL_INSTANCE=qa seeds the QA instance instead of the default one. local-up.sh and
# local-reset.sh run it once the API answers. Seeding goes through the API's local seed hooks, so
# the API must be running in the local profile; it starts and stops nothing.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"

usage() {
  printf 'usage: %s [keys]\n' "$(basename "$0")" >&2
  exit 2
}

[ "$#" -le 1 ] || usage
case "${1:-seed}" in
  seed)
    load_settings
    command -v curl >/dev/null 2>&1 || fail "curl is required and was not found."
    seed_roster
    ;;
  keys)
    links="$(roster keys)"
    say "Authenticators of the fixed local accounts (infra/local/accounts.json); every password is 123456@321."
    say "Add each to an authenticator app once; the seeds never change, so it keeps working after any reset."
    say ""
    while IFS=$'\t' read -r title link; do
      [ -n "$title" ] || continue
      say "$title"
      say "  $link"
      if command -v qrencode >/dev/null 2>&1; then
        printf '%s' "$link" | qrencode -t ANSIUTF8
      fi
      say ""
    done <<<"$links"
    command -v qrencode >/dev/null 2>&1 \
      || say "qrencode is not installed, so no QR codes are shown; an authenticator app can take the link or its secret instead."
    ;;
  *) usage ;;
esac
