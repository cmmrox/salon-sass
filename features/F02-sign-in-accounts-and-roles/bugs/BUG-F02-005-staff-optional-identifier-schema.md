---
id: BUG-F02-005
title: Staff creation and lists return nullable identifiers forbidden by the generated response schema
severity: major
status: verified
found_by: TC-F02-S01-019
breaks: AC-9
found_on: 2026-10-14
fixed_in: 61c55a94bac9cf881ac886091e339ca9830063e1
fix_note: "Declared serialized staff email/mobile as required nullable strings and regenerated contracts; email-only/mobile-only create, sign-in, list and negative checks plus 504-test API verify and contract check pass."
developer_note: null
verified_on: 2026-10-14
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

Verified on 2026-10-14. Extended S01-019 passed both email-only/mobile-only HTTP201 generated StaffView responses, omitted null identifiers, normalized supplied mobile, real sign-in and nonempty PageStaffView results. Original Owner keyboard creation also passed with API role readback. The fresh existing-count failure witness reached its intended assertion with exact restored source; final whole F02 run passed. Evidence: `qa/reports/20261014-115732-F02/summary.json` and the corresponding registered entry logs.
Targeted ordinary evidence: `qa/reports/20261014-final-staff-fix-normalized/staff-contract-ordinary.log`; fresh witness `qa/reports/F02-witnesses/staff-contract-confirmed.json`; manual proof `qa/reports/20261014-F02-manual-complete/manual/keyboard-timing.json`.
