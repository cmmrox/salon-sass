#!/usr/bin/env bash
# F01-S01, AC-5: /actuator/info outside the local profile.
#
# Case: TC-F01-S01-028. Expected result from AC-5: "Started with any other profile,
# GET /actuator/info returns 404." The local half of AC-5 is TC-F01-S01-019.
#
# The API is restarted with another profile the way the handoff says to, and restarted with the
# local profile when the check ends, however it ends.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool curl jq lsof

WORK="$(mktemp -d)"
INFO="$API_URL/actuator/info"
require_product_up

start_api_with_profile() {
  "$SCRIPTS/local-service.sh" stop api >"$WORK/stop-$1.log" 2>&1
  wait_for_port_closed "$API_PORT" 40 || environment_fault "the API did not stop"
  SALON_TRUSTED_PROXIES=127.0.0.1 SALON_OTLP_ENDPOINT=http://127.0.0.1:43180 SPRING_PROFILES_ACTIVE="$1" "$SCRIPTS/local-service.sh" start api >"$WORK/start-$1.log" 2>&1
}

restore() {
  start_api_with_profile local || printf 'ENVIRONMENT: could not restart the API with the local profile: %s\n' "$(tail -n 5 "$WORK/start-local.log")" >&2
  [ "$(http_code "$INFO")" = 200 ] || printf 'ENVIRONMENT: the API is not back on the local profile (info answers %s)\n' "$(http_code "$INFO")" >&2
}
trap restore EXIT

begin_case TC-F01-S01-028 "Started with a profile other than local, info answers 404 while the API itself is up"
# Proves info exists to be hidden: without this, a 404 below could mean it never worked at all.
expect_equal "before, with the local profile: GET /actuator/info" 200 "$(http_code "$INFO")"

for profile in staging production default; do
  if start_api_with_profile "$profile"; then
    right "$profile: the API started"
  else
    wrong "$profile: the API did not start: $(tail -n 3 "$WORK/start-$profile.log" | tr '\n' ' ')"
    continue
  fi
  # The API is up and serving in this profile, so a 404 from info is the profile's doing.
  expect_equal "$profile: GET /actuator/health" 200 "$(http_code "$API_URL/actuator/health")"
  expect_equal "$profile: GET /api/v1/meta" 200 "$(http_code "$API_URL/api/v1/meta")"
  read -r status seconds content_type <<<"$(timed_get "$INFO" "$WORK/info-$profile.json" 15)"
  expect_equal "$profile: GET /actuator/info" 404 "$status"
  expect_matches "$profile: the 404's content type" '^application/problem\+json' "$content_type"
  expect_equal "$profile: the 404's code" not_found "$(jq -r '.code' "$WORK/info-$profile.json" 2>/dev/null)"
  expect_no_match "$profile: the 404's body" 'java|version|virtual|runtime|jvm|vendor' "$(cat "$WORK/info-$profile.json")"
  expect_equal "$profile: 'info' among the links at /actuator" "" "$(curl -s --max-time 10 "$API_URL/actuator" | jq -r '._links | keys[]' 2>/dev/null | grep -x info || true)"
  note "$profile: /v3/api-docs answers $(http_code "$API_URL/v3/api-docs") (the contract is not required to be hidden; recorded only)"
done

start_api_with_profile local && right "the API started again with the local profile" || wrong "the API did not start again with the local profile"
expect_equal "after, with the local profile again: GET /actuator/info" 200 "$(http_code "$INFO")"
trap - EXIT

finish
