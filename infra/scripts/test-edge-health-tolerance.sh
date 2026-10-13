#!/usr/bin/env bash
# Regression check for a single transient active-health failure ejecting the last ready upstream.
# Uses the same health_fails value as each production Caddyfile and the pinned local tool images.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CADDY_IMAGE="caddy:2.11.7-alpine"
NODE_IMAGE="node:24.21.0-alpine3.24"
suffix="$$"
network="salon-edge-health-test-${suffix}"
mock="salon-edge-health-mock-${suffix}"
proxy="salon-edge-health-proxy-${suffix}"
tmp="$(mktemp -d)"
cleanup() {
  docker rm --force "$proxy" "$mock" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf "$tmp"
}
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || { echo "Docker is required" >&2; exit 1; }
docker image inspect "$CADDY_IMAGE" >/dev/null
docker image inspect "$NODE_IMAGE" >/dev/null
docker network create "$network" >/dev/null

docker run --detach --name "$mock" --network "$network" --network-alias mock -p 127.0.0.1::3000 \
  "$NODE_IMAGE" node -e '
    const http=require("node:http"); let failUntil=0, failures=0;
    http.createServer((req,res)=>{
      if(req.url==="/healthz") { if(Date.now()<failUntil){failures++;res.statusCode=503;} res.end("health"); return; }
      if(req.url==="/test/fail") { failUntil=Date.now()+10000; res.end("failing"); return; }
      if(req.url==="/test/recover") { failUntil=0; res.end("recovered"); return; }
      if(req.url==="/test/state") { res.setHeader("content-type","application/json"); res.end(JSON.stringify({failures,failUntil})); return; }
      res.end("ok");
    }).listen(3000,"0.0.0.0");' >/dev/null

for caddyfile in Caddyfile Caddyfile.staging; do
  source_file="$ROOT/infra/stack/edge/$caddyfile"
  health_fails="$(awk '$1 == "health_fails" { print $2; exit }' "$source_file")"
  [[ "$health_fails" =~ ^[0-9]+$ ]] || { echo "$caddyfile has no numeric health_fails setting" >&2; exit 1; }

  cat >"$tmp/Caddyfile" <<EOF
:8080 {
  reverse_proxy mock:3000 {
    lb_policy round_robin
    lb_retries 2
    lb_try_duration 5s
    lb_try_interval 100ms
    health_uri /healthz
    health_interval 1s
    health_timeout 2s
    health_fails $health_fails
    health_passes 1
  }
}
EOF
  docker run --detach --name "$proxy" --network "$network" -p 127.0.0.1::8080 \
    --volume "$tmp/Caddyfile:/etc/caddy/Caddyfile:ro" "$CADDY_IMAGE" \
    caddy run --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null
  port="$(docker port "$proxy" 8080/tcp | sed 's/.*://')"
  url="http://127.0.0.1:$port"
  ready=0
  for _ in {1..50}; do
    if [[ "$(curl --silent --output /dev/null --write-out '%{http_code}' "$url/")" == 200 ]]; then ready=1; break; fi
    sleep 0.1
  done
  (( ready == 1 )) || { echo "$caddyfile test proxy did not become ready" >&2; exit 1; }

  mock_port="$(docker port "$mock" 3000/tcp | sed 's/.*://')"
  mock_url="http://127.0.0.1:$mock_port"
  curl --fail --silent "$mock_url/test/fail" >/dev/null
  failures=0
  for _ in {1..30}; do
    failures="$(curl --fail --silent "$mock_url/test/state" | sed -E 's/.*"failures":([0-9]+).*/\1/')"
    (( failures >= 1 )) && break
    sleep 0.1
  done
  (( failures >= 1 )) || { echo "$caddyfile test did not observe a transient failed probe" >&2; exit 1; }
  sleep 0.05
  status="$(curl --silent --output /dev/null --write-out '%{http_code}' "$url/")"
  [[ "$status" == 200 ]] || { echo "$caddyfile ejected its only upstream after one transient probe (HTTP $status)" >&2; exit 1; }
  curl --fail --silent "$mock_url/test/recover" >/dev/null
  recovered=0
  for _ in {1..50}; do
    if [[ "$(curl --silent --output /dev/null --write-out '%{http_code}' "$url/")" == 200 ]]; then recovered=1; break; fi
    sleep 0.1
  done
  (( recovered == 1 )) || { echo "$caddyfile test proxy did not recover" >&2; exit 1; }
  docker rm --force "$proxy" >/dev/null
  echo "PASS $caddyfile preserves the only upstream through one transient health-check failure"
done
