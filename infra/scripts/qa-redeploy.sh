#!/usr/bin/env bash
# Rebuilds the QA instance from this checkout's HEAD and proves that it runs it.
#
# Usage: infra/scripts/qa-redeploy.sh
#
# Stops the QA instance's web app and API (by the process ids the local scripts recorded, through
# local-service.sh), starts it again with local-up.sh, which rebuilds the API jar and the web app
# and starts PostgreSQL first if it is down, and then asks the API's /api/v1/meta which commit it
# was built from. Exits non-zero unless that commit is HEAD. PostgreSQL and its data are left as
# they are.
#
# Only ever the QA instance (decision 105): SALON_LOCAL_INSTANCE is set to qa, and any other value
# in the shell is refused before anything is stopped. Refuses, before stopping anything, when the
# build's sources (apps, packages, the workspace and lock files) have uncommitted changes: a build
# of them would not be HEAD, though /api/v1/meta would say it was. Commit them first.
#
# Playwright's global setup (qa/shared/global-setup.ts) makes the same comparison before every run.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

case "${SALON_LOCAL_INSTANCE-qa}" in
  qa) ;;
  *) printf "error: qa-redeploy.sh acts only on the QA instance, and SALON_LOCAL_INSTANCE is '%s'. Unset it, or set it to qa. Nothing was done.\n" \
       "${SALON_LOCAL_INSTANCE:-default}" >&2
     exit 1 ;;
esac
export SALON_LOCAL_INSTANCE=qa
# shellcheck source=lib/common.sh
. "$HERE/lib/common.sh"

# What the build is made from; a change to anything else does not change what runs.
BUILD_SOURCES=(apps packages package.json pnpm-lock.yaml pnpm-workspace.yaml .nvmrc .sdkmanrc)

# Whether two commit ids name the same commit: equal, or one an abbreviation (7 or more hex
# digits) of the other.
same_commit() {
  local a b
  a="$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')"
  b="$(printf '%s' "${2:-}" | tr '[:upper:]' '[:lower:]')"
  case "$a$b" in *[!0-9a-f]*) return 1 ;; esac
  [ "${#a}" -ge 7 ] && [ "${#b}" -ge 7 ] || return 1
  case "$a" in "$b"*) return 0 ;; esac
  case "$b" in "$a"*) return 0 ;; esac
  return 1
}

head="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null)" || fail "cannot read HEAD of $ROOT. Nothing was done."
changes="$(git -C "$ROOT" status --porcelain --untracked-files=all -- "${BUILD_SOURCES[@]}")"
if [ -n "$changes" ]; then
  printf '%s\n' "$changes" >&2
  fail "the build's sources have uncommitted changes (above), so a build would not be HEAD $head. Commit them and run again. Nothing was stopped."
fi

# shellcheck disable=SC2086 # three words: the QA instance's PostgreSQL, API and web ports
set -- $DEFAULT_PORTS
api_port="${SALON_API_PORT:-$2}"

say "qa-redeploy: rebuilding the QA instance at $head"
"$HERE/local-service.sh" stop web
"$HERE/local-service.sh" stop api
"$HERE/local-up.sh"

meta="$(curl -s --max-time 10 "http://localhost:${api_port}/api/v1/meta" 2>/dev/null || true)"
running="$(printf '%s' "$meta" | sed -n 's/.*"commit"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')"
if ! same_commit "$running" "$head"; then
  fail "the QA instance's API at http://localhost:${api_port}/api/v1/meta reports commit '${running:-none}', not HEAD $head. See $LOG_DIR/api-build.log and $LOG_DIR/api.log."
fi
say "qa-redeploy: the QA instance's API runs HEAD $head"
