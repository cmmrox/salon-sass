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
#
# A scan that could not complete (Trivy's own error, or Docker unable to run it) fails too, but is
# reported as what it is, with the image and the scanner's message, never as vulnerabilities
# (BUG-F01-005). Exit status: 0 clean; 1 vulnerabilities with a fix; 2 a scan could not complete.
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/stack.sh"

require_docker

if [ $# -eq 0 ]; then
  version="${SALON_VERSION:-$(default_version)}"
  set -- "$(image_ref api "$version")" "$(image_ref web "$version")"
fi

# Trivy exits 1 on a fatal error whatever --exit-code says (and Go exits 2 on a panic), so findings
# are given an exit status of their own: only that one means vulnerabilities.
FINDINGS_EXIT=3
errors_file="$(mktemp)"
trap 'rm -f "$errors_file"' EXIT

vulnerable=()
incomplete=()
for image in "$@"; do
  image_exists "$image" || fail "there is no image $image. Build it first (infra/scripts/images-build.sh)."
  say "scan: $image"
  set +e
  docker run --rm \
      --volume /var/run/docker.sock:/var/run/docker.sock \
      --volume salon-trivy-cache:/root/.cache/ \
      "$TRIVY_IMAGE" image \
      --quiet \
      --scanners vuln \
      --severity HIGH,CRITICAL \
      --ignore-unfixed \
      --exit-code "$FINDINGS_EXIT" \
      --format table \
      "$image" 2>"$errors_file"
  status=$?
  set -e
  cat "$errors_file" >&2
  case "$status" in
    0) ;;
    "$FINDINGS_EXIT") vulnerable+=("$image") ;;
    *)
      # The scanner's own words: its FATAL line when there is one, else the last thing it said.
      message="$(grep -m 1 'FATAL' "$errors_file" || true)"
      [ -n "$message" ] || message="$(grep -v '^[[:space:]]*$' "$errors_file" | tail -n 1 || true)"
      printf 'error: the scan of %s could not complete (scanner exit %s): %s\n' \
        "$image" "$status" "${message:-no message}" >&2
      incomplete+=("$image") ;;
  esac
done

if [ "${#vulnerable[@]}" -gt 0 ]; then
  printf 'error: critical or high vulnerabilities with a fix (listed above) in: %s\n' "${vulnerable[*]}" >&2
fi
if [ "${#incomplete[@]}" -gt 0 ]; then
  printf 'error: these scans could not complete, so these images are not known to be clean: %s. Run the scan again; this is not a vulnerability finding.\n' "${incomplete[*]}" >&2
fi
[ "${#vulnerable[@]}" -eq 0 ] || exit 1
[ "${#incomplete[@]}" -eq 0 ] || exit 2
say "scan: no critical or high vulnerability with a fix in $*"
