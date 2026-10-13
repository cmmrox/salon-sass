#!/usr/bin/env bash
# F01-S02, AC-12: the contract and the generated client for GET /api/v1/salon.
#
# Cases: TC-F01-S02-080 and TC-F01-S02-081. "The OpenAPI document describes GET /api/v1/salon with
# its 200 and 404 responses, and the generated client exposes it; pnpm contracts:check passes."
# That the document describes the endpoint and the running API agrees with it is TC-F01-S02-014,
# and a call through the client at run time is TC-F01-S02-015, both in qa/api/F01/.
#
# Nothing in the repository is altered. The stale contract is made in a copy of the committed tree
# in a temporary folder, and the files that must not compile are written there too.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool git tar node pnpm docker jq

DOCUMENT="docs/api/openapi.json"
CLIENT="packages/api-contracts/src/generated/schema.ts"
SALON_PATH="/api/v1/salon"
WORK="$(mktemp -d)"
COPY="$WORK/repository"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

docker info >/dev/null 2>&1 || environment_fault "Docker is not running; the contract check starts the API's document test, which needs it"

run_check() { CHECK_OUTPUT="$(cd "$1" && pnpm contracts:check 2>&1)"; CHECK_EXIT=$?; }
tree_state() { git -C "$REPO_ROOT" status --porcelain -- docs/api packages apps; }

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-080 "pnpm contracts:check passes on the committed tree, and fails, naming the file, when the salon endpoint is missing from the document or from the client"
expect_equal "uncommitted changes to the contract or the product before the check" "" "$(tree_state)"
expect_equal "the committed document describes GET $SALON_PATH with a 200 and a 404" "200 404" "$(jq -r --arg p "$SALON_PATH" '.paths[$p].get.responses | keys | map(select(. == "200" or . == "404")) | join(" ")' "$REPO_ROOT/$DOCUMENT" 2>/dev/null)"
expect_contains "the committed client" "\"$SALON_PATH\"" "$(cat "$REPO_ROOT/$CLIENT")"
# The contract generator repackages the runtime jar. Keep the live API stopped while it does.
"$SCRIPTS/local-service.sh" stop api >"$REPORT_DIR/contract-stop-api.log" 2>&1 || environment_fault "API could not be stopped before contract generation"
run_check "$REPO_ROOT"
"$SCRIPTS/local-service.sh" start api >"$REPORT_DIR/contract-start-api.log" 2>&1 || environment_fault "API could not restart after contract generation"
printf '%s\n' "$CHECK_OUTPUT" >"$REPORT_DIR/contracts-check-committed.log"
expect_equal "exit code on the committed tree" 0 "$CHECK_EXIT"
expect_equal "changes the check made to the repository" "" "$(tree_state)"

committed_copy "$COPY"
( cd "$COPY" && pnpm install --frozen-lockfile >"$REPORT_DIR/contracts-copy-install.log" 2>&1 ) \
  || environment_fault "pnpm install --frozen-lockfile failed in the copy; see $REPORT_DIR/contracts-copy-install.log"
cp "$COPY/$DOCUMENT" "$WORK/openapi.json.committed"
cp "$COPY/$CLIENT" "$WORK/schema.ts.committed"

# The document without the endpoint: what a developer who added the endpoint and did not regenerate would commit.
node -e '
  const fs = require("fs"); const [file, path] = process.argv.slice(1);
  const document = JSON.parse(fs.readFileSync(file, "utf8"));
  if (!document.paths[path]) { process.exit(3); }
  delete document.paths[path]; delete document.components.schemas.Salon;
  fs.writeFileSync(file, JSON.stringify(document, null, 2) + "\n");
' "$COPY/$DOCUMENT" "$SALON_PATH" || environment_fault "could not remove $SALON_PATH from the copy's document"
expect_equal "the copy's document still describes the endpoint" false "$(jq --arg p "$SALON_PATH" '.paths | has($p)' "$COPY/$DOCUMENT")"
run_check "$COPY"
printf '%s\n' "$CHECK_OUTPUT" >"$REPORT_DIR/contracts-check-document-without-salon.log"
expect_not_equal "exit code with the endpoint missing from the document" 0 "$CHECK_EXIT"
expect_contains "the output names the stale file" "$DOCUMENT" "$CHECK_OUTPUT"
cp "$WORK/openapi.json.committed" "$COPY/$DOCUMENT"

# The client without the endpoint: the document regenerated, the client forgotten.
node -e '
  const fs = require("fs"); const [file, path] = process.argv.slice(1);
  const before = fs.readFileSync(file, "utf8");
  const after = before.split(JSON.stringify(path)).join(JSON.stringify(path + "-renamed"));
  if (after === before) { process.exit(3); }
  fs.writeFileSync(file, after);
' "$COPY/$CLIENT" "$SALON_PATH" || environment_fault "could not remove $SALON_PATH from the copy's client"
run_check "$COPY"
printf '%s\n' "$CHECK_OUTPUT" >"$REPORT_DIR/contracts-check-client-without-salon.log"
expect_not_equal "exit code with the endpoint missing from the generated client" 0 "$CHECK_EXIT"
expect_contains "the output names the stale file" "$CLIENT" "$CHECK_OUTPUT"
cp "$WORK/schema.ts.committed" "$COPY/$CLIENT"
expect_equal "changes this check made to the repository" "" "$(tree_state)"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-081 "The generated client exposes GET /api/v1/salon with its three fields: code that uses them compiles, and code that asks for another path or for a tenant id does not"
# Compiled with QA's own TypeScript against the committed client, in files outside the repository.
INDEX="$REPO_ROOT/packages/api-contracts/src/index"
compile() { # <file>: sets COMPILE_EXIT and COMPILE_OUTPUT
  COMPILE_OUTPUT="$(pnpm --dir "$QA_ROOT" exec tsc --noEmit --strict --skipLibCheck --target ES2023 --module ESNext --moduleResolution Bundler --lib ES2023,DOM "$1" 2>&1)"
  COMPILE_EXIT=$?
}
# A file that fails to compile for another reason (the client not found, a setting of the compiler)
# would look like a pass above: so each refusal must be the one complaint, about the one thing.
expect_only_that_complaint() {
  expect_equal "other complaints from the compiler" 0 "$(printf '%s\n' "$1" | grep -c 'error TS' | awk '{ print $1 - 1 }')"
}
cat >"$WORK/uses-the-endpoint.ts" <<TS
import { createApiClient, type Salon, type Problem } from '$INDEX';
export async function brand(): Promise<[string, string, string]> {
  const client = createApiClient({ baseUrl: 'http://alpha.localhost:8090' });
  const { data, error } = await client.GET('/api/v1/salon');
  const problem: Problem | undefined = error;
  if (!data) { throw new Error(problem?.code); }
  const salon: Salon = data;
  return [salon.slug, salon.name, salon.brandColour];
}
TS
compile "$WORK/uses-the-endpoint.ts"
expect_equal "exit code compiling code that calls GET $SALON_PATH and reads slug, name and brandColour (${COMPILE_OUTPUT:0:300})" 0 "$COMPILE_EXIT"

cat >"$WORK/asks-for-another-path.ts" <<TS
import { createApiClient } from '$INDEX';
export const all = () => createApiClient({ baseUrl: 'http://alpha.localhost:8090' }).GET('/api/v1/salons');
TS
compile "$WORK/asks-for-another-path.ts"
expect_not_equal "exit code compiling a call to /api/v1/salons, which the contract does not describe" 0 "$COMPILE_EXIT"
expect_matches "the compiler's complaint is about the path" 'error TS2345: .*"/api/v1/salons"' "$COMPILE_OUTPUT"
expect_only_that_complaint "$COMPILE_OUTPUT"

cat >"$WORK/asks-for-a-tenant-id.ts" <<TS
import { createApiClient } from '$INDEX';
export async function id() {
  const { data } = await createApiClient({ baseUrl: 'http://alpha.localhost:8090' }).GET('/api/v1/salon');
  return data?.tenantId;
}
TS
compile "$WORK/asks-for-a-tenant-id.ts"
expect_not_equal "exit code compiling code that reads a tenantId from the salon" 0 "$COMPILE_EXIT"
expect_matches "the compiler's complaint is that a salon has no tenantId" "error TS2339: Property 'tenantId' does not exist" "$COMPILE_OUTPUT"
expect_only_that_complaint "$COMPILE_OUTPUT"

cat >"$WORK/writes-to-the-endpoint.ts" <<TS
import { createApiClient } from '$INDEX';
export const rename = () => createApiClient({ baseUrl: 'http://alpha.localhost:8090' }).PUT('/api/v1/salon', { body: { name: 'x' } });
TS
compile "$WORK/writes-to-the-endpoint.ts"
expect_not_equal "exit code compiling a PUT to $SALON_PATH, which the contract does not describe" 0 "$COMPILE_EXIT"
expect_matches "the compiler's complaint is about the call" 'writes-to-the-endpoint\.ts\([0-9]+,[0-9]+\): error TS2345' "$COMPILE_OUTPUT"
expect_only_that_complaint "$COMPILE_OUTPUT"

# QA's own tests, which use the client and the contract's types, compile as a whole.
QA_TYPECHECK="$(pnpm --dir "$QA_ROOT" exec tsc --noEmit 2>&1)"; expect_equal "exit code of QA's typecheck (${QA_TYPECHECK:0:200})" 0 "$?"

finish
