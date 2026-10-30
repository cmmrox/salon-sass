---
id: BUG-F01-008
title: The API production image ships brotli-libs 1.2.0-r1 with a fixable critical vulnerability, so the image scan refuses it
severity: major
status: open
found_after_merge: 2026-10-30
found_by: TC-F01-S05-002
breaks: AC-2
found_on: 2026-10-30
fixed_in: null
fix_note: null
developer_note: null
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
