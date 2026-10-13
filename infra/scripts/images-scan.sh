#!/usr/bin/env bash
# Scans images for vulnerabilities with Trivy, run from its official image (decision 030), and exits
# non-zero when any image has a critical or high vulnerability that has a fix, listing each one.
# A vulnerability with no fix yet is not a failure: there is nothing to upgrade to.
#
# Usage: infra/scripts/images-scan.sh [image ...]
#
#   With no image, the API's and the web app's images of the development version (apps/api/pom.xml),
#   or of SALON_VERSION when it is set, as infra/scripts/images-build.sh tags them.
#
# Trivy reads the images from Docker and keeps its vulnerability database in the volume
# salon-trivy-cache, which belongs to neither the local environment nor the stack.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/stack.sh"

require_docker

if [ $# -eq 0 ]; then
  version="${SALON_VERSION:-$(default_version)}"
  set -- "$(image_ref api "$version")" "$(image_ref web "$version")"
fi

failed=()
for image in "$@"; do
  image_exists "$image" || fail "there is no image $image. Build it first (infra/scripts/images-build.sh)."
  say "scan: $image"
  if ! docker run --rm \
      --volume /var/run/docker.sock:/var/run/docker.sock \
      --volume salon-trivy-cache:/root/.cache/ \
      "$TRIVY_IMAGE" image \
      --quiet \
      --scanners vuln \
      --severity HIGH,CRITICAL \
      --ignore-unfixed \
      --exit-code 1 \
      --format table \
      "$image"; then
    failed+=("$image")
  fi
done

if [ "${#failed[@]}" -gt 0 ]; then
  fail "critical or high vulnerabilities with a fix (listed above) in: ${failed[*]}"
fi
say "scan: no critical or high vulnerability with a fix in $*"
