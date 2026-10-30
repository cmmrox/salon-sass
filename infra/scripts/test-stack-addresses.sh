#!/usr/bin/env bash
# The edge never reaches an instance, nor the web app the edge, through a name (BUG-F01-009): a name
# that does not exist (an instance being replaced, web-surge outside a deployment) is resolved by
# the host's resolvers and could point outside the stack. Checks that each Caddyfile proxies only to
# address settings, and that the stack's resolved configuration, for the local and the staging edge
# and two sizes of edge network, gives the edge and every instance a distinct fixed address that
# Docker can never give another container, and hands the edge and the web app those addresses.
# Resolves the configuration only; starts nothing.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/stack.sh"
require_docker
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
failures=0
check() { if [ "$1" = ok ]; then printf 'PASS %s\n' "$2"; else printf 'FAIL %s\n' "$2"; failures=$((failures + 1)); fi; }

# 1. Each Caddyfile names its upstreams by address settings only.
for caddyfile in Caddyfile Caddyfile.staging; do
  named="$(awk '$1 == "reverse_proxy" { for (i = 2; i <= NF; i++) if ($i != "{" && $i !~ /^\{\$SALON_EDGE_[A-Z0-9_]+\}:[0-9]+$/) print $i }' \
    "$ROOT/infra/stack/edge/$caddyfile")"
  [ -z "$named" ] && check ok "$caddyfile proxies to address settings only" \
    || check fail "$caddyfile proxies to a name: $named"
done

# 2. The resolved configuration. Disposable values only: never the real environment file.
for subnet in 10.89.43.0/24 172.30.0.0/16; do
  sed -E -e 's/^(SALON_[A-Z_]*(PASSWORD|KEY))=$/\1=fixture/' \
      -e "s|^SALON_STACK_EDGE_SUBNET=.*|SALON_STACK_EDGE_SUBNET=$subnet|" \
      "$ROOT/.env.stack.example" >"$test_dir/env"
  printf 'SALON_%s_VERSION=fixture\n' MIGRATE API_1 API_2 WEB_1 WEB_2 WEB_SURGE >>"$test_dir/env"
  STACK_ENV_FILE="$test_dir/env"
  STACK_DEPLOYED="$test_dir/none"
  for SALON_STACK_ENV in local staging; do
    stack_compose --profile surge config --format json >"$test_dir/config.json"
    if result="$(python3 - "$test_dir/config.json" "$subnet" "$SALON_STACK_ENV" <<'PY'
import ipaddress,json,sys
config=json.load(open(sys.argv[1])); subnet=ipaddress.ip_network(sys.argv[2]); profile=sys.argv[3]
edge_service='edge-http' if profile=='local' else 'edge-staging'
ipam=config['networks']['edge']['ipam']['config'][0]
dynamic=ipaddress.ip_network(ipam['ip_range'])
assert ipam['subnet']==str(subnet) and dynamic.subnet_of(subnet), ipam
peers={}
for name,service in config['services'].items():
    networks=service.get('networks') or {}
    if 'edge' not in networks: continue
    address=(networks['edge'] or {}).get('ipv4_address')
    assert address, f'{name} has no fixed address on the edge network'
    address=ipaddress.ip_address(address)
    assert address in subnet and address not in dynamic, f'{name} at {address}: not in {subnet} outside {dynamic}'
    peers[name]=address
expected={edge_service,'api-1','api-2','web-1','web-2','web-surge'}
assert set(peers)==expected, sorted(peers)
assert len(set(peers.values()))==len(peers), peers
environment=config['services'][edge_service]['environment']
for key,name in [('SALON_EDGE_API_1','api-1'),('SALON_EDGE_API_2','api-2'),('SALON_EDGE_WEB_1','web-1'),
                 ('SALON_EDGE_WEB_2','web-2'),('SALON_EDGE_WEB_SURGE','web-surge')]:
    assert environment[key]==str(peers[name]), (key,environment[key],peers[name])
for name in ('web-1','web-2','web-surge'):
    url=config['services'][name]['environment']['SALON_API_BASE_URL']
    assert url==f'http://{peers[edge_service]}:8080', (name,url)
assert config['services']['web-surge'].get('restart')=='no', config['services']['web-surge'].get('restart')
print(f'{len(peers)} peers, Docker allocates from {dynamic}')
PY
)"; then
      check ok "$SALON_STACK_ENV edge on $subnet: $result; the edge and the web app use the addresses"
    else
      check fail "$SALON_STACK_ENV edge on $subnet: $result"
    fi
  done
done

# 3. A subnet the addresses cannot be taken from is refused, naming the setting.
for subnet in 10.89.43.0/27 10.89.43.7/24 not-a-range; do
  printf 'SALON_STACK_EDGE_SUBNET=%s\n' "$subnet" >"$test_dir/bad.env"
  STACK_ENV_FILE="$test_dir/bad.env"
  if message="$(edge_addresses 2>&1)"; then
    check fail "subnet $subnet was accepted"
  else
    case "$message" in *SALON_STACK_EDGE_SUBNET*) check ok "subnet $subnet is refused, naming the setting" ;;
      *) check fail "subnet $subnet was refused without naming the setting: $message" ;; esac
  fi
done

[ "$failures" -eq 0 ] || { printf '%s check(s) failed\n' "$failures" >&2; exit 1; }
