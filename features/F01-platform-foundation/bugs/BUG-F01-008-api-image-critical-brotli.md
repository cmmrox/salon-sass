---
id: BUG-F01-008
title: The API production image ships brotli-libs 1.2.0-r1 with a fixable critical vulnerability, so the image scan refuses it
severity: major
status: fixed
found_after_merge: 2026-10-30
found_by: TC-F01-S05-002
breaks: AC-2
found_on: 2026-10-30
fixed_in: f415df7
fix_note: "The API runtime stage adds the Alpine floor brotli-libs>=1.2.1-r0 beside libexpat's (the BUG-F01-006 pattern), failing the build if absent; 0dbe7d2 also moves the web image to Node.js 24.22.2, whose built-in brotli carries the same CVE's fix. images-scan.sh exits 0 for both images."
developer_note: "Reproduced by scanning salon-api:7049bf21a1e6 (exit 1, one CRITICAL). The web image had no brotli-libs package but Node 24.21.0 builds in brotli 1.2.0, invisible to the scan; Node 24.22.1 LTS (security release, 2026-10-21) names CVE-2026-111497 in its changelog, so 0dbe7d2 pins node:24.22.2-alpine3.24 (the lead's direction). Remove the brotli-libs floor once the Temurin tag carries 1.2.1-r0. Gates at 0dbe7d2 (complete logs in /Users/cmmrox/Personal/Projects/salon-sass-bugfix/build/regression-bugfix-evidence/gates, summary.txt): ./mvnw -q verify exit 0 (864 tests; three earlier runs failed only OperatorAccessIntegrationTest.simultaneousAttemptsOnOneEmailCountEveryAttempt under load averages 10-40, which passes alone and with the new tests run first, and base fbab6d0 passed at the same time), pnpm -r lint/typecheck/test exit 0 (web 700/700 on retry after load timeouts), web build, format:check, knip, contracts:check, lint-workflows, images-build, images-scan, test-edge-health-tolerance, test-script-prerequisites, sdlc selftest exit 0."
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F01-008 The API production image ships brotli-libs 1.2.0-r1 with a fixable critical vulnerability, so the image scan refuses it

Found by the owner-requested F01 to F03 regression after F23 (run
`qa/reports/20261030-073318-regression-a908c388c8aa`, entry F01-S05 production-like-stack). The API
image's runtime base is unchanged by F23 (`eclipse-temurin:25.0.4.1_1-jre-alpine-3.24`; F23 only copies
`lombok.config` into the build stage), so this is a newly published vulnerability in the delivered
image, not an F23 change. Same kind of defect as the verified BUG-F01-006 (libexpat).

## Steps

Account: none. Data: none.

1. `infra/scripts/images-build.sh` on the tested tree (7049bf2; product code as 51c15dc).
2. `infra/scripts/images-scan.sh salon-api:0.1.0-SNAPSHOT`.

## Expected

F01-S05 AC-2: `images-scan.sh` exits zero on the images built from the tree; it refuses only an image
with a HIGH or CRITICAL vulnerability that has a fix.

## Actual

Exit 1: `error: critical or high vulnerabilities with a fix (listed above) in: salon-api:0.1.0-SNAPSHOT`.
The scanner lists one: `brotli-libs` `CVE-2026-111497` CRITICAL, installed `1.2.0-r1`, fixed in `1.2.1-r0`
("Out-of-Bounds Write in Google Brotli Decoder"). The web image passes. Every time (the regression run and
a separate rescan).

## Evidence

Local, uncommitted: `qa/reports/20261030-F23-preflight/images-scan-api.log`; the regression run's entry log
`011-F01-S05-production-like-stack.log`.

## Re-verification

<QA, after a fix.>
