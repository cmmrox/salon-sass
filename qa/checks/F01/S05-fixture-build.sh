#!/usr/bin/env bash
# Preparation for TC-F01-S05-008 only. Product images are built with images-build.sh first.
# Add one transparent migration resource to the next API image; never alter application source.
set -euo pipefail
[ $# -eq 1 ] || { echo 'usage: S05-fixture-build.sh <next-version>' >&2; exit 2; }
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
version="$1"
docker image tag "salon-api:$version" "salon-api:$version-product"
docker build --build-arg "BASE_IMAGE=salon-api:$version-product" -t "salon-api:$version" "$root/qa/checks/F01/fixtures"
work="$(mktemp -d)"
cleanup(){ docker rm "${original:-}" "${fixture:-}" >/dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT
original="$(docker create "salon-api:$version-product")"
fixture="$(docker create "salon-api:$version")"
docker cp "$original:/app/salon-api.jar" "$work/original.jar" >/dev/null
docker cp "$fixture:/app/salon-api.jar" "$work/fixture.jar" >/dev/null
python3 - "$work" <<'PY'
import sys,zipfile
from pathlib import Path
p=Path(sys.argv[1]);a=zipfile.ZipFile(p/'original.jar');b=zipfile.ZipFile(p/'fixture.jar')
assert set(b.namelist())-set(a.namelist())=={'db/migration/V8__qa_rollout_probe.sql'}
for name in a.namelist(): assert a.read(name)==b.read(name),name
print('fixture provenance: all original jar entry bytes unchanged; only QA migration V8 added')
PY
