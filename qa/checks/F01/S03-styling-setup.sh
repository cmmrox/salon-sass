#!/usr/bin/env bash
# F01-S03, AC-13 (CR-016): Tailwind CSS 4 and shadcn/ui are set up as docs/engineering/web.md,
# "Styling", says, and the styling-rules test guards them.
#
# Case: TC-F01-S03-014. Expected results come from AC-13: postcss.config.mjs (Tailwind's PostCSS
# plugin), components.json, src/app/globals.css as the one style sheet the application imports,
# src/styles/theme.css holding the prototype's final tokens under shadcn/ui's names with --primary
# and --accent reading the salon brand variables, cn() in src/lib/utils.ts; no *.module.css and no
# other style sheet under apps/web/src except the generated plain-page CSS (CR-017). src/styles/styling-rules.test.ts
# passes on the committed tree and fails when a CSS Module, a second style sheet, an arbitrary
# colour class or a hex colour in a component file is introduced.
#
# Nothing in the repository is altered: every violation is made in a copy of the committed
# apps/web in a temporary folder outside the repository, which is deleted when the check ends.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool git tar node

WEB="$REPO_ROOT/apps/web"
VITEST="$WEB/node_modules/vitest/vitest.mjs"
[ -f "$VITEST" ] || environment_fault "apps/web has no installed vitest (pnpm install --frozen-lockfile first)"
SCRATCH="$(mktemp -d)"
COPY="$SCRATCH/repository"
cleanup() { rm -rf "$SCRATCH"; }
trap cleanup EXIT

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S03-014 "Tailwind CSS 4 and shadcn/ui are set up and the styling rules test guards them"

# The committed tree, read from Git so that an uncommitted file can neither help nor hurt.
show() { git -C "$REPO_ROOT" show "HEAD:apps/web/$1" 2>/dev/null; }

postcss="$(show postcss.config.mjs)"
expect_contains "postcss.config.mjs uses Tailwind's PostCSS plugin" "'@tailwindcss/postcss'" "$postcss"
expect_equal "tailwind.config files (Tailwind 4 is configured in CSS)" "" \
  "$(git -C "$REPO_ROOT" ls-tree --name-only HEAD apps/web/ | grep -E 'tailwind\.config' || true)"
expect_matches "package.json pins Tailwind CSS 4" '"tailwindcss": "4\.' "$(show package.json)"

components="$(show components.json)"
read_components() { node -e '
  const c = JSON.parse(require("fs").readFileSync(0, "utf8"));
  process.stdout.write([c.tailwind && c.tailwind.css, c.tailwind && c.tailwind.cssVariables, c.aliases && c.aliases.ui, c.aliases && c.aliases.utils].join(" "));' ; }
expect_equal "components.json: Tailwind css, cssVariables, ui and utils aliases" \
  "src/app/globals.css true @/components/ui @/lib/utils" "$(printf '%s' "$components" | read_components 2>&1)"

globals="$(show src/app/globals.css)"
expect_matches "globals.css imports Tailwind" "^@import ['\"]tailwindcss['\"];" "$globals"
expect_matches "globals.css imports the theme" "^@import ['\"]\.\./styles/theme\.css['\"];" "$globals"

theme="$(show src/styles/theme.css)"
# token <shadcn name> <expected value>: the prototype's final value under shadcn/ui's variable name.
token() {
  local value
  value="$(printf '%s\n' "$theme" | sed -n "s/^[[:space:]]*--$1:[[:space:]]*\([^;]*\);.*/\1/p" | head -n 1)"
  expect_equal "theme.css --$1" "$(printf '%s' "$2" | tr '[:lower:]' '[:upper:]')" "$(printf '%s' "$value" | tr '[:lower:]' '[:upper:]')"
}
token background '#F3F0EB'
token foreground '#111827'
token card '#FFFFFF'
token secondary '#EAE5DC'
token muted-foreground '#6B7280'
token border '#DDD6CA'
token destructive '#B3261E'
token ring '#1D5FD0'
token primary 'var(--color-brand)'
token primary-foreground 'var(--color-on-brand)'
token accent 'var(--color-brand-soft)'

utils="$(show src/lib/utils.ts)"
expect_matches "src/lib/utils.ts exports cn()" '^export function cn\(' "$utils"
expect_contains "cn() merges with tailwind-merge" "tailwind-merge" "$utils"

sheets="$(git -C "$REPO_ROOT" ls-tree -r --name-only HEAD apps/web/src | grep -E '\.(css|scss|sass|less|styl)$' | sort | tr '\n' ' ')"
expect_equal "style sheets under apps/web/src" \
  "apps/web/src/app/globals.css apps/web/src/lib/plain-page/plain-page.generated.css apps/web/src/styles/theme.css " "$sheets"
expect_equal "CSS Modules under apps/web" "" "$(git -C "$REPO_ROOT" ls-tree -r --name-only HEAD apps/web | grep -E '\.module\.(css|scss|sass)$' || true)"
# Imports of a style sheet in application code (tests and type declarations excluded): only the root layout imports one;
# the plain pages read the theme and their Tailwind-generated sheet as text (`?raw`) to inline them (CR-017).
imports="$(git -C "$REPO_ROOT" grep -n -E "(import|require)[^'\"]*['\"][^'\"]+\.(css|scss|sass|less|styl)(\?[^'\"]*)?['\"]" HEAD -- 'apps/web/src/*.ts' 'apps/web/src/*.tsx' \
  | grep -v -E '\.test\.tsx?:|\.d\.ts:' | sed -E 's/^HEAD:apps\/web\///; s/:[0-9]+:/: /' | sort)"
expected_imports="$(printf '%s\n' "src/app/layout.tsx: import './globals.css';" \
  "src/lib/plain-page/plain-page.ts: import generatedSheet from './plain-page.generated.css?raw';" \
  "src/lib/plain-page/plain-page.ts: import themeSheet from '../../styles/theme.css?raw';" | sort)"
# Compare the files and targets only, so a renamed local binding is not a failure.
targets() { sed -E "s/^([^:]+): .*['\"]([^'\"]+)['\"].*/\1 \2/" | sort; }
expect_equal "style sheet imports in application code" "$(printf '%s\n' "$expected_imports" | targets | tr '\n' ' ')" "$(printf '%s\n' "$imports" | targets | tr '\n' ' ')"

# The styling-rules test, on a copy of the committed apps/web with the installed dependencies.
committed_copy "$COPY" apps/web
ln -s "$WEB/node_modules" "$COPY/apps/web/node_modules"
# run_rules <label>: runs only the styling-rules test in the copy; sets RULES_EXIT and RULES_LOG.
run_rules() {
  RULES_LOG="$REPORT_DIR/styling-rules-$1.log"
  ( cd "$COPY/apps/web" && node "$VITEST" run src/styles/styling-rules.test.ts ) >"$RULES_LOG" 2>&1
  RULES_EXIT=$?
}
run_rules committed
expect_equal "styling-rules.test.ts on the unaltered committed copy (exit)" 0 "$RULES_EXIT"
expect_matches "the committed run executed its tests" 'Tests +[0-9]+ passed' "$(cat "$RULES_LOG")"

# violation <label> <file it adds, from apps/web> <content>: the test must fail, naming that file.
violation() {
  local label="$1" file="$2" content="$3"
  mkdir -p "$(dirname "$COPY/apps/web/$file")"
  printf '%s\n' "$content" >"$COPY/apps/web/$file"
  run_rules "$label"
  rm -f "$COPY/apps/web/$file"
  expect_not_equal "styling-rules.test.ts with $label (exit)" 0 "$RULES_EXIT"
  expect_contains "the failure names $file" "$file" "$(cat "$RULES_LOG")"
}
violation css-module src/components/shell/QaProbe.module.css '.probe { color: inherit; }'
violation second-style-sheet src/styles/qa-probe.css ':root { --qa-probe: 1px; }'
violation arbitrary-colour-class src/components/shell/QaProbe.tsx 'export function QaProbe() { return <div className="p-2 bg-[#D6086A]" />; }'
violation hex-colour-in-component src/components/shell/QaProbe.tsx "export const qaProbeBrand = '#D6086A';"
# After the alterations are removed, the copy passes again: each failure was the alteration's doing.
run_rules restored
expect_equal "styling-rules.test.ts on the restored copy (exit)" 0 "$RULES_EXIT"
expect_equal "changes the check made to apps/web in the repository" "" "$(git -C "$REPO_ROOT" status --porcelain -- apps/web)"

finish
