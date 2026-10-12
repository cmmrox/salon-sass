#!/usr/bin/env bash
# F01-S01, AC-1: every prerequisite local-up.sh refuses to start without is one README.md lists
# (BUG-F01-S01-005).
#
# Case: TC-F01-S01-063. AC-1 begins "On a machine with the documented prerequisites", so the
# documented list is the whole list. The reader of the README's table is proved first on a copy
# of README.md with a row removed. Nothing in the repository is altered.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool git tar
# Taking ps away from scripts that do not check for it would let them go on and start the product;
# with it already running they find it so and change nothing.
require_product_up

WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

# The rows of the table under "Prerequisites, on the machine" in "Quick start".
prerequisite_rows() {
  sed -n '/^Prerequisites, on the machine/,/^```/p' "$1" | grep '^|' | grep -v '^| *Needs *|' | grep -v '^| *---'
}

# missing_from <README> <tool...>: the tools no row of the table names in its first cell.
missing_from() {
  local readme="$1" tool rows missing=""; shift
  rows="$(prerequisite_rows "$readme" | cut -d'|' -f2)"
  for tool in "$@"; do
    printf '%s\n' "$rows" | grep -qiE "(^|[^a-z])\`?${tool}\`?([^a-z]|$)" || missing="$missing $tool"
  done
  printf '%s' "${missing# }"
}

TOOLS="JDK Node.js Docker curl ps"

begin_case TC-F01-S01-063 "Every prerequisite local-up.sh refuses to start without is a row of README.md's prerequisites"

# 1. The reader can fail.
committed_copy "$WORK/copy" README.md
expect_equal "an unaltered copy: tools the table does not name" "" "$(missing_from "$WORK/copy/README.md" $TOOLS)"
grep -v '^| curl' "$WORK/copy/README.md" >"$WORK/no-curl.md"
expect_equal "a copy with the curl row removed: tools the table does not name" curl "$(missing_from "$WORK/no-curl.md" $TOOLS)"

# 2. The committed README names each tool, and the table says Corepack is how pnpm comes.
expect_equal "README.md: tools the table does not name" "" "$(missing_from "$REPO_ROOT/README.md" $TOOLS)"
expect_matches "README.md: the table" 'corepack' "$(prerequisite_rows "$REPO_ROOT/README.md")"

# 3. For each tool the script checks that is not on every machine, take it away and see what the
#    script says, and that it points at a list that has it.
for tool in curl ps; do
  path_without "$WORK/bin-no-$tool" "$tool"
  OUT="$(cd "$REPO_ROOT" && PATH="$WORK/bin-no-$tool" infra/scripts/local-up.sh 2>&1)"; CODE=$?
  printf '%s\n' "$OUT" >"$REPORT_DIR/prerequisites-without-$tool.log"
  expect_not_equal "without $tool: exit code of local-up.sh" 0 "$CODE"
  expect_matches "without $tool: what local-up.sh says names it" "(^|[^a-z])'?${tool}'?([^a-z]|$)" "$OUT"
  expect_contains "without $tool: what local-up.sh says points at" "README.md" "$OUT"
  expect_equal "without $tool: README.md's table names it" "" "$(missing_from "$REPO_ROOT/README.md" "$tool")"
done

finish
