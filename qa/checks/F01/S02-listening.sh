#!/usr/bin/env bash
# F01-S02, AC-11 (NFR-07, CR-002): who can reach the local API and the local web app.
#
# Cases: TC-F01-S02-060 to TC-F01-S02-063. "By default, the API's and the web app's ports accept
# connections on this machine's loopback addresses only: a connection to either port on the
# machine's non-loopback network address is refused, while alpha.localhost works from curl, from
# Node.js and from a browser, whether the name resolves to 127.0.0.1 or ::1 first. With the opt-in
# variable named in .env.example and README.md set, both ports are reachable on the network
# address." The browser is TC-F01-S02-064, in qa/web/F01/.
#
# A refused connection only means something on an address where an open port would have been
# reached. So every address of this machine is first tried against a control, a listener of the
# check's own on every interface (shared/network-probe.mjs), and only the addresses that reach the
# control are judged. On a machine where no non-loopback address reaches it, the check stops as an
# environment fault and proves nothing, which is the truth.
#
# The API and the web app are restarted with the opt-in through the developer's script, and
# restarted without it when the check ends, however it ends.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool curl lsof jq node

WORK="$(mktemp -d)"
OPT_IN="SALON_LISTEN_ON_NETWORK"
require_product_up
load_salons

probe() { node "$QA_ROOT/shared/network-probe.mjs" "$API_PORT" "$WEB_PORT" >"$1" 2>"$WORK/probe.err" || environment_fault "could not probe this machine's addresses: $(cat "$WORK/probe.err")"; }
# addresses_where <probe file> <jq condition>: one address per line.
addresses_where() { jq -r ".[] | select($2) | .address" "$1"; }
# What a port is bound to, one address per line: "127.0.0.1", "[::1]" or "*" for every interface.
bound_to() { lsof -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | awk 'NR > 1 { sub(/:[0-9]+$/, "", $9); print $9 }' | sort -u; }

restart_both() { # with the environment given as VAR=value words, or none
  "$SCRIPTS/local-service.sh" stop web >"$WORK/stop-web.log" 2>&1
  "$SCRIPTS/local-service.sh" stop api >"$WORK/stop-api.log" 2>&1
  wait_for_port_closed "$API_PORT" 40 || environment_fault "the API did not stop"
  wait_for_port_closed "$WEB_PORT" 40 || environment_fault "the web app did not stop"
  env "$@" "$SCRIPTS/local-service.sh" start api >"$WORK/start-api.log" 2>&1 || return 1
  env "$@" "$SCRIPTS/local-service.sh" start web >"$WORK/start-web.log" 2>&1 || return 1
}

RESTORE_NEEDED=0
restore() {
  if [ "$RESTORE_NEEDED" = 1 ]; then
    restart_both "$OPT_IN=false" || printf 'ENVIRONMENT: could not restart the API and the web app without %s: %s\n' "$OPT_IN" "$(tail -n 3 "$WORK/start-api.log" "$WORK/start-web.log" 2>/dev/null | tr '\n' ' ')" >&2
    product_is_up || printf 'ENVIRONMENT: the product is not up after the check restored it\n' >&2
  fi
  rm -rf "$WORK"
}
trap restore EXIT

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-060 "By default both ports refuse a connection on every non-loopback address of this machine, and listen on 127.0.0.1 and ::1 only"
probe "$WORK/default.json"
cp "$WORK/default.json" "$REPORT_DIR/network-probe-default.json"
TELLING="$(addresses_where "$WORK/default.json" '.loopback == false and .control == "connected"')"
[ -n "$TELLING" ] || environment_fault "no non-loopback address of this machine reaches even a listener on every interface (see $REPORT_DIR/network-probe-default.json), so a refused connection would prove nothing here"
note "addresses on which an open port would be reached (the control was): $(printf '%s' "$TELLING" | tr '\n' ' ')"
note "addresses that prove nothing, because the control was not reached there either: $(addresses_where "$WORK/default.json" '.loopback == false and .control != "connected"' | tr '\n' ' ')"
while IFS= read -r address; do
  expect_equal "the API's port on $address" refused "$(jq -r --arg a "$address" --arg p "$API_PORT" '.[] | select(.address == $a) | .ports[$p]' "$WORK/default.json")"
  expect_equal "the web app's port on $address" refused "$(jq -r --arg a "$address" --arg p "$WEB_PORT" '.[] | select(.address == $a) | .ports[$p]' "$WORK/default.json")"
done <<<"$TELLING"
# The other half: they are open where they should be, so "refused" above is not a product that is down.
for address in 127.0.0.1 ::1; do
  expect_equal "the API's port on $address" connected "$(jq -r --arg a "$address" --arg p "$API_PORT" '.[] | select(.address == $a) | .ports[$p]' "$WORK/default.json")"
  expect_equal "the web app's port on $address" connected "$(jq -r --arg a "$address" --arg p "$WEB_PORT" '.[] | select(.address == $a) | .ports[$p]' "$WORK/default.json")"
done
# And what the operating system says each port is bound to: both loopback addresses and nothing else.
expect_equal "what the API's port is bound to" "127.0.0.1 [::1]" "$(bound_to "$API_PORT" | tr '\n' ' ' | sed 's/ $//')"
expect_equal "what the web app's port is bound to" "127.0.0.1 [::1]" "$(bound_to "$WEB_PORT" | tr '\n' ' ' | sed 's/ $//')"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-061 "A salon's hostname works from curl and from Node.js on both ports, whether it is reached over IPv4 or IPv6"
API_ALPHA="$(salon_api alpha)"
WEB_ALPHA="$(on_hostname "$WEB_URL" "$ALPHA_HOST")/"
note "this machine resolves $ALPHA_HOST to: $(node -e 'require("node:dns").lookup(process.argv[1], { all: true }, (error, found) => console.log(error ? error.code : found.map((entry) => entry.address).join(", ")))' "$ALPHA_HOST")"
for family in "" -4 -6; do
  label="curl ${family:-with the order the system gives}"
  read -r status remote <<<"$(curl -s $family -o "$WORK/curl.json" -w '%{http_code} %{remote_ip}' --max-time 10 "$API_ALPHA")"
  expect_equal "$label, the API: status" 200 "$status"
  expect_equal "$label, the API: the salon" "$ALPHA_NAME" "$(jq -r '.name' "$WORK/curl.json" 2>/dev/null)"
  case "$family" in
    -4) expect_equal "$label, the API: the address reached" 127.0.0.1 "$remote" ;;
    -6) expect_equal "$label, the API: the address reached" ::1 "$remote" ;;
  esac
  read -r status remote <<<"$(curl -s $family -o /dev/null -w '%{http_code} %{remote_ip}' --max-time 15 "$WEB_ALPHA")"
  expect_equal "$label, the web app: status" 200 "$status"
done
# Node.js, told which family to use, and left to choose (it tries the first address the system gives).
NODE_RESULTS="$(node "$QA_ROOT/shared/http-by-family.mjs" "$API_ALPHA" "$WEB_ALPHA" 2>&1)"
# node_field <0 for the API, 1 for the web app> <4|6|any|fetch> <3 status | 4 address | 5 body>
node_field() { printf '%s\n' "$NODE_RESULTS" | awk -F'|' -v url="$1" -v way="$2" -v col="$3" '$1 == url && $2 == way { print $col }'; }
node_salon() { printf '%s\n' "$NODE_RESULTS" | awk -F'|' -v way="$1" '$1 == 0 && $2 == way { sub(/^[^|]*\|[^|]*\|[^|]*\|[^|]*\|/, ""); print }' | jq -r '.name' 2>/dev/null; }
expect_equal "Node.js over IPv4, the API: status, address, salon" "200|127.0.0.1|$ALPHA_NAME" "$(node_field 0 4 3)|$(node_field 0 4 4)|$(node_salon 4)"
expect_equal "Node.js over IPv4, the web app: status, address" "200|127.0.0.1" "$(node_field 1 4 3)|$(node_field 1 4 4)"
expect_equal "Node.js over IPv6, the API: status, address, salon" "200|::1|$ALPHA_NAME" "$(node_field 0 6 3)|$(node_field 0 6 4)|$(node_salon 6)"
expect_equal "Node.js over IPv6, the web app: status, address" "200|::1" "$(node_field 1 6 3)|$(node_field 1 6 4)"
expect_equal "Node.js left to choose, the API: status, salon" "200|$ALPHA_NAME" "$(node_field 0 any 3)|$(node_salon any)"
expect_equal "Node.js left to choose, the web app: status" 200 "$(node_field 1 any 3)"
expect_equal "Node.js fetch, the API: status, salon" "200|$ALPHA_NAME" "$(node_field 0 fetch 3)|$(node_salon fetch)"
expect_equal "Node.js fetch, the web app: status" 200 "$(node_field 1 fetch 3)"
# Every salon, not only alpha.
for slug in $SALON_SLUGS; do
  expect_equal "curl, $slug's hostname on the API" "$(salon_value "$slug" NAME)" "$(curl -s --max-time 10 "$(salon_api "$slug")" | jq -r '.name' 2>/dev/null)"
done

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-062 "The opt-in is named in .env.example and README.md, is off by default, and any value but true or false stops the scripts without starting anything"
expect_contains ".env.example" "$OPT_IN=false" "$(cat "$REPO_ROOT/.env.example")"
expect_contains "README.md" "$OPT_IN=true" "$(cat "$REPO_ROOT/README.md")"
expect_matches "README.md says what turning it on exposes" 'network' "$(grep -n -A2 -B2 "$OPT_IN" "$REPO_ROOT/README.md")"
BOUND_BEFORE="$(bound_to "$API_PORT" | tr '\n' ' ')|$(bound_to "$WEB_PORT" | tr '\n' ' ')|$(listener_pid "$API_PORT")|$(listener_pid "$WEB_PORT")"
for value in yes 1 TRUE True on 0.0.0.0 "true "; do
  for service in api web; do
    OUT="$(env "$OPT_IN=$value" "$SCRIPTS/local-service.sh" start "$service" 2>&1)"; CODE=$?
    expect_not_equal "exit code of 'start $service' with $OPT_IN='$value'" 0 "$CODE"
    expect_contains "its message, with $OPT_IN='$value'" "$OPT_IN" "$OUT"
  done
done
OUT="$(env "$OPT_IN=maybe" "$SCRIPTS/local-up.sh" 2>&1)"; CODE=$?
expect_not_equal "exit code of local-up.sh with $OPT_IN='maybe'" 0 "$CODE"
expect_contains "its message" "$OPT_IN" "$OUT"
expect_equal "what the ports are bound to, and by which processes, after every refused start" "$BOUND_BEFORE" "$(bound_to "$API_PORT" | tr '\n' ' ')|$(bound_to "$WEB_PORT" | tr '\n' ' ')|$(listener_pid "$API_PORT")|$(listener_pid "$WEB_PORT")"

# ---------------------------------------------------------------------------------------------
begin_case TC-F01-S02-063 "With the opt-in set, both ports are reachable on the network address; without it again, they are not"
RESTORE_NEEDED=1
if restart_both "$OPT_IN=true"; then
  right "the API and the web app started with $OPT_IN=true"
else
  wrong "the API or the web app did not start with $OPT_IN=true: $(tail -n 3 "$WORK/start-api.log" "$WORK/start-web.log" | tr '\n' ' ')"
fi
probe "$WORK/opt-in.json"
cp "$WORK/opt-in.json" "$REPORT_DIR/network-probe-opt-in.json"
TELLING_NOW="$(addresses_where "$WORK/opt-in.json" '.loopback == false and .control == "connected"')"
[ -n "$TELLING_NOW" ] || environment_fault "no non-loopback address of this machine reaches the control any more; the network changed during the check"
while IFS= read -r address; do
  expect_equal "with the opt-in, the API's port on $address" connected "$(jq -r --arg a "$address" --arg p "$API_PORT" '.[] | select(.address == $a) | .ports[$p]' "$WORK/opt-in.json")"
  expect_equal "with the opt-in, the web app's port on $address" connected "$(jq -r --arg a "$address" --arg p "$WEB_PORT" '.[] | select(.address == $a) | .ports[$p]' "$WORK/opt-in.json")"
done <<<"$TELLING_NOW"
expect_equal "with the opt-in, what the API's port is bound to" "*" "$(bound_to "$API_PORT" | tr '\n' ' ' | sed 's/ $//')"
expect_equal "with the opt-in, what the web app's port is bound to" "*" "$(bound_to "$WEB_PORT" | tr '\n' ' ' | sed 's/ $//')"
# Reachable is more than a port that connects: a salon is served there, as a phone on the network would ask for it.
NETWORK_ADDRESS="$(printf '%s\n' "$TELLING_NOW" | grep -v '%' | head -n 1)"
if [ -n "$NETWORK_ADDRESS" ]; then
  case "$NETWORK_ADDRESS" in *:*) AT="[$NETWORK_ADDRESS]" ;; *) AT="$NETWORK_ADDRESS" ;; esac
  expect_equal "with the opt-in, alpha's brand asked for on $NETWORK_ADDRESS" "$ALPHA_NAME" "$(curl -s -g --max-time 10 -H "Host: $ALPHA_HOST:$API_PORT" "http://$AT:$API_PORT/api/v1/salon" | jq -r '.name' 2>/dev/null)"
  expect_equal "with the opt-in, the web app asked for on $NETWORK_ADDRESS" 200 "$(curl -s -g -o /dev/null -w '%{http_code}' --max-time 15 "http://$AT:$WEB_PORT/")"
  # The address is not a salon's hostname: it reaches the API and names no salon.
  expect_equal "with the opt-in, the salon endpoint asked for by the bare address" 404 "$(curl -s -g -o /dev/null -w '%{http_code}' --max-time 10 "http://$AT:$API_PORT/api/v1/salon")"
else
  note "every address that reaches the control is link-local, so no request was sent to one by name"
fi
expect_equal "with the opt-in, alpha's hostname on loopback still works" "$ALPHA_NAME" "$(curl -s --max-time 10 "$API_ALPHA" | jq -r '.name' 2>/dev/null)"

# Without it again: closed as before.
if restart_both "$OPT_IN=false"; then right "the API and the web app started again without the opt-in"; else wrong "they did not start again without the opt-in: $(tail -n 3 "$WORK/start-api.log" "$WORK/start-web.log" | tr '\n' ' ')"; fi
RESTORE_NEEDED=0
probe "$WORK/after.json"
while IFS= read -r address; do
  expect_equal "afterwards, the API's port on $address" refused "$(jq -r --arg a "$address" --arg p "$API_PORT" '.[] | select(.address == $a) | .ports[$p]' "$WORK/after.json")"
  expect_equal "afterwards, the web app's port on $address" refused "$(jq -r --arg a "$address" --arg p "$WEB_PORT" '.[] | select(.address == $a) | .ports[$p]' "$WORK/after.json")"
done <<<"$(addresses_where "$WORK/after.json" '.loopback == false and .control == "connected"')"
expect_equal "afterwards, what the API's port is bound to" "127.0.0.1 [::1]" "$(bound_to "$API_PORT" | tr '\n' ' ' | sed 's/ $//')"
expect_equal "afterwards, what the web app's port is bound to" "127.0.0.1 [::1]" "$(bound_to "$WEB_PORT" | tr '\n' ' ' | sed 's/ $//')"
product_is_up && right "the product is up, as the check found it" || wrong "the product is not up after the check"
expect_equal "afterwards, alpha's hostname" "$ALPHA_NAME" "$(curl -s --max-time 10 "$API_ALPHA" | jq -r '.name' 2>/dev/null)"

finish
