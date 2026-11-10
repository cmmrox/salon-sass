---
id: BUG-F06-002
title: "Moving a service to another category silently stops a professional performing a service they are booked for, or makes everyone who performs the new category eligible for it"
severity: major
status: open
found_after_merge: 2026-11-10
found_by: "exploratory: the foundation review of 2026-11-10, by reading the code; not yet reproduced on the running product"
breaks: "F06-S01 AC-8 (a save may not stop a person performing a service with an upcoming booking for them); STF-01"
found_on: 2026-11-10
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F06-002 Moving a service to another category silently stops a professional performing a service they are booked for, or makes everyone who performs the new category eligible for it

Filed by the lead from `docs/reviews/2026-11-10-foundation-review/README.md` (decision 113). The developer reproduces it first, as `bug-fix` requires; if it
does not reproduce, say so in `developer_note` and leave it for QA.

## Steps

Account: an Owner. Data: Nimal performs the whole category Colour; the service Balayage is in Colour; Nimal has a confirmed Balayage booking next week; a second category Treatments that he does not perform.

1. On Services, edit Balayage and move it to Treatments.
2. Read Nimal's services, the free times for Balayage with Nimal, and try to undo a no-show on his Balayage booking.

## Expected

The move is refused 409 while it would stop someone performing a service they are booked for, as a team save is; or the booking is flagged for attention (BKG-13). A move that makes more people eligible says so before it is saved.

## Actual

By reading: `SalonServicesImpl.update` changes the category with no question to `staff` or `booking`; a person's services are derived from whole categories at read time; the guard runs only on a team save. Nimal no longer performs Balayage, availability stops offering him, undoing a no-show on that booking is refused, and nothing is flagged.

## Evidence

- `apps/api/src/main/java/com/salonplatform/catalogue/service/impl/SalonServicesImpl.java:152-183`.
- `apps/api/src/main/java/com/salonplatform/staff/domain/PerformedServices.java:68-75`.
- `apps/api/src/main/java/com/salonplatform/staff/service/impl/TeamMemberServiceImpl.java` (about `:191,:207-208`, the guard on a team save only).
- Review: `docs/reviews/2026-11-10-foundation-review/api-domain.md`, M3.

## Re-verification

Not yet verified.
