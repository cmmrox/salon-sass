#!/usr/bin/env bash
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool curl jq
require_product_up
restore(){ "$SCRIPTS/local-service.sh" stop api >/dev/null 2>&1; "$SCRIPTS/local-service.sh" start api >"$REPORT_DIR/S04-profile-restore.log" 2>&1; }
trap restore EXIT
begin_case TC-F01-S04-016 "Nonlocal profiles expose no test clock probe or metrics and contract describes none"
"$SCRIPTS/local-service.sh" stop api >"$REPORT_DIR/S04-profile-stop.log" 2>&1
# F03 made its own settings required outside local (platform role, operator, export and support keys).
later_feature_nonlocal_settings
with_identity_keys env SPRING_PROFILES_ACTIVE=qa-other "$SCRIPTS/local-service.sh" start api >"$REPORT_DIR/S04-profile-start.log" 2>&1 || environment_fault "nonlocal API did not start; see profile-start log"
for target in /local/ /local/clock /local/probes /local/probes/missing /local/anything /actuator/metrics /actuator/metrics/salon.outbox.dead; do
  for method in GET PUT DELETE POST; do
    body="$REPORT_DIR/S04-hook-refused.json"
    status="$(curl -sS --max-time 10 -X "$method" -H 'Content-Type: application/json' --data '{}' -o "$body" -w '%{http_code}' "$API_URL$target")"
    expect_equal "$method $target" 404 "$status"
    expect_equal "$method $target code" not_found "$(jq -r .code "$body")"
  done
done
expect_equal "contract paths mentioning local or actuator" 0 "$(jq '[.paths | keys[] | select(startswith("/local/") or startswith("/actuator"))] | length' "$REPO_ROOT/docs/api/openapi.json")"
finish
