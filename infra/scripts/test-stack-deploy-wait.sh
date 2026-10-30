#!/usr/bin/env bash
# stack-deploy.sh waits for a replaced instance the way BUG-F01-009 needs: an instance that reports
# unhealthy while it warms up under traffic, then healthy, is ready (Compose's `--wait` gave up at the
# first unhealthy report, and the deployment stopped half done); one that stops, or is not ready in
# time, is not. Starts nothing: Docker is replaced by a stub that plays back a list of states.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
failures=0
check() { if [ "$1" = ok ]; then printf 'PASS %s\n' "$2"; else printf 'FAIL %s\n' "$2"; failures=$((failures + 1)); fi; }

# The stub answers `docker inspect` with the next line of $STATES (the last one repeats).
mkdir -p "$test_dir/bin"
cat >"$test_dir/bin/docker" <<'STUB'
#!/usr/bin/env bash
[ "$1" = inspect ] || exit 0
n="$(cat "$STATES.n" 2>/dev/null || echo 0)"; n=$((n + 1)); printf '%s' "$n" >"$STATES.n"
line="$(sed -n "${n}p" "$STATES")"; [ -n "$line" ] || line="$(tail -n 1 "$STATES")"
[ "$line" = gone ] && exit 1
printf '%s\n' "$line"
STUB
chmod +x "$test_dir/bin/docker"
# The deploy script's own library, with its waits shortened.
# shellcheck source=lib/stack.sh
. "$ROOT/infra/scripts/lib/stack.sh"
STACK_LOG_DIR="$test_dir"
sleep() { :; }

wait_for() { # <name> <limit> <state...>: runs wait_until_ready over these states; prints its status
  printf '%s\n' "${@:3}" >"$test_dir/$1.states"
  set +e
  PATH="$test_dir/bin:$PATH" STATES="$test_dir/$1.states" wait_until_ready api-2 "$2"
  printf '%s' "$?"
  set -e
}

[ "$(wait_for warming 30 'running starting' 'running starting' 'running unhealthy' 'running unhealthy' 'running healthy')" = 0 ] \
  && check ok "an instance unhealthy while it warms up, then healthy, is ready" \
  || check fail "an instance unhealthy on the way to healthy was given up on"
[ "$(wait_for quick 30 'running healthy')" = 0 ] \
  && check ok "a healthy instance is ready at once" || check fail "a healthy instance was not ready"
[ "$(wait_for exited 30 'running starting' 'exited none')" != 0 ] \
  && check ok "an instance that stops is not ready" || check fail "an instance that stopped was called ready"
[ "$(wait_for removed 30 'running starting' gone)" != 0 ] \
  && check ok "an instance that disappears is not ready" || check fail "an instance that disappeared was called ready"
[ "$(wait_for stuck 5 'running unhealthy')" != 0 ] \
  && check ok "an instance never ready within the limit is not ready" || check fail "an instance never ready was called ready"

[ "$failures" -eq 0 ] || { printf '%s check(s) failed\n' "$failures" >&2; exit 1; }
