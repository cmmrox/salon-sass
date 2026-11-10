---
id: BUG-F03-009
title: "A deleted salon's registry row keeps its name, contact email and phone, address and owner email for ever, and the operator's salon detail still returns them"
severity: major
status: verified
found_after_merge: 2026-11-10
found_by: "exploratory: the foundation review of 2026-11-10, by reading the code; not yet reproduced on the running product"
breaks: "PLT-06 (the data is deleted after the agreed retention period); NFR-09"
found_on: 2026-11-10
fixed_in: 167d3640679e57f81cd8ebf3bfc0082507cd9c2a
fix_note: "V34: the registry's lifecycle guard sets name, contact_email, contact_phone, address and owner_email to null whenever a tenant row is or becomes deleted, a check (tenant_tombstone_holds_no_profile) holds every tombstone to it, and existing tombstones are cleared; kept are id, slug, status, created_at, updated_at, closed_at, delete_at, deleted_at, plan_id, country, currency, time_zone, default_language, access_state and version. The operator's salon detail returns null for the cleared fields (already nullable in the contract)."
developer_note: "Reproduced by SalonLifecycleIntegrationTest.aDeletedSalonsTombstoneKeepsNoProfileOrOwnerContactAndTheConsoleReturnsNone: after the deletion job the row and the console detail still held all five values. Every cleared column was nullable, so no placeholder was needed. Invoices keep their own plan snapshot and never copied the salon's name; console lists that join tenant.name (invoices, support sessions) now show no name for a deleted salon."
verified_on: 2026-11-10
approved_by: null
found_in_release: null
---

# BUG-F03-009 A deleted salon's registry row keeps its name, contact email and phone, address and owner email for ever, and the operator's salon detail still returns them

Filed by the lead from `docs/reviews/2026-11-10-foundation-review/README.md` (decision 113). The developer reproduces it first, as `bug-fix` requires; if it
does not reproduce, say so in `developer_note` and leave it for QA.

## Steps

Account: an operator Admin. Data: a salon that has been closed.

1. Move the test clock past the salon's deletion instant and let the deletion job run.
2. Read the salon in the console (`GET` the operator's salon detail) and the `tenant` row.

## Expected

After deletion the tombstone keeps only what the platform must keep (id, slug, status and lifecycle instants). The salon's profile and the owner's contact details are gone and are not returned.

## Actual

By reading: `markDeleted` updates only `status`, `deleted_at`, `version` and `updated_at`; no statement in `platform` or in a migration clears the profile columns. Raised as P1 by the automated review of PR #13, which merged five seconds before the review arrived; never answered.

## Evidence

- `apps/api/src/main/java/com/salonplatform/platform/repository/SalonLifecycleStore.java:123-127`.
- PR #13 review comment on `SalonDeletion.java:145`: "Scrub profile PII when creating the tombstone".
- Review: `docs/reviews/2026-11-10-foundation-review/qa-and-delivery.md`, "Unanswered review on PR 13".

## Re-verification

Verified on 2026-11-10 on `148bf6af` (fix `167d3640`, migration V34, applied to the QA database on that build's start), QA instance.

- **The bug's own steps, as a case:** TC-F03-S03-018 (new, `qa/api/F03/S03-export-retention.spec.ts`, regression). A salon made for the test is given a paid invoice and closed, and its deletion instant is reached on the test clock.
  - The console's detail and its list by slug show status `deleted` with name, contact email, contact phone, address and Owner email all null, and none of those values appears anywhere in either.
  - Its old name finds nothing, and the registry row holds none of them.
  - The slug is refused 409 `slug_taken`.
  - The invoice is still readable and still names the salon by id. The platform audit is still readable (one `salon.deleted`) and holds none of the values.
  - Passed in `qa/reports/20261110-115757-F03-S03-7ab9e7ae0a1a`.
- The case fails without the fix (the detail still shows the address, contact email and phone) on a temporary local build with the eight fix commits reverted (commit d99b85b5 on a throwaway branch, deleted afterwards; the QA database was still at V33): `qa/reports/20261110-115120-F03-S03-4d8a77f1d467`.
- **Existing tombstones** are cleared by V34: after it, no `deleted` registry row on the QA database has a name.
- **Neighbours, for the registry guard and the tombstone's columns:** F03-S03 `lifecycle`, `export-retention` and `lifecycle-screens` in the same folder; F03-S01 `console-screens` and `provisioning` (the list, retired slugs) in `qa/reports/20261110-115641-F03-S01-9d00379805fa`. All passed. No roster salon was closed.
