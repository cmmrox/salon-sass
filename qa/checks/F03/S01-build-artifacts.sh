#!/usr/bin/env bash
# TC-F03-S01-040: built web artifacts contain no environment file and no identity, operator,
# billing, export or support key value. Reads the existing build output; never builds, never edits the
# repository. Key values are read from the retained local key files and never printed.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool grep find
BUILD="$PRODUCT_ROOT/apps/web/.next"
[ -d "$BUILD" ] || environment_fault "no web build output at $BUILD; run the developer gate 'pnpm --filter @salon/web build' first"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# One fixed-string pattern per retained key value, in a file only this check reads.
PATTERNS="$WORK/patterns"
: >"$PATTERNS"; chmod 600 "$PATTERNS"
for file in identity-keys operator-keys billing-keys export-keys support-keys; do
  path="$PRODUCT_ROOT/.local/$file.properties"
  [ -f "$path" ] || { [ "$file" = billing-keys ] && continue; environment_fault "retained key file .local/$file.properties is absent"; }
  sed -n 's/^[^=#]*=[[:space:]]*\([0-9a-fA-F]\{32,\}\)[[:space:]]*$/\1/p' "$path" >>"$PATTERNS"
done
KEY_COUNT="$(wc -l <"$PATTERNS" | tr -d ' ')"
[ "$KEY_COUNT" -ge 9 ] || environment_fault "expected at least nine retained key values (identity 4, operator 5), found $KEY_COUNT"

# scan <directory>: prints how many files hold a key value and how many environment/key files exist.
scan() {
  local leaked env
  leaked="$(grep -rlF -f "$PATTERNS" "$1" 2>/dev/null | wc -l | tr -d ' ')"
  env="$(find "$1" \( -name '.env' -o -name '.env.*' -o -name '*keys.properties' \) -print 2>/dev/null | wc -l | tr -d ' ')"
  printf '%s %s' "$leaked" "$env"
}

begin_case TC-F03-S01-040 "Built web artifacts contain no environment files and no identity or operator key value"
note "scanning $(find "$BUILD" -type f | wc -l | tr -d ' ') files for $KEY_COUNT key values"
read -r leaked env <<<"$(scan "$BUILD")"
expect_equal "files in the build holding a key value" 0 "$leaked"
expect_equal "environment or key files in the build" 0 "$env"
# Failure proof on a copy outside the repository: one planted key value and one planted env file.
COPY="$WORK/copy"; mkdir -p "$COPY/static"
find "$BUILD" -type f -name '*.js' | head -n 3 | while read -r f; do cp "$f" "$COPY/static/"; done
{ printf 'const x = "'; head -n 1 "$PATTERNS" | tr -d '\n'; printf '";\n'; } >"$COPY/static/planted.js"
: >"$COPY/.env.local"
read -r leaked env <<<"$(scan "$COPY")"
expect_equal "the scan finds the planted key value" 1 "$leaked"
expect_equal "the scan finds the planted environment file" 1 "$env"
finish
