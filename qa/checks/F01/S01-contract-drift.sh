#!/usr/bin/env bash
# F01-S01, AC-8: the contract drift check.
#
# Cases: TC-F01-S01-052 to TC-F01-S01-054. Expected results from AC-8: "pnpm contracts:check exits
# zero on the committed tree. Run in a copy of the repository in which docs/api/openapi.json or a
# generated client file has been altered, it exits non-zero and names the stale file."
#
# Nothing in the repository is altered. The alterations are made in a copy of the committed tree
# in a temporary folder, which is deleted when the check ends.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool git tar node pnpm docker

DOCUMENT="docs/api/openapi.json"
CLIENT="packages/api-contracts/src/generated/schema.ts"
COPY="$(mktemp -d)/repository"
cleanup() { rm -rf "$(dirname "$COPY")"; }
trap cleanup EXIT

docker info >/dev/null 2>&1 || environment_fault "Docker is not running; the contract check starts the API's document test, which needs it"

# run_check <directory>: runs the check there; sets CHECK_EXIT and CHECK_OUTPUT.
run_check() {
  CHECK_OUTPUT="$(cd "$1" && pnpm contracts:check 2>&1)"
  CHECK_EXIT=$?
}

tree_state() { git -C "$REPO_ROOT" status --porcelain -- docs/api packages apps; }

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-052 "pnpm contracts:check exits zero on the committed tree"
expect_equal "uncommitted changes to the contract or the product before the check" "" "$(tree_state)"
# The contract generator repackages the runtime jar. Keep the live API stopped while it does.
"$SCRIPTS/local-service.sh" stop api >"$REPORT_DIR/contract-stop-api.log" 2>&1 || environment_fault "API could not be stopped before contract generation"
run_check "$REPO_ROOT"
"$SCRIPTS/local-service.sh" start api >"$REPORT_DIR/contract-start-api.log" 2>&1 || environment_fault "API could not restart after contract generation"
printf '%s\n' "$CHECK_OUTPUT" >"$REPORT_DIR/contracts-check-committed.log"
expect_equal "exit code" 0 "$CHECK_EXIT"
expect_equal "changes the check made to the repository" "" "$(tree_state)"

# The copy every alteration is made in: the committed tree, installed as the handoff says.
committed_copy "$COPY"
( cd "$COPY" && pnpm install --frozen-lockfile >"$REPORT_DIR/contracts-copy-install.log" 2>&1 ) \
  || environment_fault "pnpm install --frozen-lockfile failed in the copy; see $REPORT_DIR/contracts-copy-install.log"
cp "$COPY/$DOCUMENT" "$COPY/$DOCUMENT.committed"
cp "$COPY/$CLIENT" "$COPY/$CLIENT.committed"
mv "$COPY/$DOCUMENT.committed" "$(dirname "$COPY")/openapi.json.committed"
mv "$COPY/$CLIENT.committed" "$(dirname "$COPY")/schema.ts.committed"
restore_copy() {
  cp "$(dirname "$COPY")/openapi.json.committed" "$COPY/$DOCUMENT"
  cp "$(dirname "$COPY")/schema.ts.committed" "$COPY/$CLIENT"
  rm -f "$COPY/packages/api-contracts/src/generated/by-hand.ts"
}
# The copy itself passes, so a failure below is the alteration's doing and nothing else's.
run_check "$COPY"
printf '%s\n' "$CHECK_OUTPUT" >"$REPORT_DIR/contracts-check-copy.log"
expect_equal "exit code in an unaltered copy of the committed tree" 0 "$CHECK_EXIT"

# alteration <label> <file the check must name>: runs the check in the altered copy and restores it.
alteration() {
  local label="$1" named="$2" before after
  before="$(cksum "$COPY/$named" 2>/dev/null || echo missing)"
  run_check "$COPY"
  printf '%s\n' "$CHECK_OUTPUT" >"$REPORT_DIR/contracts-check-$(printf '%s' "$label" | tr ' ' '-').log"
  after="$(cksum "$COPY/$named" 2>/dev/null || echo missing)"
  expect_not_equal "$label: exit code" 0 "$CHECK_EXIT"
  expect_contains "$label: the output names the stale file" "$named" "$CHECK_OUTPUT"
  expect_equal "$label: the altered file, which the check reports and does not rewrite" "$before" "$after"
  restore_copy
}

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-053 "With docs/api/openapi.json altered, pnpm contracts:check exits non-zero and names it"
sed -i.bak 's/"time"/"timestamp"/g' "$COPY/$DOCUMENT" && rm -f "$COPY/$DOCUMENT.bak"
cmp -s "$COPY/$DOCUMENT" "$(dirname "$COPY")/openapi.json.committed" && wrong "the document was not altered, so this proves nothing" || right "a field of the document was renamed in the copy"
alteration "a field renamed in the document" "$DOCUMENT"
# The document describing an endpoint the API does not have: stale in the other direction.
node -e '
  const fs = require("fs"); const file = process.argv[1];
  const document = JSON.parse(fs.readFileSync(file, "utf8"));
  document.paths["/api/v1/ghost"] = document.paths["/api/v1/meta"];
  fs.writeFileSync(file, JSON.stringify(document, null, 2) + "\n");
' "$COPY/$DOCUMENT"
alteration "an endpoint added to the document by hand" "$DOCUMENT"
rm -f "$COPY/$DOCUMENT"
alteration "the document deleted" "$DOCUMENT"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S01-054 "With a generated client file altered, pnpm contracts:check exits non-zero and names it"
sed -i.bak 's/time: string/time: number/' "$COPY/$CLIENT" && rm -f "$COPY/$CLIENT.bak"
cmp -s "$COPY/$CLIENT" "$(dirname "$COPY")/schema.ts.committed" && wrong "the client was not altered, so this proves nothing" || right "a type in the generated client was changed in the copy"
alteration "a type changed in the generated client" "$CLIENT"
printf '\n// edited by hand\n' >>"$COPY/$CLIENT"
alteration "a comment added to the generated client" "$CLIENT"
rm -f "$COPY/$CLIENT"
alteration "the generated client deleted" "$CLIENT"
printf 'export const byHand = true;\n' >"$COPY/packages/api-contracts/src/generated/by-hand.ts"
alteration "a hand-written file added beside the generated client" "packages/api-contracts/src/generated/by-hand.ts"

expect_equal "changes this check made to the repository" "" "$(tree_state)"
finish
