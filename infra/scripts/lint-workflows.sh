#!/usr/bin/env bash
# Checks every GitHub Actions workflow (.github/workflows) with actionlint, run from its official
# image (decision 030), which also checks each `run:` script with shellcheck. Exits non-zero, listing
# each problem, when there is one.
#
# Usage: infra/scripts/lint-workflows.sh
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/stack.sh"

require_docker
docker run --rm --network none \
  --volume "$ROOT:/repo:ro" --workdir /repo \
  "$ACTIONLINT_IMAGE" -color \
  || fail "a workflow has problems (listed above)."
say "workflows: $(find "$ROOT/.github/workflows" -name '*.y*ml' | wc -l | tr -d ' ') checked, no problem"
