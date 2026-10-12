#!/usr/bin/env bash
# F01-S01, AC-1: installing and starting never rewrite the committed pnpm-lock.yaml
# (BUG-F01-S01-004).
#
# Case: TC-F01-S01-062. A pnpm that does not come through Corepack, and is not the pinned version,
# must not change the committed lock file: it stops and says why, and local-up.sh refuses to run
# without Corepack and names it.
#
# Everything happens in a copy of the committed tree outside the repository. The pnpm of another
# version is QA's own dependency (qa/node_modules/pnpm), so the case does not depend on what is
# installed on the machine.
set -u
. "$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)/shared/checks.sh"
require_tool git tar node corepack

OTHER_PNPM="$QA_ROOT/node_modules/pnpm/bin/pnpm.cjs"
[ -f "$OTHER_PNPM" ] || environment_fault "QA's own pnpm is missing at $OTHER_PNPM; run: pnpm --dir qa install --ignore-workspace"
WORK="$(mktemp -d)"
COPY="$WORK/repository"
BIN="$WORK/bin"
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

committed_copy "$COPY"
LOCK_BEFORE="$(cksum <"$COPY/pnpm-lock.yaml")"
PINNED="$(node -p 'require(process.argv[1]).packageManager' "$COPY/package.json")"
OTHER_VERSION="$(node -p 'require(process.argv[1]).version' "$QA_ROOT/node_modules/pnpm/package.json")"
note "the copy pins $PINNED; the other pnpm is $OTHER_VERSION"

# This machine without Corepack, with the other pnpm on the PATH.
path_without "$BIN" corepack
printf '#!/bin/sh\nexec node "%s" "$@"\n' "$OTHER_PNPM" >"$BIN/pnpm"; chmod +x "$BIN/pnpm"
NO_COREPACK="$BIN"

begin_case TC-F01-S01-062 "A pnpm that is not Corepack's never rewrites pnpm-lock.yaml: install stops and says why, and local-up.sh asks for Corepack"
[ "pnpm@$OTHER_VERSION" != "$PINNED" ] && right "the other pnpm ($OTHER_VERSION) is not the pinned one" || wrong "the other pnpm is the pinned version, so this proves nothing"
expect_equal "corepack on the PATH the case uses" "" "$(PATH="$NO_COREPACK" command -v corepack || true)"

INSTALL="$(cd "$COPY" && PATH="$NO_COREPACK" pnpm install --frozen-lockfile 2>&1)"; INSTALL_EXIT=$?
printf '%s\n' "$INSTALL" >"$REPORT_DIR/pinned-pnpm-install.log"
expect_not_equal "exit code of 'pnpm install --frozen-lockfile' with pnpm $OTHER_VERSION" 0 "$INSTALL_EXIT"
expect_contains "what it says names the pinned version" "${PINNED#pnpm@}" "$INSTALL"
expect_equal "pnpm-lock.yaml after that install" "$LOCK_BEFORE" "$(cksum <"$COPY/pnpm-lock.yaml")"

UP="$(cd "$COPY" && PATH="$NO_COREPACK" infra/scripts/local-up.sh 2>&1)"; UP_EXIT=$?
printf '%s\n' "$UP" >"$REPORT_DIR/pinned-pnpm-local-up.log"
expect_not_equal "exit code of local-up.sh without Corepack" 0 "$UP_EXIT"
expect_matches "what local-up.sh says" 'corepack' "$UP"
expect_equal "pnpm-lock.yaml after local-up.sh" "$LOCK_BEFORE" "$(cksum <"$COPY/pnpm-lock.yaml")"
expect_equal "process-id files the refused start left in the copy" "" "$(ls "$COPY/.local/run" 2>/dev/null | grep '\.pid$' || true)"

# The documented way still works, and leaves the file alone too.
GOOD="$(cd "$COPY" && corepack pnpm install --frozen-lockfile 2>&1)"; GOOD_EXIT=$?
expect_equal "exit code of 'corepack pnpm install --frozen-lockfile'" 0 "$GOOD_EXIT"
expect_equal "pnpm-lock.yaml after Corepack's install" "$LOCK_BEFORE" "$(cksum <"$COPY/pnpm-lock.yaml")"

finish
