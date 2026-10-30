#!/usr/bin/env bash
# Regression check for BUG-F01-009: the edge keeps a busy instance that still answers, and spreads
# requests by how quickly each instance answers. Runs the `(instances)` snippet of each production
# Caddyfile, unchanged, in front of mock instances, from the pinned tool images:
#
#   1. An only instance whose health check answers, but slowly (3 s, as a saturated web server
#      answers it), stays in the rotation: requests keep reaching it instead of a 503 with no
#      instance left.
#   2. Of two instances, one answering a request in a second (one just started, or busy) and one at
#      once, the slow one is given a small share of 20 concurrent clients' requests, not half of them
#      with a queue behind it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CADDY_IMAGE="caddy:2.11.7-alpine"
NODE_IMAGE="node:24.22.2-alpine3.24"
suffix="$$"
network="salon-edge-busy-test-${suffix}"
containers=()
tmp="$(mktemp -d)"
cleanup() {
  [ "${#containers[@]}" -eq 0 ] || docker rm --force "${containers[@]}" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf "$tmp"
}
trap cleanup EXIT
failures=0
check() { if [ "$1" = ok ]; then printf 'PASS %s\n' "$2"; else printf 'FAIL %s\n' "$2"; failures=$((failures + 1)); fi; }

command -v docker >/dev/null 2>&1 || { echo "Docker is required" >&2; exit 1; }
docker image inspect "$CADDY_IMAGE" >/dev/null
docker image inspect "$NODE_IMAGE" >/dev/null
docker network create "$network" >/dev/null

# A mock instance: /healthz answers after HEALTH_MS, every other path after REQUEST_MS, and
# /test/count says how many requests (not health checks) it has answered.
mock() { # <name> <health-ms> <request-ms>
  docker run --detach --name "$1-$suffix" --network "$network" --network-alias "$1" \
    -e HEALTH_MS="$2" -e REQUEST_MS="$3" "$NODE_IMAGE" node -e '
      const http=require("node:http"); let count=0;
      const health=Number(process.env.HEALTH_MS), request=Number(process.env.REQUEST_MS);
      http.createServer((req,res)=>{
        if(req.url==="/healthz"){ setTimeout(()=>res.end("UP"),health); return; }
        if(req.url==="/test/count"){ res.end(String(count)); return; }
        count++; setTimeout(()=>res.end("ok"),request);
      }).listen(3000,"0.0.0.0");' >/dev/null
  containers+=("$1-$suffix")
}

# The edge: the production snippet, imported as each site of the Caddyfiles does.
edge() { # <caddyfile> <upstream...>
  local source_file="$ROOT/infra/stack/edge/$1"; shift
  awk '/^\(instances\) \{/{on=1} on{print} on&&/^\}/{exit}' "$source_file" >"$tmp/Caddyfile"
  grep -q 'lb_policy' "$tmp/Caddyfile" || { echo "$source_file has no (instances) snippet" >&2; exit 1; }
  cat >>"$tmp/Caddyfile" <<EOF
:8080 {
	reverse_proxy $* {
		import instances /healthz
	}
}
EOF
  docker run --detach --name "edge-$suffix" --network "$network" \
    --volume "$tmp/Caddyfile:/etc/caddy/Caddyfile:ro" "$CADDY_IMAGE" \
    caddy run --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null
  containers+=("edge-$suffix")
}

# A client on the stack's network: node runs <script> with the edge's address in EDGE.
client() { # <script>
  docker run --rm --network "$network" -e EDGE="http://edge-$suffix:8080" "$NODE_IMAGE" node -e "$1"
}

# Removes containers this test started, and forgets them.
remove() {
  docker rm --force "$@" >/dev/null 2>&1 || true
  local name keep=()
  for name in "${containers[@]}"; do
    case " $* " in *" $name "*) ;; *) keep+=("$name") ;; esac
  done
  containers=(${keep[@]+"${keep[@]}"})
}

for caddyfile in Caddyfile Caddyfile.staging; do
  # 1. An only instance, busy: its health check takes 3 s, requests are answered at once.
  mock busy 3000 0
  edge "$caddyfile" busy:3000
  # Long enough for several consecutive slow checks (one a second); then every request must
  # still reach it.
  result="$(client '
    const wait=ms=>new Promise(r=>setTimeout(r,ms)); const statuses=[];
    (async()=>{ await wait(1000);
      for(let i=0;i<18;i++){ try{ statuses.push((await fetch(process.env.EDGE+"/")).status); }catch(e){ statuses.push(0); } await wait(500); }
      console.log(statuses.join(" ")); })();')"
  if [[ " $result " == *" 200 "* ]] && [[ ! " $result " =~ \ (0|5[0-9][0-9])\  ]]; then
    check ok "$caddyfile keeps an only instance whose health check answers slowly in the rotation"
  else
    check fail "$caddyfile took a busy but answering instance out of the rotation (statuses: $result)"
  fi
  remove "busy-$suffix" "edge-$suffix"

  # 2. Two instances: one answers each request in a second, the other at once.
  mock slow 0 1000
  mock fast 0 0
  edge "$caddyfile" slow:3000 fast:3000
  result="$(client '
    const wait=ms=>new Promise(r=>setTimeout(r,ms)); let failed=0;
    (async()=>{ await wait(2500); const end=Date.now()+4000;
      await Promise.all(Array.from({length:20},async()=>{ while(Date.now()<end){ try{ const r=await fetch(process.env.EDGE+"/"); await r.text(); if(r.status!==200) failed++; }catch{ failed++; } } }));
      const count=async host=>Number(await (await fetch("http://"+host+":3000/test/count")).text());
      console.log(await count("slow"), await count("fast"), failed); })();')"
  read -r slow fast failed <<<"$result"
  if [ -n "${fast:-}" ] && [ "${failed:-1}" -eq 0 ] && [ $((slow * 4)) -lt $((slow + fast)) ]; then
    check ok "$caddyfile gives the slow instance a small share ($slow of $((slow + fast)) requests)"
  else
    check fail "$caddyfile gave the slow instance $slow of $((${slow:-0} + ${fast:-0})) requests, failed ${failed:-?} (wanted under a quarter, none failed)"
  fi
  remove "slow-$suffix" "fast-$suffix" "edge-$suffix"
done

[ "$failures" -eq 0 ] || { printf '%s check(s) failed\n' "$failures" >&2; exit 1; }
