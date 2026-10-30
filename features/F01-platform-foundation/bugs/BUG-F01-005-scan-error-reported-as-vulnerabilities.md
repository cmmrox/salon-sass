---
id: BUG-F01-005
title: images-scan.sh reports a scanner failure as fixable critical or high vulnerabilities
severity: minor
status: verified
found_by: TC-F01-S05-002
breaks: AC-2
found_on: 2026-10-17
fixed_in: 1fc9f2a
fix_note: "images-scan.sh gives Trivy's findings an exit code of their own (3, as Trivy uses 1 for fatal errors), so a scan that could not complete is reported as such (exit 2, naming the image and the scanner's message) and never as vulnerabilities (exit 1)."
developer_note: "infra/scripts/test-images-scan.sh (stub Docker playing Trivy) failed 4 of 10 checks on 4ad5e9b and passes 10 of 10 on 1fc9f2a. Real Trivy 0.75.0 on 1fc9f2a: salon-api and salon-web images exit 0 (clean); alpine:3.18.0 exit 1 naming 7 CVEs and \"vulnerabilities with a fix\" (unchanged for TC-F01-S05-002); Trivy killed mid-scan exit 2 with \"the scan of salon-web:f009-next could not complete (scanner exit 137)\" and no vulnerability claim. Trivy's own FATAL deadline error was not reproduced live; the stub replays its message."
verified_on: 2026-10-30
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

Verified on 2026-10-30 by QA on branch `charithm/bugfix/F01-009-rolling-deploy`, fix 1fc9f2a, product source
3bafe84 (QA head ddc3010 adds only the check below). Run folder (local, ignored):
`qa/reports/20261030-180546-F01-S05-production-like-stack-direct`.

- Changed path: `infra/scripts/images-scan.sh`. TC-F01-S05-002, the case that found the bug, covers it. That case
  now also checks a scan that cannot complete. A stand-in Trivy fails as the real one did here: a FATAL "context
  deadline exceeded" line, exit 1. The case expects exit 2, the image named, Trivy's message carried, and no claim of
  vulnerabilities. The vulnerable control must now exit 1 (docs/operations/environments-and-releases.md: 0 clean,
  1 vulnerabilities, 2 could not complete). Against 4ad5e9b's script the stand-in gives exit 1 and "critical or high
  vulnerabilities with a fix (listed above)", so the check fails there (the bug's own symptom). On the fix it gives
  exit 2 and "could not complete ... this is not a vulnerability finding".
- `qa/checks/F01/S05-stack.sh` (TC-F01-S05-001 to -007, -010 to -012) was run once on images built from ddc3010:
  10 of 10 passed in 23 s, exit 0. The default images scanned clean (exit 0). alpine:3.18.0 exited 1 naming 7 CVEs.
  The stand-in scan exited 2 (`images-scan-incomplete.log`).
- The bug's own steps with the real scanner: the Trivy 0.75.0 container was killed as soon as it started on
  salon-web:0.1.0-SNAPSHOT. Result: "the scan of salon-web:0.1.0-SNAPSHOT could not complete (scanner exit 137)", the
  summary "not known to be clean ... not a vulnerability finding", exit 2 (`bug-f01-005-killed-scanner.log`).
  Trivy's own analysis deadline was not provoked live; the stand-in replays its message.
- The check was run directly with `QA_REPORT_DIR`, not through `qa/scripts/sdlc run`. The runner selects whole
  scopes only, and `--stage F01-S05` would also start the load check TC-F01-S05-008/009, which the owner excluded
  (decision 069).
