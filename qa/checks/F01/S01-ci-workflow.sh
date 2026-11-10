#!/usr/bin/env bash
# F01-S01, AC-10, as far as it can be read without GitHub: the workflow file says that a pull
# request runs every verification command, and that a failing command fails it. Since the CI split
# into parallel jobs that means: each command is a step of a job the required check "Verification
# commands" waits for; that check runs if: always() and fails unless every job succeeded; and the
# API's `./mvnw -q verify` shards run every test class between them, each exactly once.
#
# Case: TC-F01-S01-056. Whether GitHub really runs it that way is TC-F01-S01-057, which waits for
# the owner to create the GitHub remote.
#
# The reader (qa/shared/check-ci-workflow.mjs) is proved first on copies of the committed workflow
# in which one thing has been broken. Nothing in the repository is altered.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool git tar node python3

READER="$QA_ROOT/shared/check-ci-workflow.mjs"
WORKFLOW=".github/workflows/ci.yml"
STAGE_YAML="$(grep -rl '^id: F01-S01$' "$REPO_ROOT"/features/*/stages/*/stage.yaml)"
[ -n "$STAGE_YAML" ] || environment_fault "no stage.yaml declares id: F01-S01"
# The API's test sources, which the shards of `./mvnw -q verify` must cover between them.
API_TESTS="$REPO_ROOT/apps/api/src/test/java"
[ -d "$API_TESTS" ] || environment_fault "no API test sources at $API_TESTS"
WORK="$(mktemp -d)"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

# run_reader <workflow file>: sets READER_EXIT and READER_OUTPUT.
run_reader() {
  READER_OUTPUT="$(node "$READER" "$1" "$STAGE_YAML" "$API_TESTS" 2>&1)"
  READER_EXIT=$?
}

# altered <label> <words the reader must say> <python expression rewriting the text `s`>
altered() {
  local label="$1" says="$2" edit="$3" copy
  copy="$WORK/$(printf '%s' "$label" | tr -c 'a-zA-Z0-9' '-')"
  committed_copy "$copy" "$WORKFLOW"
  python3 - "$copy/$WORKFLOW" "$edit" <<'PY'
import re, sys
path, edit = sys.argv[1], sys.argv[2]
s = open(path, encoding='utf-8').read()
changed = eval(edit)
if changed == s:
    sys.exit('the alteration changed nothing')
open(path, 'w', encoding='utf-8').write(changed)
PY
  if [ "$?" -ne 0 ]; then wrong "$label: could not alter the workflow in the copy, so this proves nothing"; return; fi
  run_reader "$copy/$WORKFLOW"
  expect_equal "$label: exit code of the reader" 1 "$READER_EXIT"
  expect_contains "$label: what the reader says" "$says" "$READER_OUTPUT"
}

begin_case TC-F01-S01-056 "The CI workflow runs every verification command on a pull request, and none of them is allowed to fail quietly"

# 1. The reader can fail.
committed_copy "$WORK/unaltered" "$WORKFLOW"
run_reader "$WORK/unaltered/$WORKFLOW"
expect_equal "an unaltered copy: exit code of the reader" 0 "$READER_EXIT"
altered "a command removed" '"pnpm contracts:check" is not run' "re.sub(r'      - name: pnpm contracts:check\n        run: pnpm contracts:check\n\n', '', s)"
altered "a command that may fail" '"pnpm -r lint" is not run' "s.replace('run: pnpm -r lint', 'run: pnpm -r lint || true')"
altered "a command with continue-on-error" 'continue-on-error' "s.replace('        run: pnpm -r test\n', '        run: pnpm -r test\n        continue-on-error: true\n')"
altered "a command run only sometimes" 'runs only if' "s.replace('        run: pnpm -r typecheck\n', '        run: pnpm -r typecheck\n        if: github.event_name == \\'push\\'\n')"
altered "no pull_request trigger" 'does not run on pull_request' "s.replace('  pull_request:\n', '')"
altered "pull requests narrowed by path" 'narrowed by "paths"' "s.replace('  pull_request:\n', '  pull_request:\n    paths: [apps/**]\n')"
altered "two commands swapped" 'out of order' "s.replace('run: pnpm -r lint', 'run: SWAP').replace('run: pnpm -r typecheck', 'run: pnpm -r lint').replace('run: SWAP', 'run: pnpm -r typecheck')"
altered "the whole job allowed to fail" 'continue-on-error' "s.replace('    runs-on: ubuntu-latest\n', '    runs-on: ubuntu-latest\n    continue-on-error: true\n')"
# Parallel jobs (since the CI split): the required check must wait for each job, always run, and fail
# unless all succeeded; the API's shards must run every test class between them.
altered "a job dropped from the check's needs" 'does not wait for' "s.replace('needs: [web-checks, web-tests, web-a11y, web-build, tooling, contracts, api, images]', 'needs: [web-checks, web-tests, web-a11y, web-build, tooling, api, images]')"
altered "the API's job dropped from the check's needs" '"cd apps/api && ./mvnw -q verify" is run only by job(s) api' "s.replace('tooling, contracts, api, images]', 'tooling, contracts, images]')"
altered "the check not run if: always()" 'if: always()' "s.replace('    if: always()\n', '')"
altered "the check that does not test the results" 'every job it waits for succeeded' "s.replace('all(.value.result == \"success\")', 'any(.value.result == \"success\")')"
altered "the required check renamed" 'job(s) named "Verification commands"' "s.replace('name: Verification commands', 'name: Verify')"
altered "a shard pattern removed" 'no shard of job "api" runs' "s.replace('com/salonplatform/identity/**,com/salonplatform/catalogue/**', 'com/salonplatform/identity/**')"
altered "an exclusion missing from the rest shard" 'more than one shard of job "api" runs' "s.replace(',!com/salonplatform/catalogue/**', '')"
altered "a shard's tests not taken from the matrix" 'is not ${{ matrix.shard.tests }}' "s.replace('TESTS: \${{ matrix.shard.tests }}', 'TESTS: com/salonplatform/staff/**')"

# 2. The committed workflow, against the stage's verification commands.
run_reader "$REPO_ROOT/$WORKFLOW"
printf '%s\n' "$READER_OUTPUT" >"$REPORT_DIR/ci-workflow.log"
note "$(printf '%s' "$READER_OUTPUT" | head -n 1)"
expect_equal "the committed workflow: exit code of the reader" 0 "$READER_EXIT"
[ "$READER_EXIT" -eq 0 ] || printf '%s\n' "$READER_OUTPUT"

finish
