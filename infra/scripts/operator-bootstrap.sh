#!/usr/bin/env bash
# The explicit offline operator bootstrap for the local environment (F03-S01,
# docs/architecture/operator-console.md): creates one operator account from a properties file that
# only you can read (chmod 600) with email, displayName, role (OPERATOR_ADMIN or OPERATOR_SUPPORT)
# and password, unless that email already has an account. Credentials never go on the command line
# and nothing is printed back. The account enrolls MFA at its first console sign-in.
#
#   infra/scripts/operator-bootstrap.sh <account-file>
#
# PostgreSQL must be running (local-service.sh start postgres); the API jar is built if needed.
set -euo pipefail
. "$(dirname "$0")/lib/common.sh"

[ "$#" -eq 1 ] || fail "usage: infra/scripts/operator-bootstrap.sh <account-file>"
[ -f "$1" ] || fail "no such file: $1"
ACCOUNT_FILE="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"

load_settings
# postgres and api: the bootstrap runs the API jar, with the JDK 25 the api prerequisite finds.
require_prerequisites postgres api
postgres_is_running || fail "PostgreSQL is not running. Start it first: infra/scripts/local-service.sh start postgres"
ensure_application_role
if [ ! -f "$ROOT/apps/api/target/salon-api.jar" ]; then
  say "api: building"
  ( cd "$ROOT/apps/api" && JAVA_HOME="$RESOLVED_JAVA_HOME" ./mvnw -q -DskipTests package ) >"$LOG_DIR/api-build.log" 2>&1 \
    || { tail -n 30 "$LOG_DIR/api-build.log" >&2; fail "the API did not build. See $LOG_DIR/api-build.log"; }
fi
( cd "$ROOT/apps/api" && SALON_OPERATOR_BOOTSTRAP_FILE="$ACCOUNT_FILE" \
    "$RESOLVED_JAVA_HOME/bin/java" -jar target/salon-api.jar --spring.profiles.active="${SPRING_PROFILES_ACTIVE},operator-bootstrap" ) \
  >"$LOG_DIR/operator-bootstrap.log" 2>&1 \
  || { tail -n 30 "$LOG_DIR/operator-bootstrap.log" >&2; fail "the operator bootstrap failed. See $LOG_DIR/operator-bootstrap.log"; }
grep -o 'Operator bootstrap: [^"]*' "$LOG_DIR/operator-bootstrap.log" | tail -n 1
