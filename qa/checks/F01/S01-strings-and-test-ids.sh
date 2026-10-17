#!/usr/bin/env bash
# F01-S01, AC-9, read from the build: every user-visible string of the web shell comes from a
# translation file, and every element a person can press carries a data-testid.
#
# Case: TC-F01-S01-055. The browser cases (TC-F01-S01-038, -039 and -050) check the screens they
# open; this one reads every component, including ones no screen shows yet.
#
# The scan (qa/shared/scan-web-source.mjs) is proved first: run on copies of the committed web app
# in which one thing has been broken, it must fail and name the file. Nothing in the repository is
# altered; the copies are in a temporary folder that is deleted when the check ends.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool git tar node python3

SCAN="$QA_ROOT/shared/scan-web-source.mjs"
WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT
STATUS="src/components/status/Status.tsx"

# run_scan <web app folder>: sets SCAN_EXIT and SCAN_OUTPUT.
run_scan() {
  SCAN_OUTPUT="$(node "$SCAN" "$1" 2>&1)"
  SCAN_EXIT=$?
}

# altered <label> <file the scan must name> <python expression rewriting the text `s`> [file to rewrite]
altered() {
  local label="$1" named="$2" edit="$3" target="${4:-$2}" copy
  copy="$WORK/$(printf '%s' "$label" | tr -c 'a-zA-Z0-9' '-')"
  committed_copy "$copy" apps/web/src apps/web/messages
  python3 - "$copy/apps/web/$target" "$edit" <<'PY'
import sys
path, edit = sys.argv[1], sys.argv[2]
s = open(path, encoding='utf-8').read()
changed = eval(edit)
if changed == s:
    sys.exit('the alteration changed nothing')
open(path, 'w', encoding='utf-8').write(changed)
PY
  if [ "$?" -ne 0 ]; then wrong "$label: could not alter $target in the copy, so this proves nothing"; return; fi
  run_scan "$copy/apps/web"
  expect_equal "$label: exit code of the scan" 1 "$SCAN_EXIT"
  expect_contains "$label: the scan names the file" "$named" "$SCAN_OUTPUT"
}

begin_case TC-F01-S01-055 "In the web shell's source every user-visible string comes from a translation file and every pressable element has a data-testid"

# 1. The scan can fail: each of these breaks one thing in a copy.
committed_copy "$WORK/unaltered" apps/web/src apps/web/messages
run_scan "$WORK/unaltered/apps/web"
expect_equal "an unaltered copy: exit code of the scan" 0 "$SCAN_EXIT"
altered "a sentence typed into a component" "$STATUS" "s.replace(\"{t('loaded.summary')}\", 'The service is running.')"
altered "a sentence written as an expression" "$STATUS" "s.replace(\"{t('unavailable.retry')}\", \"{'Try again'}\")"
altered "a sentence chosen by a condition" "$STATUS" "s.replace(\"{t('unavailable.title')}\", \"{retryHref ? 'Service unavailable' : t('unavailable.title')}\")"
altered "a spoken attribute typed into a component" "$STATUS" "s.replace('role=\"status\"', 'role=\"status\" aria-label=\"Loading\"')"
altered "a link with its test id removed" "$STATUS" "s.replace(' data-testid=\"status-retry\"', '')"
altered "a link with an empty test id" "$STATUS" "s.replace('data-testid=\"status-retry\"', 'data-testid=\"\"')"
altered "a button added with no test id" "$STATUS" "s.replace('<p data-testid=\"status-unavailable-body\">', '<button type=\"button\" onClick={undefined}>{t(\\'unavailable.retry\\')}</button><p data-testid=\"status-unavailable-body\">')"
altered "a shared Button with its testId removed" "src/components/account/AccountScreen.tsx" "s.replace(' testId=\"account-logout\"', '')"
altered "a broken shared Button forwarding contract" "src/components/account/Controls.tsx" "s.replace(' data-testid={testId}', '')"
# CR-016: shadcn/ui primitives forward the caller's props; the test id is required where they are used.
altered "a shadcn/ui Input used with no test id" "src/components/account/Controls.tsx" "s.replace(' data-testid={controlTestId(name)} aria-invalid', ' aria-invalid', 1)"
altered "a shadcn/ui primitive that no longer forwards its props" "src/components/ui/input.tsx" "s.replace('      {...props}', '', 1)"
altered "the home link of the not-found page with its test id removed" "src/app/not-found.tsx" "s.replace(' data-testid=\"not-found-home\"', '')"
altered "a page title typed into the layout" "src/app/layout.tsx" "s.replace(\"title: t('name')\", \"title: 'Salon'\")"
altered "an empty string in the translation file" "messages/en.json" "s.replace('\"Try again\"', '\"\"')" "messages/en.json"

# Comparisons are boolean conditions, not text. The existing nonempty committed AccountScreen
# contains comparison operands and real Button.testId uses, and must scan clean.
expect_contains "valid comparison/Button fixture exists" "view === 'staff'" "$(cat "$REPO_ROOT/apps/web/src/components/account/AccountScreen.tsx")"
expect_contains "valid wrapped-button fixture exists" 'testId="account-logout"' "$(cat "$REPO_ROOT/apps/web/src/components/account/AccountScreen.tsx")"

# 2. The committed web app.
run_scan "$REPO_ROOT/apps/web"
printf '%s\n' "$SCAN_OUTPUT" >"$REPORT_DIR/scan-web-source.log"
note "$(printf '%s' "$SCAN_OUTPUT" | head -n 1)"
expect_equal "the committed web app: exit code of the scan" 0 "$SCAN_EXIT"
[ "$SCAN_EXIT" -eq 0 ] || printf '%s\n' "$SCAN_OUTPUT"

finish
