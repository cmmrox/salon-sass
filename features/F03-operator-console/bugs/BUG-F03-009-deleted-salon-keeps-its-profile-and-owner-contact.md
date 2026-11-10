---
id: BUG-F03-009
title: "A deleted salon's registry row keeps its name, contact email and phone, address and owner email for ever, and the operator's salon detail still returns them"
severity: major
status: fixed
found_after_merge: 2026-11-10
found_by: "exploratory: the foundation review of 2026-11-10, by reading the code; not yet reproduced on the running product"
breaks: "PLT-06 (the data is deleted after the agreed retention period); NFR-09"
found_on: 2026-11-10
fixed_in: 167d3640679e57f81cd8ebf3bfc0082507cd9c2a
fix_note: "V34: the registry's lifecycle guard sets name, contact_email, contact_phone, address and owner_email to null whenever a tenant row is or becomes deleted, a check (tenant_tombstone_holds_no_profile) holds every tombstone to it, and existing tombstones are cleared; kept are id, slug, status, created_at, updated_at, closed_at, delete_at, deleted_at, plan_id, country, currency, time_zone, default_language, access_state and version. The operator's salon detail returns null for the cleared fields (already nullable in the contract)."
developer_note: "Reproduced by SalonLifecycleIntegrationTest.aDeletedSalonsTombstoneKeepsNoProfileOrOwnerContactAndTheConsoleReturnsNone: after the deletion job the row and the console detail still held all five values. Every cleared column was nullable, so no placeholder was needed. Invoices keep their own plan snapshot and never copied the salon's name; console lists that join tenant.name (invoices, support sessions) now show no name for a deleted salon."
verified_on: null
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

Not yet verified.
