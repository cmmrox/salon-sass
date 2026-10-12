---
id: BUG-F01-S01-004
title: Run with a pnpm that is not Corepack's, local-up.sh and "pnpm install --frozen-lockfile" rewrite the committed pnpm-lock.yaml
severity: minor
status: fixed
found_by: exploratory
breaks: AC-1
found_on: 2026-10-12
fixed_in: 5b85cf1
fix_note: 'Cause: a pnpm other than the pinned 12.11.2 switches to the pinned version by itself and first records @pnpm/exe in pnpm-lock.yaml, even with --frozen-lockfile, and local-up.sh fell back to whatever pnpm was on PATH. Fix: pnpm-workspace.yaml sets pmOnFail: error, so such a pnpm stops before writing anything and names the version it needs; local-up.sh requires Corepack, names it when missing, and always runs corepack pnpm.'
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F01-S01-004 Run with a pnpm that is not Corepack's, local-up.sh and "pnpm install --frozen-lockfile" rewrite the committed pnpm-lock.yaml

## Steps

Account: none. Role: a developer whose `pnpm` was installed on its own (Homebrew, the standalone
installer, `npm i -g pnpm`) and who has no `corepack` on the `PATH`. Data: a clean checkout.

On this machine: Node.js 24.12.0, and Homebrew's `/usr/local/bin/pnpm` (11.6.0), with a `PATH` that
holds those two and no `corepack`.

1. `git status --short pnpm-lock.yaml` prints nothing.
2. Run `pnpm install --frozen-lockfile` (verification command 2), or `infra/scripts/local-up.sh`, which
   runs the same install through its `pnpm_run` fallback when `corepack` is not found.
3. `git status --short pnpm-lock.yaml`

```bash
mkdir -p /tmp/bin-node24 && ln -sf "$(command -v node)" /tmp/bin-node24/node && ln -sf /usr/local/bin/pnpm /tmp/bin-node24/pnpm
PATH="/tmp/bin-node24:/usr/bin:/bin:/usr/sbin:/sbin" pnpm install --frozen-lockfile
git diff --stat pnpm-lock.yaml
```

## Expected

Starting the product, or installing with `--frozen-lockfile`, never changes a committed file. Either
the lock file already holds what any pnpm 12.11.2 writes there, or the scripts refuse a pnpm that
does not come through Corepack and say so (`local-up.sh` already checks the other prerequisites and
names what is missing; the README asks for Corepack).

## Actual

The install prints `Lockfile is up to date, resolution step is skipped`, exits 0, and
`pnpm-lock.yaml` is modified: 25 lines are added recording `@pnpm/exe` 12.11.2 beside `pnpm`
12.11.2 under `packageManagerDependencies`. The developer now has an uncommitted change to the
lock file they did not make, which is easily committed with their next change. Happens every time:
2 times in 2. With Corepack's `pnpm` (the documented prerequisite) the file is left alone.

`local-up.sh` accepts this set-up without a word: its prerequisite check only asks that one of
`corepack` or `pnpm` exists.

## Evidence

```
Downloading the pnpm 12.11.2 binary for darwin-arm64...
Scope: all 3 workspace projects
Lockfile is up to date, resolution step is skipped
Done in 27ms using pnpm v12.11.2

$ git diff --stat pnpm-lock.yaml
 pnpm-lock.yaml | 25 +++++++++++++++++++++++++

@@ importers: . : packageManagerDependencies:
+      '@pnpm/exe':
+        specifier: 12.11.2
+        version: 12.11.2
       pnpm:
         specifier: 12.11.2
         version: 12.11.2
```

QA restored the file each time by reversing exactly this diff; the working tree was left as found.

## Re-verification

