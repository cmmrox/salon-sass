#!/usr/bin/env bash
# Every API setting that .env.stack.example lists reaches the containers that read it (the review of PR #30): a
# setting the API reads (a ${SALON_...} placeholder of its application*.yaml) that is listed in .env.stack.example
# but not forwarded by infra/stack/compose.yaml would be set by an operator and silently ignored, as the earlier
# encryption keys would have been after a key change. Checks the resolved configuration, for the local and the
# staging profile, with a marker value for each listed setting, so the value set in the file is the value the
# container receives. Then checks the check: a listed setting that is not forwarded fails it.
# Resolves the configuration only; starts nothing.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/stack.sh"
require_docker
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
failures=0
check() { if [ "$1" = ok ]; then printf 'PASS %s\n' "$2"; else printf 'FAIL %s\n' "$2"; failures=$((failures + 1)); fi; }

# The API's settings that the stack's example lists. The local profile's own placeholders (application-local.yaml)
# belong to a developer's machine, never to the stack.
api_settings="$(find "$ROOT/apps/api/src/main/resources" -maxdepth 1 -name 'application*.yaml' \
  ! -name 'application-local.yaml' -exec grep -oh '\${SALON_[A-Z0-9_]*' {} + | tr -d '${' | sort -u)"
listed="$(grep -oE '^SALON_[A-Z0-9_]+=' "$ROOT/.env.stack.example" | tr -d '=' | sort -u)"
api_listed="$(comm -12 <(printf '%s\n' "$api_settings") <(printf '%s\n' "$listed"))"
[ -n "$api_listed" ] && check ok "$(printf '%s\n' "$api_listed" | wc -l | tr -d ' ') API settings are listed in .env.stack.example" \
  || check fail "no API setting found in .env.stack.example"

# A disposable environment: every secret a fixture, every listed API setting a marker of its own name.
sed -E -e 's/^(SALON_[A-Z_]*(PASSWORD|KEY))=$/\1=fixture/' "$ROOT/.env.stack.example" >"$test_dir/env"
printf 'SALON_%s_VERSION=fixture\n' MIGRATE API_1 API_2 WEB_1 WEB_2 WEB_SURGE >>"$test_dir/env"
while read -r name; do
  [ -n "$name" ] && printf '%s=marker-%s\n' "$name" "$name" >>"$test_dir/env"
done <<<"$api_listed"

# Prints each listed API setting that does not reach, with its marker, the containers that read it: both API
# instances, or only the migration container for the owning role's credentials, which the instances are never given
# (docs/engineering/backend.md, "Health and service metadata"). Nothing when all arrive.
missing() {
  STACK_ENV_FILE="$test_dir/env"
  STACK_DEPLOYED="$test_dir/none"
  stack_compose --profile migrate config --format json >"$test_dir/config.json"
  python3 - "$test_dir/config.json" <<PY
import json,sys
config=json.load(open(sys.argv[1]))
names="""$api_listed""".split()
migration_only={'SALON_DB_USER','SALON_DB_PASSWORD'}
for name in names:
    for service in (('migrate',) if name in migration_only else ('api-1','api-2')):
        environment=config['services'][service].get('environment') or {}
        if environment.get(name)!=f'marker-{name}':
            print(f'{service}: {name}')
PY
}

for SALON_STACK_ENV in local staging; do
  if gaps="$(missing)" && [ -z "$gaps" ]; then
    check ok "$SALON_STACK_ENV: every API setting listed in .env.stack.example reaches the containers that read it"
  else
    check fail "$SALON_STACK_ENV: listed but not forwarded: $(printf '%s ' $gaps)"
  fi
done

# The check fails on a listed setting the stack does not forward: a copy of compose.yaml without one forwarding line.
SALON_STACK_ENV=local
unforwarded="SALON_EXPORT_EARLIER_KEYS"
grep -v "^  $unforwarded:" "$STACK_COMPOSE_FILE" >"$test_dir/compose.yaml"
gaps="$(STACK_COMPOSE_FILE="$test_dir/compose.yaml" missing || true)"
case "$gaps" in *"$unforwarded"*) check ok "a listed setting that is not forwarded ($unforwarded) is found" ;;
  *) check fail "a listed setting that is not forwarded ($unforwarded) passed the check" ;; esac

if [ "$failures" -gt 0 ]; then
  printf '%d failed\n' "$failures"
  exit 1
fi
printf 'every check passed\n'
