#!/usr/bin/env bash
# qa-redeploy.sh restarts only the QA instance, in order, refuses a build that would not be HEAD,
# and exits non-zero unless the API then reports a current build of HEAD (a commit since which
# HEAD has not changed the build's sources). Starts nothing: the script runs from a copy
# in a temporary repository whose local-service.sh and local-up.sh are stubs that record their
# calls, and curl is a stub that answers /api/v1/meta with $META.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_dir="$(mktemp -d)"
runner_pid=""
lock_pid=""
# The only processes this test starts are stand-in runners and lock holders, stopped by their own ids.
trap 'rm -rf "$test_dir"; for pid in "$runner_pid" "$lock_pid"; do if [ -n "$pid" ] && ps -p "$pid" >/dev/null 2>&1; then kill "$pid"; fi; done' EXIT
failures=0
check() { if [ "$1" = ok ]; then printf 'PASS %s\n' "$2"; else printf 'FAIL %s\n' "$2"; failures=$((failures + 1)); fi; }

repo="$test_dir/repo"
mkdir -p "$repo/infra/scripts/lib" "$repo/apps/api" "$test_dir/bin"
cp "$ROOT/infra/scripts/qa-redeploy.sh" "$repo/infra/scripts/"
cp "$ROOT/infra/scripts/lib/common.sh" "$ROOT/infra/scripts/lib/build-sources.txt" "$repo/infra/scripts/lib/"
# Each stub records its call, and who holds the QA instance's lock while it runs.
for stub in local-service.sh local-up.sh; do
  printf '#!/usr/bin/env bash\nprintf "%%s %%s instance=%%s\\n" "%s" "$*" "$SALON_LOCAL_INSTANCE" >>"%s/calls"\nreadlink "%s/.local/run/qa-instance.lock" >>"%s/locks" || echo none >>"%s/locks"\n' \
    "$stub" "$test_dir" "$repo" "$test_dir" "$test_dir" >"$repo/infra/scripts/$stub"
  chmod +x "$repo/infra/scripts/$stub"
done
cat >"$test_dir/bin/curl" <<'STUB'
#!/usr/bin/env bash
printf '%s %s\n' "curl" "${*: -1}" >>"$CALLS"
printf '%s' "$META"
STUB
chmod +x "$test_dir/bin/curl"
# The prerequisites local-up.sh checks, met by stubs: Docker answers unless DOCKER_DOWN is set, and
# a stand-in JDK 25 that only reports its version. Node.js, Corepack and ps are this machine's.
cat >"$test_dir/bin/docker" <<'STUB'
#!/usr/bin/env bash
[ -z "${DOCKER_DOWN:-}" ]
STUB
chmod +x "$test_dir/bin/docker"
mkdir -p "$test_dir/jdk/bin"
printf '#!/bin/sh\necho "    java.specification.version = 25" >&2\n' >"$test_dir/jdk/bin/java"
chmod +x "$test_dir/jdk/bin/java"
printf 'api\n' >"$repo/apps/api/source.txt"
git -C "$repo" init -q -b main
git -C "$repo" -c user.email=t@example.invalid -c user.name=t add .
git -C "$repo" -c user.email=t@example.invalid -c user.name=t commit -q -m fixture
head="$(git -C "$repo" rev-parse HEAD)"
upper="$(printf '%s' "$head" | tr '[:lower:]' '[:upper:]')"

redeploy() { # <meta body> [VAR=value ...]: runs the copy; prints its exit status, keeps its output
  local meta="$1"; shift
  rm -f "$test_dir/calls" "$test_dir/locks"
  set +e
  env -u SALON_LOCAL_INSTANCE -u SALON_API_PORT -u DOCKER_DOWN PATH="$test_dir/bin:$PATH" JAVA25_HOME="$test_dir/jdk" \
    CALLS="$test_dir/calls" META="$meta" "$@" \
    "$repo/infra/scripts/qa-redeploy.sh" >"$test_dir/out.log" 2>&1
  printf '%s' "$?"
  set -e
}
calls() { cat "$test_dir/calls" 2>/dev/null || true; }

expected="local-service.sh stop web instance=qa
local-service.sh stop api instance=qa
local-up.sh  instance=qa
curl http://localhost:8190/api/v1/meta"

full_meta="{\"service\":\"salon-api\",\"commit\":\"$head\",\"time\":\"2026-11-10T00:00:00Z\"}"
[ "$(redeploy "$full_meta")" = 0 ] \
  && check ok "a rebuilt API that reports HEAD passes" || check fail "a rebuilt API reporting HEAD failed: $(cat "$test_dir/out.log")"
[ "$(calls)" = "$expected" ] && check ok "it stops web and API, then starts the QA instance, then asks its API" \
  || check fail "unexpected calls: $(calls)"

[ "$(redeploy "{\"commit\": \"${head:0:12}\"}")" = 0 ] && check ok "an abbreviated commit of HEAD is HEAD" \
  || check fail "an abbreviated HEAD was refused"
[ "$(redeploy "{\"commit\":\"$upper\"}")" = 0 ] && check ok "case does not matter" || check fail "an upper-case HEAD was refused"

for answer in '{"commit":"0123456789abcdef0123456789abcdef01234567"}' "{\"commit\":\"${head:0:6}\"}" \
              '{"commit":"unknown"}' '{"service":"salon-api"}' ''; do
  status="$(redeploy "$answer")"
  if [ "$status" != 0 ] && grep -q "not a current build of HEAD $head" "$test_dir/out.log"; then
    check ok "an API answering '${answer:-nothing}' fails and names HEAD"
  else
    check fail "an API answering '${answer:-nothing}' was accepted (status $status): $(cat "$test_dir/out.log")"
  fi
done

[ "$(redeploy "{\"commit\":\"$head\"}" SALON_API_PORT=8195)" = 0 ] && calls | grep -q 'localhost:8195/api/v1/meta' \
  && check ok "it asks the QA API on SALON_API_PORT" || check fail "SALON_API_PORT was not used: $(calls)"

for instance in default ""; do
  status="$(redeploy "{\"commit\":\"$head\"}" SALON_LOCAL_INSTANCE="$instance")"
  if [ "$status" != 0 ] && [ -z "$(calls)" ] && grep -q 'only on the QA instance' "$test_dir/out.log"; then
    check ok "SALON_LOCAL_INSTANCE='$instance' is refused before anything is stopped"
  else
    check fail "SALON_LOCAL_INSTANCE='$instance' was not refused first (status $status): $(calls)"
  fi
done

printf 'changed\n' >>"$repo/apps/api/source.txt"
status="$(redeploy "{\"commit\":\"$head\"}")"
if [ "$status" != 0 ] && [ -z "$(calls)" ] && grep -q 'uncommitted changes' "$test_dir/out.log"; then
  check ok "an uncommitted change to the build's sources is refused before anything is stopped"
else
  check fail "a dirty build source was not refused first (status $status): $(calls)"
fi
git -C "$repo" checkout -q -- apps/api/source.txt
printf 'notes\n' >"$repo/notes.md"
[ "$(redeploy "{\"commit\":\"$head\"}")" = 0 ] && check ok "a change outside the build's sources does not stop it" \
  || check fail "a change outside the build's sources was refused: $(cat "$test_dir/out.log")"

# A missing prerequisite of local-up.sh is found before anything is stopped, or the instance would
# be left down.
for missing in "JAVA25_HOME=$test_dir/no-jdk" DOCKER_DOWN=1; do
  status="$(redeploy "{\"commit\":\"$head\"}" "$missing")"
  if [ "$status" != 0 ] && [ -z "$(calls)" ] && grep -q 'a prerequisite is missing' "$test_dir/out.log"; then
    check ok "a missing prerequisite (${missing%%=*}) is refused before anything is stopped"
  else
    check fail "a missing prerequisite (${missing%%=*}) was not refused first (status $status): $(calls) $(cat "$test_dir/out.log")"
  fi
done

# The QA instance's lock, which sdlc run holds for a whole run: held by this redeploy across the
# stop, rebuild and start; a live holder refuses it before anything is stopped; a stale one is
# replaced. The holders are `sleep`s this test starts and stops by their own ids.
lock="$repo/.local/run/qa-instance.lock"
started_utc() { LC_ALL=C TZ=UTC ps -p "$1" -o lstart= | sed -e 's/^ *//' -e 's/ *$//'; }
[ "$(redeploy "{\"commit\":\"$head\"}")" = 0 ] || check fail "a plain redeploy failed: $(cat "$test_dir/out.log")"
holders="$(sort -u "$test_dir/locks" 2>/dev/null)"
if [ "$(wc -l <"$test_dir/locks" | tr -d ' ')" = 3 ] && [ "$(printf '%s\n' "$holders" | wc -l | tr -d ' ')" = 1 ] \
    && [ "$holders" != none ] && [ ! -e "$lock" ] && [ ! -L "$lock" ]; then
  check ok "the lock is held through stop, stop and start by one holder, and released at the end"
else
  check fail "the lock was not held throughout and then released: $(cat "$test_dir/locks" 2>/dev/null); left: $(readlink "$lock" 2>/dev/null)"
fi
sleep 60 &
lock_pid=$!
mkdir -p "$repo/.local/run"
ln -s "$lock_pid $(started_utc "$lock_pid")" "$lock"
status="$(redeploy "{\"commit\":\"$head\"}")"
if [ "$status" != 0 ] && [ -z "$(calls)" ] && grep -q "in use by process $lock_pid" "$test_dir/out.log" \
    && [ "$(readlink "$lock")" = "$lock_pid $(started_utc "$lock_pid")" ]; then
  check ok "a live holder of the lock (a run starting) refuses the redeploy before anything is stopped"
else
  check fail "a held lock did not refuse the redeploy first (status $status): $(calls) $(cat "$test_dir/out.log")"
fi
ps -p "$lock_pid" >/dev/null 2>&1 && kill "$lock_pid"
wait "$lock_pid" 2>/dev/null || true
rm -f "$lock"
ln -s "$lock_pid Mon Jan  1 00:00:00 2024" "$lock"
if [ "$(redeploy "{\"commit\":\"$head\"}")" = 0 ] && [ ! -L "$lock" ]; then
  check ok "a stale lock (its holder is gone) is replaced, and released at the end"
else
  check fail "a stale lock stopped the redeploy or was left: $(cat "$test_dir/out.log")"
fi

# A run of this checkout in progress: summary.json says "running" and names a live runner. The
# stand-in runner is a `sleep` this test starts and stops by its own process id.
summary_of() { # <folder> <state> <pid> <process start>: writes a runner summary as sdlc run does
  mkdir -p "$repo/qa/reports/$1"
  printf '{\n  "label": "F07",\n  "state": "%s",\n  "entries": [],\n  "runner": {\n    "pid": %s,\n    "process_start": "%s"\n  }\n}\n' \
    "$2" "$3" "$4" >"$repo/qa/reports/$1/summary.json"
}
sleep 60 &
runner_pid=$!
runner_start="$(ps -p "$runner_pid" -o lstart= | sed -e 's/^ *//' -e 's/ *$//')"
summary_of 20261110-100000-F07-done complete "$runner_pid" "$runner_start"
summary_of 20261110-110000-F07-abc running "$runner_pid" "$runner_start"
status="$(redeploy "{\"commit\":\"$head\"}")"
if [ "$status" != 0 ] && [ -z "$(calls)" ] && grep -q 'in progress: qa/reports/20261110-110000-F07-abc (process' "$test_dir/out.log"; then
  check ok "a run in progress is refused before anything is stopped, naming its report folder"
else
  check fail "a run in progress was not refused first (status $status): $(cat "$test_dir/out.log") $(calls)"
fi
ps -p "$runner_pid" >/dev/null 2>&1 && kill "$runner_pid"
wait "$runner_pid" 2>/dev/null || true
[ "$(redeploy "{\"commit\":\"$head\"}")" = 0 ] \
  && check ok "a summary left 'running' by a runner that is gone does not stop it" \
  || check fail "a dead runner's summary was taken for a run: $(cat "$test_dir/out.log")"
rm -rf "$repo/qa/reports"

# The rule sdlc run applies too: a commit that changes no build source leaves the build current.
commit_all() { # <message>: commits everything in the copy; prints the new HEAD
  git -C "$repo" -c user.email=t@example.invalid -c user.name=t add -A
  git -C "$repo" -c user.email=t@example.invalid -c user.name=t commit -q -m "$1"
  git -C "$repo" rev-parse HEAD
}
mkdir -p "$repo/qa/api"
printf 'repaired\n' >"$repo/qa/api/x.spec.ts"
qa_only="$(commit_all 'test: repair a test')"
[ "$(redeploy "{\"commit\":\"$head\"}")" = 0 ] \
  && check ok "the build of an earlier commit is current after a commit that changes only tests and notes" \
  || check fail "a QA-only commit made the earlier build stale: $(cat "$test_dir/out.log")"
printf 'fixed\n' >>"$repo/apps/api/source.txt"
fixed="$(commit_all 'fix: the API')"
status="$(redeploy "{\"commit\":\"$qa_only\"}")"
if [ "$status" != 0 ] && grep -q "not a current build of HEAD $fixed" "$test_dir/out.log"; then
  check ok "a build from before a change to the build's sources is not current"
else
  check fail "a build older than a source change was accepted (status $status): $(cat "$test_dir/out.log")"
fi
[ "$(redeploy "{\"commit\":\"$fixed\"}")" = 0 ] && check ok "the API rebuilt at the new HEAD is current" \
  || check fail "the API at the new HEAD was refused: $(cat "$test_dir/out.log")"

[ "$failures" -eq 0 ] || { printf '%s check(s) failed\n' "$failures" >&2; exit 1; }
