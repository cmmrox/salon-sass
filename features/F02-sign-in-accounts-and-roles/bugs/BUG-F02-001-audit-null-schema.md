---
id: BUG-F02-001
title: Identity audit responses return null values forbidden by their generated schema
severity: major
status: fixed
found_by: TC-F02-S01-023
breaks: AC-9
found_on: 2026-10-14
fixed_in: 2ec43e0c4428c5a0674c8c4395cd305790d55658
fix_note: "Declared audit before/after as required nullable objects and regenerated OpenAPI/client; focused contract regression and combined API/contract gates pass."
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F02-001 Identity audit responses violate their schema

## Steps

Local alpha only, application source 8eeca6f (runtime closure revision 34b59f2), QA revision 4149a059a6edfb3b7e8f45709e1093026755c7e6. Use random synthetic Manager email/mobile/password through the local seed hook; authenticate through the real password sign-in API.

1. Sign in the Manager, producing an ordinary identity sign-in audit entry.
2. GET `/api/v1/identity/audit?size=100` on alpha with that issued access credential.
3. Validate the returned page with the committed `PageAuditView`/`AuditView` schema, without changing its nullability.

## Expected

F02-S01 AC-9 requires explicit generated response DTOs; the implemented API contract requires generated schemas to match actual JSON. An authorized audit page must satisfy its published schema. Missing before/after metadata may be nullable or omitted when the contract describes it accurately.

## Actual

HTTP 200 audit items include `before:null` and `after:null`. The generated properties allow only `object`. Actual response validation therefore fails. The diagnostic run hit this same mismatch in S01-023/025, S02-015/013 and S03-008/014. Independent real-authentication reproduction returned 100 entries; the first six alone provided twelve null/object mismatches. No unauthorized data or credential leakage was observed.

## Evidence

- `qa/reports/20261014-013535-F02/F02-S01-staff-access.log`: `PageAuditView: generated contract shape`.
- `qa/reports/20261014-013535-F02/independent-audit-repro.json`: HTTP200, item count100, twelve redacted field/type mismatches.
- `docs/api/openapi.json`, `components.schemas.AuditView.properties.before/after`: `type: object` without null.

The reproduction inspected only schema/type mismatches and recorded no issued credentials or personal values. Do not loosen the QA validator; make actual response DTO nullability and generated client agree, with a focused generated-contract regression.

## Re-verification

Pending developer fix. Recheck the affected exact audit assertions, then the entire F02 feature on the fixed source through its lifecycle.
