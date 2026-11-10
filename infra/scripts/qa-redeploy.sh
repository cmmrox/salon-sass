#!/usr/bin/env bash
# Rebuilds the QA instance from this checkout's HEAD and proves that it runs it.
#
# Usage: infra/scripts/qa-redeploy.sh
#
# Stops the QA instance's web app and API (by the process ids the local scripts recorded, through
# local-service.sh), starts it again with local-up.sh, which rebuilds the API jar and the web app
# and starts PostgreSQL first if it is down, and then asks the API's /api/v1/meta which commit it
# was built from. Exits non-zero unless that build is current for HEAD: the reported commit is an
# ancestor of HEAD and HEAD has not changed the build's sources since (git diff --quiet). That is
# the rule `qa/scripts/sdlc run` and Playwright's global setup apply before a run. PostgreSQL and
# its data are left as they are.
#
# Only ever the QA instance (decision 105): SALON_LOCAL_INSTANCE is set to qa, and any other value
# in the shell is refused before anything is stopped. Refuses, before stopping anything, when the
# build's sources (lib/build-sources.txt, the one list of them) have uncommitted changes: a build
# of them would not be HEAD, though /api/v1/meta would say it was. Commit them first.
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

# What the build is made from (one path per line); a change to anything else does not change
# what runs.
BUILD_SOURCES=()
while IFS= read -r source || [ -n "$source" ]; do
  case "$source" in ''|'#'*) continue ;; esac
  BUILD_SOURCES+=("$source")
done <"$HERE/lib/build-sources.txt"
[ "${#BUILD_SOURCES[@]}" -gt 0 ] || fail "$HERE/lib/build-sources.txt names no build source. Nothing was done."

# Whether the build of commit $1 is current for HEAD: $1 is a commit of this repository, an
# ancestor of HEAD, and HEAD has not changed the build's sources since.
build_is_current() {
  local commit
  commit="$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')"
  case "$commit" in ''|*[!0-9a-f]*) return 1 ;; esac
  [ "${#commit}" -ge 7 ] || return 1
  git -C "$ROOT" cat-file -e "${commit}^{commit}" 2>/dev/null || return 1
  git -C "$ROOT" merge-base --is-ancestor "$commit" HEAD 2>/dev/null || return 1
  git -C "$ROOT" diff --quiet "$commit" HEAD -- "${BUILD_SOURCES[@]}"
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
if ! build_is_current "$running"; then
  fail "the QA instance's API at http://localhost:${api_port}/api/v1/meta reports commit '${running:-none}', which is not a current build of HEAD $head. See $LOG_DIR/api-build.log and $LOG_DIR/api.log."
fi
say "qa-redeploy: the QA instance's API runs a current build of HEAD $head (commit $running)"
