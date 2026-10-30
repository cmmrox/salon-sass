#!/usr/bin/env bash
# stack-deploy.sh replaces the instances in the order BUG-F01-009 needs: the API's one at a time,
# then the web app's with a third web app instance (web-surge) on the new version serving from
# before the first is taken out until after the second is back, so that the web app tier never
# rests on one instance. Runs the script in a copy of the repository's scripts and Compose file,
# with Docker replaced by a stub that records what it is asked and `sleep` by one that returns.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failures=0
check() { if [ "$1" = ok ]; then printf 'PASS %s\n' "$2"; else printf 'FAIL %s\n' "$2"; failures=$((failures + 1)); fi; }

mkdir -p "$work/repo/infra/stack" "$work/bin"
cp -R "$ROOT/infra/scripts" "$work/repo/infra/scripts"
cp "$ROOT/infra/stack/compose.yaml" "$work/repo/infra/stack/compose.yaml"
printf 'SALON_STACK_ENV=local\nSALON_PLATFORM_DOMAIN=stack.localhost\nSALON_EDGE_HTTP_PORT=8089\n' >"$work/repo/.env.stack"

# Every container runs and is healthy; every image exists; every Compose command succeeds and is
# written to $work/calls as "<command> <service>" (Compose runs with an empty environment, so the
# path is written into the stub).
{ printf '#!/usr/bin/env bash\nCALLS=%q\n' "$work/calls"; cat <<'STUB'
case "$1" in
  info) exit 0 ;;
  image) exit 0 ;;
  inspect)
    case "$*" in
      *Health*) echo "running healthy" ;;
      *) echo "true" ;;
    esac
    exit 0 ;;
  compose)
    args=("$@"); last="${args[${#args[@]}-1]}"
    for word in "$@"; do
      case "$word" in
        up|stop|run) printf '%s %s\n' "$word" "$last" >>"$CALLS"; break ;;
      esac
    done
    exit 0 ;;
esac
exit 0
STUB
} >"$work/bin/docker"
printf '#!/bin/sh\nexit 0\n' >"$work/bin/sleep"
chmod +x "$work/bin/docker" "$work/bin/sleep"

deploy() { # <name> <deployed versions...>: deploys version 2 over these versions; prints the calls
  local name="$1"; shift
  mkdir -p "$work/repo/.stack"
  printf '%s\n' "$@" >"$work/repo/.stack/deployed.env"
  : >"$work/calls"
  PATH="$work/bin:$PATH" "$work/repo/infra/scripts/stack-deploy.sh" 2 \
    >"$work/$name.out" 2>&1 || { cat "$work/$name.out" >&2; return 1; }
  { grep -v '^run ' "$work/calls" || true; } | tr '\n' ',' | sed 's/,$//'
}
all_at_1=(SALON_MIGRATE_VERSION=1 SALON_API_1_VERSION=1 SALON_API_2_VERSION=1 SALON_WEB_1_VERSION=1 SALON_WEB_2_VERSION=1)

calls="$(deploy full "${all_at_1[@]}")"
expected="stop api-1,up api-1,stop api-2,up api-2,up web-surge,stop web-1,up web-1,stop web-2,up web-2,stop web-surge"
[ "$calls" = "$expected" ] \
  && check ok "a deployment starts web-surge before the first web app instance stops and stops it after the last is back" \
  || check fail "a deployment ran: $calls (expected: $expected)"
grep -q '^SALON_WEB_SURGE_VERSION=2$' "$work/repo/.stack/deployed.env" \
  && check ok "web-surge runs the version being deployed" || check fail "web-surge was not given version 2"

calls="$(deploy api-only SALON_MIGRATE_VERSION=1 SALON_API_1_VERSION=1 SALON_API_2_VERSION=1 SALON_WEB_1_VERSION=2 SALON_WEB_2_VERSION=2)"
expected="stop api-1,up api-1,stop api-2,up api-2"
[ "$calls" = "$expected" ] \
  && check ok "with every web app instance already on the version, no web-surge is started" \
  || check fail "an API-only deployment ran: $calls (expected: $expected)"

calls="$(deploy second-web SALON_MIGRATE_VERSION=2 SALON_API_1_VERSION=2 SALON_API_2_VERSION=2 SALON_WEB_1_VERSION=2 SALON_WEB_2_VERSION=1)"
expected="up web-surge,stop web-2,up web-2,stop web-surge"
[ "$calls" = "$expected" ] \
  && check ok "a deployment resumed at web-2 still keeps a second web app instance serving" \
  || check fail "a resumed deployment ran: $calls (expected: $expected)"

[ "$failures" -eq 0 ] || { printf '%s check(s) failed\n' "$failures" >&2; exit 1; }
