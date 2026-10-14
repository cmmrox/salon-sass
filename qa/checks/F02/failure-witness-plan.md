# F02 planned failure witnesses

Preparation only. No witness has been executed or recorded. Perform after all four stages close and the final handoff is frozen, before the recorded final feature pass.

The salon-qa reliability rule requires each new critical test, and every count or scrape check, to fail once for the intended assertion. Temporarily invert one decisive expected result inside that existing test, run that exact case headlessly, confirm its assertion message and cleanup, then restore the exact file. Do not add a second negative test, leave mutation flags in the committed suite, retry a real failure, or record a mutated run as a product defect. Keep witness outputs under ignored qa/reports, recording case id, source revision, file/line, temporary expectation, actual assertion and restored-file fingerprint. Do not record credential-bearing diffs/responses/screenshots.

Before any witness, confirm fresh local runtime/build attribution, healthy dependencies, correct synthetic fixtures and installed drivers. An environment failure is not a witness. Complete ordinary triage first if the unmodified test fails. Final evidence is only qa/scripts/sdlc run --feature F02 with restored source. No F01, stage pass, regression, smoke or staging execution.

| Scope | Critical cases requiring a witness | Registered source |
| --- | --- | --- |
| F02 | TC-F02-001, TC-F02-002, TC-F02-003 | qa/web/F02/feature-journeys.spec.ts |
| F02-S01 | TC-F02-S01-002, TC-F02-S01-003, TC-F02-S01-004, TC-F02-S01-005, TC-F02-S01-006, TC-F02-S01-007, TC-F02-S01-008, TC-F02-S01-009, TC-F02-S01-010, TC-F02-S01-011, TC-F02-S01-012, TC-F02-S01-014, TC-F02-S01-015, TC-F02-S01-016, TC-F02-S01-017, TC-F02-S01-018, TC-F02-S01-020, TC-F02-S01-021, TC-F02-S01-022, TC-F02-S01-023, TC-F02-S01-024 | qa/api/F02/S01-staff-access.spec.ts |
| F02-S01 | TC-F02-S01-027 | qa/api/F02/S01-runtime.spec.ts |
| F02-S02 | TC-F02-S02-003, TC-F02-S02-004, TC-F02-S02-005, TC-F02-S02-006, TC-F02-S02-007, TC-F02-S02-008, TC-F02-S02-009, TC-F02-S02-010, TC-F02-S02-014, TC-F02-S02-015 | qa/api/F02/S02-customer-access.spec.ts |
| F02-S02 | TC-F02-S02-011, TC-F02-S02-012, TC-F02-S02-013, TC-F02-S02-017 | qa/api/F02/S02-customer-runtime.spec.ts |
| F02-S03 | TC-F02-S03-002, TC-F02-S03-006, TC-F02-S03-007, TC-F02-S03-008, TC-F02-S03-009, TC-F02-S03-010, TC-F02-S03-011, TC-F02-S03-012, TC-F02-S03-013, TC-F02-S03-014 | qa/api/F02/S03-customer-profile.spec.ts |
| F02-S04 | TC-F02-S04-004 | qa/web/F02/S04-functional.spec.ts |
| F02-S04 | TC-F02-S04-006, TC-F02-S04-007, TC-F02-S04-008, TC-F02-S04-013, TC-F02-S04-014, TC-F02-S04-015 | qa/web/F02/S04-browser-security.spec.ts |
| F02-S04 | TC-F02-S04-022 | qa/web/F02/S04-resilience-address.spec.ts |

Count/scrape witnesses also apply to successful role/endpoint inventories, RLS table/app-role counts, contract endpoint inventories, audit/staff/device/capture counts, retry/cancellation/rate-volume evidence, allowed export keys, deletion erasure records, UI handle/string/width/brand/Axe inventories, standalone artifact inventory, and every k6 read/role/salon counter and p95 threshold. The critical inventory above is the minimum, not a replacement for that rule. Select the meaningful invariant (foreign data absent, permission denial, one renewal, all deletion authority denied, nonempty secret-free build inventory) rather than a fixture-health assertion.

| Area | Planned temporary incorrect expectation |
| --- | --- |
| Tenant/permission/CSRF | Expect one forbidden identity operation to succeed; confirm the actual denial triggers its named assertion without side effects |
| Session/MFA/code/replay | Expect spent or incomplete authority to remain valid; confirm the observed denial and family state |
| Race/once-only | Alter expected successful count or rotation count by one, preserving simultaneous requests and final-state readback |
| Profile/privacy | Alter saved version, exact own-data allowlist, or post-deletion authority/erasure expectation |
| Audit/contract/RLS/storage | Alter a known nonempty expected record, table, endpoint or allowed output key count |
| Browser isolation/secrecy | Expect one remaining credential family or inventory mismatch where the intended absence/preservation assertion must fail |
| Presentation | Alter required screen/brand/handle inventory or overflow/Axe expectation on the same nonempty inspected page |
| Artifact scan | Temporarily require a known retained-secret finding or impossible nonempty inventory count; independently check actual scan on restored expectation, never copy a real key into an artifact |
| k6 baseline | Temporarily set a served-read/role/salon counter threshold to an impossible value; require k6 nonzero exit and reported failed threshold, then restore exact thresholds |

The human review case TC-F02-S04-018 remains performed_on null until actual keyboard, focus, contrast and real-speed interactions have been reviewed. Final handoff may change selector mapping or output paths; update those QA details and compile before the witnesses.
