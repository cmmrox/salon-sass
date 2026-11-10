---
id: BUG-F06-001
title: "Team, the schedule sheet and leave send a signed-in person to the sign-in form once five minutes pass without a request, although the session can still be renewed"
severity: major
status: fixed
found_after_merge: 2026-11-10
found_by: "exploratory: the foundation review of 2026-11-10, by reading the code; not yet reproduced on the running product"
breaks: "IAM-09 (short-lived access with renewal); F06-S01 to F06-S03 screens"
found_on: 2026-11-10
fixed_in: ebde9b336e7c4b70ae5f100aaffd217ffbfb653a
fix_note: "Reproduced first by a unit test per screen in which the read answers 401 invalid_session renewable and the refresh succeeds (TeamScreen.test.tsx: the team; TeamSchedule.test.tsx: the schedule sheet; TeamTimeOff.test.tsx: the bookings leave would affect; each 'an idle session (BUG-F06-001)'): all three failed. Fixed at the cause: every read and change of Team, the schedule sheet and leave goes through apps/web/src/lib/api-core/request.ts, which renews an expired access credential once under the cross-tab lock and asks the same call again. Team rendered on the server without a principal is rendered again with the person's permissions by the workspace frame's SessionRenewal (commit 3e74b7a1). Needs the running product to show end to end: idle six minutes, then open or reload Team, open a schedule sheet, open the leave form."
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F06-001 Team, the schedule sheet and leave send a signed-in person to the sign-in form once five minutes pass without a request, although the session can still be renewed

Filed by the lead from `docs/reviews/2026-11-10-foundation-review/README.md` (decision 113). The developer reproduces it first, as `bug-fix` requires; if it
does not reproduce, say so in `developer_note` and leave it for QA.

## Steps

Account: an Owner or Manager, signed in.

1. Stay on any workspace page for six minutes without acting (or move the test clock past the access credential's five minutes).
2. Open Team, or reload it; open a person's schedule sheet; open the leave form.

## Expected

The page renews the access credential and shows the team with the person's own permissions; nobody is asked to sign in again while the refresh session is valid.

## Actual

By reading: the first read answers 401 renewable, and these screens' failure handler maps `invalid_session` straight to the sign-in form. Rendered on the server without a principal, Team also loses its manage controls.

## Evidence

- `apps/web/src/surfaces/workspace/use-team.ts:73-78,93-101`; `use-schedule.ts:104-108,121-127`; `use-time-off.ts:82-88`.
- `apps/web/src/features/identity/client/session.ts:16-40` (`identityRequest` does not renew).
- The access cookie lives 300 seconds: `apps/web/src/features/identity/server/identity-web.ts:307`; a read without it answers 401 with `renewable: true` (`:426-456`).
- The same defect was fixed for Bookings only in F07-S03 (`apps/web/src/surfaces/workspace/BookingsScreen.tsx:71-82`).
- No test covers it on these screens.
- Review: `docs/reviews/2026-11-10-foundation-review/web.md`, H1. Decision 121 moves the renew-and-retry into the one request module, which is the fix for every screen at once.

## Re-verification

Not yet verified.
