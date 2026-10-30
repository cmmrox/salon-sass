#!/usr/bin/env bash
# Resolve the actual Compose configuration through its scrubbed environment; never start services.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/stack.sh"
require_docker
command -v python3 >/dev/null || fail "Python 3 is required for the resolved configuration assertion."
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
python3 - "$ROOT/.env.stack.example" "$test_dir" <<'PY'
import pathlib,sys
source=pathlib.Path(sys.argv[1]).read_text()
# Disposable fixture values only: never read the real environment or print config credentials.
keys=('SALON_DB_ADMIN_PASSWORD','SALON_DB_PASSWORD','SALON_DB_APP_PASSWORD',
      'SALON_IDENTITY_SIGNING_KEY','SALON_IDENTITY_CHALLENGE_KEY',
      'SALON_IDENTITY_ENCRYPTION_KEY','SALON_IDENTITY_PROXY_KEY',
      'SALON_DB_PLATFORM_PASSWORD','SALON_OPERATOR_SIGNING_KEY','SALON_OPERATOR_CHALLENGE_KEY',
      'SALON_OPERATOR_ENCRYPTION_KEY','SALON_OPERATOR_PROXY_KEY','SALON_OPERATOR_FINGERPRINT_KEY',
      'SALON_EXPORT_KEY','SALON_SUPPORT_SIGNING_KEY')
for key in keys:
    assert key+'=\n' in source, key+' is no longer an empty secret in .env.stack.example'
for key in keys:
    source=source.replace(key+'=\n',key+'='+'a'*64+'\n')
source+='\n'+''.join(key+'=volume-config-test\n' for key in
    ('SALON_MIGRATE_VERSION','SALON_API_1_VERSION','SALON_API_2_VERSION',
     'SALON_WEB_1_VERSION','SALON_WEB_2_VERSION'))
# Missing setting, explicit documented default, empty setting and custom setting must resolve.
for name,value in [('missing',None),('default','salon-stack-postgres-data'),
                   ('empty',''),('custom','salon-stack-postgres-volume-config-test')]:
    fixture=source.replace('SALON_STACK_DB_VOLUME=salon-stack-postgres-data\n','')
    if value is not None: fixture+='SALON_STACK_DB_VOLUME='+value+'\n'
    path=pathlib.Path(sys.argv[2])/(name+'.env')
    path.write_text(fixture); path.chmod(0o600)
PY
STACK_DEPLOYED="$test_dir/no-deployed-file"
# A shell override must not reach the stack; only the explicit fixture file may choose the volume.
export SALON_STACK_DB_VOLUME=untrusted-shell-volume
for SALON_STACK_ENV in local staging; do
  for fixture in missing default empty custom; do
    STACK_ENV_FILE="$test_dir/$fixture.env"
    stack_compose --profile migrate config --format json > "$test_dir/$SALON_STACK_ENV-$fixture.json"
  done
done
python3 - "$test_dir" <<'PY'
import json,pathlib,sys
root=pathlib.Path(sys.argv[1])
for profile in ('local','staging'):
    configs={name:json.loads((root/(profile+'-'+name+'.json')).read_text())
             for name in ('missing','default','empty','custom')}
    base=configs['default']
    assert base['volumes']['postgres-data']['name']=='salon-stack-postgres-data'
    assert configs['missing']==base, 'Absent override must preserve the complete default configuration'
    assert configs['empty']==base, 'Empty override must preserve the complete default configuration'
    custom=configs['custom']
    assert custom['volumes']['postgres-data']['name']=='salon-stack-postgres-volume-config-test'
    custom['volumes']['postgres-data']['name']=base['volumes']['postgres-data']['name']
    assert custom==base, 'Only the database volume name may change'
    if profile=='staging':
        assert base['volumes']['edge-data']['name']=='salon-stack-edge-data'
        assert base['volumes']['edge-config']['name']=='salon-stack-edge-config'
print('PASS local/staging missing/default/empty preserve the existing database volume; custom changes only its name; shell override is scrubbed')
PY
