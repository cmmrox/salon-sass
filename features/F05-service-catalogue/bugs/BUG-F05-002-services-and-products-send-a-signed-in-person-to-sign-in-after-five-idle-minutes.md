---
id: BUG-F05-002
title: "Services and Products send a signed-in person to the sign-in form once five minutes pass without a request, although the session can still be renewed"
severity: major
status: open
found_after_merge: 2026-11-10
found_by: "exploratory: the foundation review of 2026-11-10, by reading the code; not yet reproduced on the running product"
breaks: "IAM-09 (short-lived access with renewal); F05-S01 and F05-S03 screens"
found_on: 2026-11-10
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F05-002 Services and Products send a signed-in person to the sign-in form once five minutes pass without a request, although the session can still be renewed

Filed by the lead from `docs/reviews/2026-11-10-foundation-review/README.md` (decision 113). The developer reproduces it first, as `bug-fix` requires; if it
does not reproduce, say so in `developer_note` and leave it for QA.

## Steps

Account: an Owner or Manager, signed in.

1. Stay on any workspace page for six minutes without acting (or move the test clock past the access credential's five minutes).
2. Open Services, or Products, or reload either page.

## Expected

The page renews the access credential and shows the services or products with the person's own permissions; nobody is asked to sign in again while the refresh session is valid.

## Actual

By reading: the first read answers 401 renewable, and these screens' failure handler maps `invalid_session` straight to the sign-in form. Rendered on the server without a principal, the page also loses its manage controls and the navigation collapses to Account.

## Evidence

- `apps/web/src/surfaces/workspace/use-catalogue.ts:100-105,122-137`; `use-products.ts:68-72,92-94`.
- `apps/web/src/surfaces/workspace/AccountPage.tsx:87,133-163`.
- `apps/web/src/features/identity/client/session.ts:16-40` (`identityRequest` does not renew).
- The access cookie lives 300 seconds: `apps/web/src/features/identity/server/identity-web.ts:307`; a read without it answers 401 with `renewable: true` (`:426-456`).
- The same defect was fixed for Bookings only in F07-S03 (`apps/web/src/surfaces/workspace/BookingsScreen.tsx:71-82`).
- No test covers it on these screens.
- Review: `docs/reviews/2026-11-10-foundation-review/web.md`, H1. Decision 121 moves the renew-and-retry into the one request module, which is the fix for every screen at once.

## Re-verification

Not yet verified.
