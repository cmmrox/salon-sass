#!/usr/bin/env bash
# TC-F02-004. SDLC registers this one wrapper because preparation and cleanup must
# surround the actual k6 thresholds, including when either phase fails.
set -euo pipefail
task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$task_root"
corepack pnpm --dir qa exec playwright test api/F02/F02-read-baseline.spec.ts --project api
