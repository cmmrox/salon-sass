#!/usr/bin/env bash
# images-scan.sh tells three outcomes apart (BUG-F01-005): a clean image passes; an image with a
# critical or high vulnerability that has a fix fails and names it; and a scan that could not complete
# fails too, but says so, naming the image and the scanner's own message, and is never reported as
# vulnerabilities. Starts nothing: Docker is replaced by a stub whose `run` plays Trivy's part.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT
failures=0
check() { if [ "$1" = ok ]; then printf 'PASS %s\n' "$2"; else printf 'FAIL %s\n' "$2"; failures=$((failures + 1)); fi; }

# The stub. The image's name says how its scan goes:
#   clean:*       Trivy finds nothing and exits 0
#   vulnerable:*  Trivy lists a fixable HIGH vulnerability and exits with the --exit-code it was given
#   broken:*      Trivy cannot complete the scan: a FATAL line on standard error, exit 1 (its own
#                 exit for a fatal error, whatever --exit-code says)
#   nodocker:*    Docker itself cannot start the scanner: exit 125
mkdir -p "$test_dir/bin"
cat >"$test_dir/bin/docker" <<'STUB'
#!/usr/bin/env bash
case "$1" in
  info) exit 0 ;;
  compose) exit 0 ;;
  image) exit 0 ;;
  run)
    exit_code=0 image=""
    while [ $# -gt 0 ]; do
      case "$1" in
        --exit-code) exit_code="$2"; shift ;;
        clean:*|vulnerable:*|broken:*|nodocker:*) image="$1" ;;
      esac
      shift
    done
    case "$image" in
      clean:*) exit 0 ;;
      vulnerable:*)
        printf '%s (alpine 3.24)\nTotal: 1 (HIGH: 1, CRITICAL: 0)\n| libexample | CVE-2099-0001 | HIGH | fixed | 1.0.0-r0 | 1.0.1-r0 |\n' "$image"
        exit "$exit_code" ;;
      broken:*)
        printf '2026-10-17T09:58:01Z\tFATAL\tFatal error\trun error: image scan error: scan error: scan failed: failed analysis: analyze error: pipeline error: failed to analyze layer (sha256:dacdca36): post analysis error: walk dir error: walk error: context deadline exceeded\n' >&2
        exit 1 ;;
      nodocker:*)
        printf 'docker: Error response from daemon: failed to create task for container.\n' >&2
        exit 125 ;;
    esac
    exit 99 ;;
esac
exit 0
STUB
chmod +x "$test_dir/bin/docker"

scan() { # <name> <image...>: runs images-scan.sh with the stub, keeping its output and status
  local name="$1"; shift
  set +e
  PATH="$test_dir/bin:$PATH" "$ROOT/infra/scripts/images-scan.sh" "$@" >"$test_dir/$name.log" 2>&1
  printf '%s' "$?" >"$test_dir/$name.status"
  set -e
}
status() { cat "$test_dir/$1.status"; }
output() { cat "$test_dir/$1.log"; }
says() { grep -qF -- "$2" "$test_dir/$1.log"; }

scan clean clean:1
[ "$(status clean)" = 0 ] && check ok "a clean image passes" || check fail "a clean image did not pass: $(output clean)"

scan vulnerable vulnerable:1
[ "$(status vulnerable)" != 0 ] && check ok "a fixable vulnerability fails the scan" \
  || check fail "a fixable vulnerability passed the scan"
says vulnerable "CVE-2099-0001" && says vulnerable "vulnerabilities with a fix" && says vulnerable "vulnerable:1" \
  && check ok "it names the vulnerability and the image" || check fail "the vulnerability is not named: $(output vulnerable)"
says vulnerable "could not" && check fail "a vulnerability was reported as a scanner error: $(output vulnerable)" \
  || check ok "a vulnerability is not reported as a scanner error"

scan broken broken:1
[ "$(status broken)" != 0 ] && check ok "a scan that could not complete fails closed" \
  || check fail "a scan that could not complete passed"
says broken "vulnerabilities with a fix" && check fail "a scanner error was reported as vulnerabilities: $(output broken)" \
  || check ok "a scanner error is not reported as vulnerabilities"
says broken "could not complete" && says broken "broken:1" \
  && check ok "it says the scan of that image could not complete" || check fail "the scanner error is not named: $(output broken)"
says broken "context deadline exceeded" \
  && check ok "it repeats the scanner's own message" || check fail "the scanner's message is missing: $(output broken)"

scan nodocker nodocker:1
[ "$(status nodocker)" != 0 ] && ! says nodocker "vulnerabilities with a fix" && says nodocker "could not complete" \
  && check ok "a scanner that could not start is a scanner error too" || check fail "a scanner that could not start: $(output nodocker)"

scan mixed clean:1 vulnerable:2 broken:2
says mixed "vulnerable:2" && says mixed "broken:2" && says mixed "CVE-2099-0001" && [ "$(status mixed)" != 0 ] \
  && ! grep -F "vulnerabilities with a fix" "$test_dir/mixed.log" | grep -qF "broken:2" \
  && ! grep -F "could not complete" "$test_dir/mixed.log" | grep -qF "vulnerable:2" \
  && check ok "several images: each outcome is reported for its own image" || check fail "several images: $(output mixed)"

[ "$failures" -eq 0 ] || { printf '%s check(s) failed\n' "$failures" >&2; exit 1; }
