---
id: BUG-F02-005
title: Staff creation and lists return nullable identifiers forbidden by the generated response schema
severity: major
status: open
found_by: TC-F02-S01-019
breaks: AC-9
found_on: 2026-10-14
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F02-005 Staff response schema rejects valid single-identifier accounts

## Steps

Final fixed product5af298a (runtime metadata b02f7c4), QA f904542. Local alpha, random synthetic Owner authenticated through password and TOTP. Native keyboard/manual Owner creation supplied an email and password, leaving the optional mobile blank; UI succeeded, but independent native staff-page validation failed.

1. Independently POST `/api/v1/identity/staff` with an Owner's issued access token, role Professional, valid password and a unique synthetic email only.
2. Validate the HTTP201 JSON with the committed `StaffView` schema.
3. Repeat with a unique synthetic mobile only.
4. GET `/api/v1/identity/staff?size=100` and validate `PageStaffView`.

## Expected

Staff policy requires at least one email/mobile identifier, and permits the other to be absent. S01 AC-6 accepts valid Owner account provisioning; AC-9 requires explicit generated response DTOs matching actual JSON. The successful creation/read/list schema must describe those absent identifiers accurately without relaxing the input rule.

## Actual

Both valid POSTs returned201. Email-only returned `mobile:null` and failed `/mobile` type:string; mobile-only returned `email:null` and failed `/email`. The authorized GET returned200 with three rows and failed both corresponding nullable item properties. The generated `StaffView` fields allow only string. No credential or cross-salon data leakage was observed. Reproduced independently in both variants after the manual discovery.

## Evidence

- `qa/reports/20261014-F02-optional-staff-repro/optional-staff-contract-repro.json`: statuses, nonempty item count, null/string types and sanitized schema error paths only; no identifier values or credentials.
- `qa/reports/20261014-F02-keyboard-complete/keyboard-review.log`: actual keyboard staff creation succeeds, then generated PageStaffView validation fails.
- `docs/api/openapi.json` StaffView.email/mobile currently type:string. This is a distinct staff-response omission from BUG-F02-001's audit fields.

## Re-verification

Pending. Existing S01-019 now includes email-only and mobile-only valid creation, generated StaffView/PageStaffView validation and real sign-in through each supplied identifier. Preserve the at-least-one identifier validation, permissions and privacy. Recheck the original manual Owner path and the entire F02 feature through the lifecycle; do not loosen the schema validator.
