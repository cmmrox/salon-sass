---
id: BUG-F03-009
title: "A deleted salon's registry row keeps its name, contact email and phone, address and owner email for ever, and the operator's salon detail still returns them"
severity: major
status: open
found_after_merge: 2026-11-10
found_by: "exploratory: the foundation review of 2026-11-10, by reading the code; not yet reproduced on the running product"
breaks: "PLT-06 (the data is deleted after the agreed retention period); NFR-09"
found_on: 2026-11-10
fixed_in: null
fix_note: null
developer_note: null
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
