---
id: BUG-F01-005
title: images-scan.sh reports a scanner failure as fixable critical or high vulnerabilities
severity: minor
status: open
found_by: TC-F01-S05-002
breaks: AC-2
found_on: 2026-10-17
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F01-005 images-scan.sh reports a scanner failure as fixable critical or high vulnerabilities

## Steps

Local production-like stack only; images built by `infra/scripts/images-build.sh` at `c78b909`. Found in the owner-requested regression of 2026-10-17; pre-existing since F01-S05, unrelated to CR-013 or its fixes.

1. Run `infra/scripts/images-scan.sh salon-api:qa-cr013-next salon-web:qa-cr013-next` while Trivy cannot finish analysing a layer (here it hit its own analysis deadline).

## Expected

AC-2: the script exits zero on clean images and exits non-zero, naming the vulnerability, when an image has a critical or high vulnerability with a fix. A scan that could not complete is a different outcome and should say so (scanner error, which image, Trivy's message), so it is neither mistaken for vulnerabilities nor for a pass.

## Actual

Trivy failed with `FATAL ... failed to analyze layer (sha256:dacdca36…): post analysis error: walk dir error: walk error: context deadline exceeded`, and the script then printed `error: critical or high vulnerabilities with a fix (listed above) in: salon-api:qa-cr013-next`, with no vulnerability listed above. Any non-zero Trivy exit is treated as "vulnerabilities found", because `--exit-code 1` is also Trivy's exit for a fatal error. It still fails closed, which is safe, but the message is false and sends the reader looking for a CVE that does not exist.

The same images scanned directly afterwards are clean: `salon-api:0.1.0-SNAPSHOT`, `salon-web:0.1.0-SNAPSHOT`, `salon-api:qa-cr013-next-product`, `salon-api:qa-cr013-next` and `salon-web:qa-cr013-next` all gave "no critical or high vulnerability with a fix" (exit 0, about 90 s each run).

## Evidence

- Regression `qa/reports/20261017-095745-regression-b151743995aa/007-F01-S05-production-like-stack.log` lines 8–12 (the FATAL line, then the misleading summary).
- Direct rescans: scratch logs summarised above.

## Re-verification

<QA, after a fix.>
