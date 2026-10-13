#!/usr/bin/env bash
# TC-F02-S02-018: isolated process on the local machine, never remote/staging.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool java node curl jq
require_product_up
require_database
WORK="$(mktemp -d)"; PID=""
restore(){ if [ -n "$PID" ];then kill "$PID" 2>/dev/null||true;wait "$PID" 2>/dev/null||true;fi;rm -rf "$WORK"; }
trap restore EXIT
SCRIPTS="${QA_PRODUCT_ROOT:-$REPO_ROOT}/infra/scripts"
. "$SCRIPTS/lib/common.sh"
load_settings
resolve_java_home || environment_fault "installed JDK25 required"
[ -f "$ROOT/apps/api/target/salon-api.jar" ] || environment_fault "lead must prepare final built jar"
PORT="$(node -e 'const n=require("net"),s=n.createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()});')"
export SALON_IDENTITY_SIGNING_KEY="$(node -e 'process.stdout.write(require("crypto").randomBytes(32).toString("hex"))')"
export SALON_IDENTITY_CHALLENGE_KEY="$(node -e 'process.stdout.write(require("crypto").randomBytes(32).toString("hex"))')"
export SALON_IDENTITY_ENCRYPTION_KEY="$(node -e 'process.stdout.write(require("crypto").randomBytes(32).toString("hex"))')"
export SALON_IDENTITY_PROXY_KEY="$(node -e 'process.stdout.write(require("crypto").randomBytes(32).toString("hex"))')"
begin_case TC-F02-S02-018 "Nonlocal code sending refuses without a provider and local hooks remain unavailable"
"$RESOLVED_JAVA_HOME/bin/java" -jar "$ROOT/apps/api/target/salon-api.jar" --spring.profiles.active=qa-nonlocal --server.port="$PORT" --spring.flyway.enabled=false >"$WORK/runtime.log" 2>&1 &
PID=$!
wait_for_code "http://localhost:$PORT/actuator/health" 200 40 || environment_fault "separate configured nonlocal-profile local API not healthy"
load_salons
ID="$(node -e 'console.log(require("crypto").randomUUID())')"
for endpoint in "/local/identity/captures/$ID" "/local/identity/deliveries/$ID";do
 code="$(curl -s --max-time 10 -o "$WORK/error.json" -w '%{http_code}' -H "Host: $ALPHA_HOST" "http://localhost:$PORT$endpoint")"
 expect_equal "nonlocal localhook$endpoint status" 404 "$code";expect_equal "stable code" not_found "$(jq -r '.code' "$WORK/error.json")"
done
for method_path in 'PUT /local/identity/providers/sms' 'DELETE /local/identity/customers';do
 read -r method endpoint <<<"$method_path"
 code="$(curl -s --max-time 10 -o "$WORK/error.json" -w '%{http_code}' -X "$method" -H "Host: $ALPHA_HOST" -H 'Content-Type: application/json' -d '{"failures":0,"delayMs":0}' "http://localhost:$PORT$endpoint")"
 expect_equal "nonlocal$endpoint" 404 "$code"
done
BASELINE="$(printf "select count(*) from identity_customer_challenge where tenant_id='%s';\n" "$ALPHA_ID"|owner_psql)"
for channel in sms whatsapp;do
 code="$(curl -s --max-time 10 -o "$WORK/error.json" -w '%{http_code}' -H "Host: $ALPHA_HOST" -H 'Content-Type: application/json' -d "{\"mobile\":\"0771234567\",\"channel\":\"$channel\",\"deviceLabel\":\"QA device\"}" "http://localhost:$PORT/api/v1/identity/customers/challenges")"
 expect_equal "unconfigured$channel response" 503 "$code";expect_equal "unconfigured provider stable code" provider_unavailable "$(jq -r '.code' "$WORK/error.json")"
done
expect_equal "no challenge was accepted without an adapter" "$BASELINE" "$(printf "select count(*) from identity_customer_challenge where tenant_id='%s';\n" "$ALPHA_ID"|owner_psql)"
node - "$ROOT/docs/api/openapi.json" <<'JS'
const fs=require('fs');const doc=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
const hidden=Object.keys(doc.paths).filter(p=>p.startsWith('/local/identity'));
if(hidden.length){console.log('FAIL TC-F02-S02-018 local identity hook in contract');process.exit(1);}
if(!doc.paths['/api/v1/identity/customers/challenges']){console.log('FAIL TC-F02-S02-018 public route missing');process.exit(1);}
JS
[ "$?" = 0 ]&&right "local hooks excluded from contract,publicauth present"||wrong "local/generatedcontract visibility incorrect"
finish
