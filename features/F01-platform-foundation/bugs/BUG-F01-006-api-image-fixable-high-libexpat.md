---
id: BUG-F01-006
title: The API production image ships libexpat 2.8.5 with a fixable high vulnerability, so the image scan refuses it
severity: major
status: verified
found_by: TC-F01-S05-002
breaks: AC-2
found_on: 2026-10-18
fixed_in: 09b7d80
fix_note: "The API runtime stage installs libexpat >= 2.9.0-r0 from Alpine 3.24 (apk add --no-cache --upgrade), failing the build if absent; images-scan.sh exits 0 for both images."
developer_note: null
verified_on: 2026-10-18
approved_by: null
found_in_release: null
---

# BUG-F01-006 The API production image ships libexpat 2.8.5 with a fixable high vulnerability, so the image scan refuses it

## Steps

No account. Local production-like stack only; images built by `infra/scripts/images-build.sh` from the tested tree `2fc8425` (QA commit on `charithm/test/CR-017-feature-qa`; the product source is identical to `29fb3d1`). Found in the CR-017 F01 feature pass. CR-017 did not cause it: the API image's Dockerfile and Java source are unchanged, and the API image built yesterday from the CR-013 tree (`salon-api:qa-cr013-next`) gives the same finding today.

1. `infra/scripts/images-build.sh`
2. `infra/scripts/images-scan.sh salon-api:0.1.0-SNAPSHOT`

## Expected

F01-S05 AC-2: "`images-scan.sh` exits zero on the images built from the stage's tree" (NFR-07). The images that would be published must have no critical or high vulnerability that has a fix.

## Actual

The scan exits 1, naming the vulnerability:

```
salon-api:0.1.0-SNAPSHOT (alpine 3.24.2)
Total: 1 (HIGH: 1, CRITICAL: 0)
libexpat │ CVE-2026-77214 │ HIGH │ fixed │ 2.8.5-r0 │ 2.9.0-r0 │ expat: Information disclosure via heap buffer over-read in XML_ParseBuffer
error: critical or high vulnerabilities with a fix (listed above) in: salon-api:0.1.0-SNAPSHOT
```

The runtime stage `FROM eclipse-temurin:25.0.4.1_1-jre-alpine-3.24` (`apps/api/Dockerfile`) contains `libexpat-2.8.5-r0` on Alpine 3.24.2; Alpine has the fix in `2.9.0-r0`. The vulnerability record is new, which is why the same Dockerfile scanned clean on 2026-10-17. The web image (`salon-web:0.1.0-SNAPSHOT`) scans clean. Happens every time (3 of 3: the sealed run, a direct rerun, and the older CR-013 image).

This is a real finding, not BUG-F01-005: the scan completed and listed the CVE.

## Evidence

- Sealed run `qa/reports/20261018-015641-F01-delta-db23e058e597/011-F01-S05-production-like-stack.log` (TC-F01-S05-002 FAIL; every other stack case passed).
- Direct rerun `qa/reports/20261018-CR017-manual/images-scan-api-rerun.log`; the CR-013 image `qa/reports/20261018-CR017-manual/images-scan-api-cr013.log`; web image clean `qa/reports/20261018-CR017-manual/images-scan-web-rerun.log`.
- `apk info -v` in the image: `libexpat-2.8.5-r0`; `/etc/alpine-release` 3.24.2.

## Re-verification

Verified 2026-10-18 by independent QA on 18bb512 (fix 09b7d80, `apps/api/Dockerfile` only), QA branch `charithm/test/CR-017-feature-qa`, reviewed revision c522818.

- The bug's own steps: `infra/scripts/images-build.sh` (inside `verify-commands --feature F01`, report `qa/reports/20261018-021645-F01-commands-7797ee59cdeb`, 10/10) built `salon-api:0.1.0-SNAPSHOT` labelled 18bb512; it holds `libexpat-2.9.0-r0` on Alpine 3.24.2 and still runs as uid 10001; `infra/scripts/images-scan.sh salon-api:0.1.0-SNAPSHOT salon-web:0.1.0-SNAPSHOT` exits 0 with "no critical or high vulnerability with a fix", and CVE-2026-77214 no longer appears (`qa/reports/20261018-BUG-F01-006-reverify/`). The N+1 image built for the deployment check has the same package.
- The case that found it and its dependencies, sealed: `qa/reports/20261018-022325-F01-delta-9149b677b3ef` at 18bb512, production-like-stack 12/12 cases (TC-F01-S05-002 passes: both images clean, the vulnerable alpine:3.18.0 control still refused and named) and deployment-traffic (TC-F01-S05-008/009, rollout and rollback of images built with the fix, with the QA migration fixture) pass, with every other F01 entry.
- The case would catch it again: TC-F01-S05-002 failed on it before the fix (`qa/reports/20261018-015641-F01-delta-db23e058e597`).
