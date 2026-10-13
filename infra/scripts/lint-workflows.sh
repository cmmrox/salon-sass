#!/usr/bin/env bash
# Checks every GitHub Actions workflow (.github/workflows):
#
#   1. with actionlint, run from its official image (decision 030), which also checks each `run:`
#      script with shellcheck;
#   2. that every action a workflow uses is either GitHub's own (`actions/...`, which may keep its
#      major version tag) or pinned to a full 40-character commit SHA. A third-party action named by
#      a tag can be changed under the workflow by whoever moves the tag, and would run with the
#      workflow's credentials.
#
# Exits non-zero, listing each problem, when there is one.
#
# Usage: infra/scripts/lint-workflows.sh
set -euo pipefail
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/stack.sh"

require_docker
failed=0

docker run --rm --network none \
  --volume "$ROOT:/repo:ro" --workdir /repo \
  "$ACTIONLINT_IMAGE" -color \
  || failed=1

# Every `uses:` line, steps' and reusable workflows' alike: file, line number and the reference.
while IFS= read -r found; do
  file="${found%%:*}"; rest="${found#*:}"; line="${rest%%:*}"; text="${rest#*:}"
  reference="$(printf '%s' "$text" | sed -E 's/^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]*//; s/[[:space:]]+#.*$//; s/^["'\'']//; s/["'\'']$//')"
  case "$reference" in
    ./*|docker://*) continue ;;                      # an action in this repository, or an image
    actions/*@*) continue ;;                         # GitHub's own; a major tag is allowed
  esac
  if [[ ! "$reference" =~ @[0-9a-f]{40}$ ]]; then
    printf 'error: %s:%s uses %s, a third-party action not pinned to a full commit SHA. Pin it (owner/action@<40-character SHA>) or do the step without it.\n' \
      "${file#"$ROOT"/}" "$line" "$reference" >&2
    failed=1
  fi
done < <(grep -n -H -E '^[[:space:]]*(-[[:space:]]*)?uses:' "$ROOT"/.github/workflows/*.y*ml || true)

[ "$failed" -eq 0 ] || fail "a workflow has problems (listed above)."
say "workflows: $(find "$ROOT/.github/workflows" -name '*.y*ml' | wc -l | tr -d ' ') checked, no problem"
