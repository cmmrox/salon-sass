---
id: BUG-F02-002
title: Profile update audit output strips its required versions and changed field names
severity: major
status: fixed
found_by: TC-F02-S03-014
breaks: AC-6
found_on: 2026-10-14
fixed_in: 2ec43e0c4428c5a0674c8c4395cd305790d55658
fix_note: "Allowlisted nonnegative profile versions and documented changed field names for profile-update audit reads; Owner/Manager, isolation and unexpected-value privacy checks plus combined gates pass."
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F02-002 Profile audit omits required safe metadata

## Steps

Local alpha only, source 8eeca6f/runtime closure 34b59f2. Arrange a random synthetic Owner with a generated TOTP seed and authenticate through password/challenge/TOTP APIs. Authenticate a synthetic customer through a new SMS challenge, local capture and actual customer sign-in.

1. Read the customer's version0 profile.
2. PUT the full mutable profile with a changed name and version0. The saved profile advances to version1.
3. As Owner, GET `/api/v1/identity/audit?subjectId=<that customer's opaque id>`.
4. Inspect the `identity.profile_updated` entry's before/after key names, without recording profile values.

## Expected

S03 AC-6 and `docs/architecture/identity-and-access.md` privacy audit policy require nonsecret before/after versions and changed field names. The authorized audit should expose this useful safe metadata while excluding personal field values and credentials.

## Actual

The update event exists, but its public `before` and `after` are both empty objects. Independent reproduction printed `beforeKeys:[]` and `afterKeys:[]`. The API output filtering currently preserves only staff role/grant/active keys, dropping the profile version/changed-fields metadata. This is distinct from the nullable-schema issue in BUG-F02-001: the metadata is absent even when parsed without schema validation.

## Evidence

- `qa/reports/20261014-013535-F02/independent-fields-privacy-repro.json`: existing profile-update event with empty before/after key sets.
- `qa/reports/20261014-013535-F02/F02-S03-customer-profile.log`: S03-014 reached the audit contract failure first; its later required version/name assertions remain unchanged.
- The identity/privacy policy explicitly says profile audit stores versions and changed field names, never values.

Do not expose profile values to fix this. Extend the audit output allowlist only for the documented nonsecret metadata and verify the generated response shape/privacy contract.

## Re-verification

Pending developer fix. Verify versions/changed field names and absence of personal values through both Owner and Manager reads, then the full F02 feature.
