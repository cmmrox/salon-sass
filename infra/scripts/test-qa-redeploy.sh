#!/usr/bin/env bash
# qa-redeploy.sh restarts only the QA instance, in order, refuses a build that would not be HEAD,
# and exits non-zero unless the API then reports a current build of HEAD (a commit since which
# HEAD has not changed the build's sources). Starts nothing: the script runs from a copy
# in a temporary repository whose local-service.sh and local-up.sh are stubs that record their
# calls, and curl is a stub that answers /api/v1/meta with $META.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
failures=0
check() { if [ "$1" = ok ]; then printf 'PASS %s\n' "$2"; else printf 'FAIL %s\n' "$2"; failures=$((failures + 1)); fi; }

repo="$test_dir/repo"
mkdir -p "$repo/infra/scripts/lib" "$repo/apps/api" "$test_dir/bin"
cp "$ROOT/infra/scripts/qa-redeploy.sh" "$repo/infra/scripts/"
cp "$ROOT/infra/scripts/lib/common.sh" "$ROOT/infra/scripts/lib/build-sources.txt" "$repo/infra/scripts/lib/"
for stub in local-service.sh local-up.sh; do
  printf '#!/usr/bin/env bash\nprintf "%%s %%s instance=%%s\\n" "%s" "$*" "$SALON_LOCAL_INSTANCE" >>"%s/calls"\n' \
    "$stub" "$test_dir" >"$repo/infra/scripts/$stub"
  chmod +x "$repo/infra/scripts/$stub"
done
cat >"$test_dir/bin/curl" <<'STUB'
#!/usr/bin/env bash
printf '%s %s\n' "curl" "${*: -1}" >>"$CALLS"
printf '%s' "$META"
STUB
chmod +x "$test_dir/bin/curl"
printf 'api\n' >"$repo/apps/api/source.txt"
git -C "$repo" init -q -b main
git -C "$repo" -c user.email=t@example.invalid -c user.name=t add .
git -C "$repo" -c user.email=t@example.invalid -c user.name=t commit -q -m fixture
head="$(git -C "$repo" rev-parse HEAD)"
upper="$(printf '%s' "$head" | tr '[:lower:]' '[:upper:]')"

redeploy() { # <meta body> [VAR=value ...]: runs the copy; prints its exit status, keeps its output
  local meta="$1"; shift
  rm -f "$test_dir/calls"
  set +e
  env -u SALON_LOCAL_INSTANCE -u SALON_API_PORT PATH="$test_dir/bin:$PATH" CALLS="$test_dir/calls" META="$meta" "$@" \
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
