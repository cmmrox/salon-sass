#!/usr/bin/env bash
# Every script that runs Java asks for the `api` prerequisite first, so that RESOLVED_JAVA_HOME is
# set before it is read, and a missing JDK 25 is reported the way local-service.sh reports it
# (BUG-F03-007). Starts nothing and touches no database: Docker is replaced by a stub that fails.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
failures=0
check() { if [ "$1" = ok ]; then printf 'PASS %s\n' "$2"; else printf 'FAIL %s\n' "$2"; failures=$((failures + 1)); fi; }

# 1. Static: a script reading RESOLVED_JAVA_HOME (outside the library that sets it) names `api`.
for script in "$ROOT"/infra/scripts/*.sh; do
  name="${script#"$ROOT"/}"
  case "$name" in infra/scripts/test-*) continue ;; esac
  grep -q 'RESOLVED_JAVA_HOME' "$script" || continue
  if grep -Eq '^[[:space:]]*require_prerequisites([[:space:]].*)?[[:space:]]api([[:space:]]|$)' "$script"; then
    check ok "$name asks for the api prerequisite before reading RESOLVED_JAVA_HOME"
  else
    check fail "$name reads RESOLVED_JAVA_HOME without asking for the api prerequisite"
  fi
done

# 2. Behaviour: with no JDK 25 to be found, the operator bootstrap stops at the prerequisite check,
#    names the missing JDK, and never reaches an unset variable or the database.
mkdir -p "$test_dir/bin"
printf '#!/bin/sh\nexit 1\n' >"$test_dir/bin/docker"
chmod +x "$test_dir/bin/docker"
touch "$test_dir/account.properties"
chmod 600 "$test_dir/account.properties"
set +e
PATH="$test_dir/bin:$PATH" JAVA25_HOME="$test_dir/no-jdk" \
  "$ROOT/infra/scripts/operator-bootstrap.sh" "$test_dir/account.properties" >"$test_dir/out.log" 2>&1
status=$?
set -e
output="$(cat "$test_dir/out.log")"
[ "$status" -ne 0 ] && check ok "operator-bootstrap.sh exits non-zero without JDK 25" \
  || check fail "operator-bootstrap.sh exited 0 without JDK 25"
case "$output" in *"JDK 25 is required"*"JAVA25_HOME points at"*) check ok "it names the missing JDK 25" ;;
  *) check fail "it does not name the missing JDK 25: $output" ;; esac
case "$output" in *"Nothing was started"*) check ok "it stops at the prerequisite check" ;;
  *) check fail "it went past the prerequisite check: $output" ;; esac
case "$output" in *"unbound variable"*) check fail "it read an unset variable: $output" ;;
  *) check ok "no unset variable is read" ;; esac

[ "$failures" -eq 0 ] || { printf '%s check(s) failed\n' "$failures" >&2; exit 1; }
