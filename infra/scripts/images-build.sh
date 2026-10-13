#!/usr/bin/env bash
# Builds the production images of the API and the web app, tags each with the version and the commit,
# and checks what every image must be: run as a user other than root, hold no environment file, and
# carry the version and the commit it was given.
#
# Usage: infra/scripts/images-build.sh [version]
#
#   version   the platform's version (an image tag); by default the API build's development version
#             (apps/api/pom.xml). `GET /api/v1/meta` reports it.
#
# The images are salon-api:<version>, salon-api:<commit>, salon-web:<version> and salon-web:<commit>,
# where <commit> is the commit's first 12 characters, with `-dirty` when the tree the images are built
# from has uncommitted changes. SALON_IMAGE_PREFIX (for example ghcr.io/<owner>/) goes in front of
# each name. Exits non-zero, naming the problem, when a build or a check fails.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/stack.sh"

version="${1:-$(default_version)}"
require_version_form "$version"
require_docker
command -v git >/dev/null 2>&1 || fail "git is required to name the commit the images are built from."

commit="$(git -C "$ROOT" rev-parse HEAD 2>/dev/null)" || fail "the images are built from a git checkout, and $ROOT is not one."
# Only what goes into an image decides whether it is the commit or not.
if [ -n "$(git -C "$ROOT" status --porcelain -- apps/api apps/web packages package.json pnpm-lock.yaml pnpm-workspace.yaml)" ]; then
  commit="${commit}-dirty"
fi
commit_tag="${commit:0:12}"
[[ "$commit" == *-dirty ]] && commit_tag="${commit_tag}-dirty"

build() { # <api|web> <dockerfile> <context>
  local kind="$1" dockerfile="$2" context="$3" log="$STACK_LOG_DIR/images-build-$1.log"
  say "$kind: building $(image_ref "$kind" "$version")"
  if ! docker build \
      --file "$dockerfile" \
      --build-arg VERSION="$version" \
      --build-arg COMMIT="$commit" \
      --tag "$(image_ref "$kind" "$version")" \
      --tag "$(image_ref "$kind" "$commit_tag")" \
      "$context" >"$log" 2>&1; then
    tail -n 40 "$log" >&2
    fail "the $kind image did not build. See .stack/logs/images-build-$kind.log"
  fi
}

# What every image must be. Prints each problem and returns non-zero if there is one.
check_image() { # <api|web>
  local image problems=0 user uid found label
  image="$(image_ref "$1" "$version")"
  user="$(docker image inspect --format '{{.Config.User}}' "$image")"
  if [ -z "$user" ] || [ "${user%%:*}" = "0" ] || [ "${user%%:*}" = "root" ]; then
    printf 'error: %s runs as root (its USER is %s).\n' "$image" "${user:-not set}" >&2
    problems=1
  fi
  uid="$(docker run --rm --network none --entrypoint id "$image" -u 2>/dev/null || true)"
  if [ -z "$uid" ] || [ "$uid" = "0" ]; then
    printf 'error: a container from %s runs as user id %s, not as an unprivileged user.\n' "$image" "${uid:-unknown}" >&2
    problems=1
  fi
  # Looked for as root, so that no folder is closed to the search.
  found="$(docker run --rm --network none --user 0 --entrypoint find "$image" / -xdev \( -name .env -o -name '.env.*' \) -print 2>/dev/null || true)"
  if [ -n "$found" ]; then
    printf 'error: %s holds an environment file: %s\n' "$image" "$(printf '%s' "$found" | tr '\n' ' ')" >&2
    problems=1
  fi
  label="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.version"}} {{index .Config.Labels "org.opencontainers.image.revision"}}' "$image")"
  if [ "$label" != "$version $commit" ]; then
    printf 'error: %s is labelled "%s", not "%s %s".\n' "$image" "$label" "$version" "$commit" >&2
    problems=1
  fi
  return "$problems"
}

mkdir -p "$STACK_LOG_DIR"
build api "$ROOT/apps/api/Dockerfile" "$ROOT/apps/api"
build web "$ROOT/apps/web/Dockerfile" "$ROOT"

failed=0
check_image api || failed=1
check_image web || failed=1
[ "$failed" -eq 0 ] || fail "an image is not fit to run (see above)."

for kind in api web; do
  image="$(image_ref "$kind" "$version")"
  size="$(docker image inspect --format '{{.Size}}' "$image")"
  say "$kind: $image and $(image_ref "$kind" "$commit_tag"), $((size / 1000000)) MB, user $(docker image inspect --format '{{.Config.User}}' "$image"), no environment file"
done
say "images: version $version, commit $commit"
