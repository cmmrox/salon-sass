#!/usr/bin/env bash
set -euo pipefail
node "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/verify-deployment.mjs"
