---
id: BUG-F07-004
title: When a change empties the later page being shown, the Bookings page says "No bookings match." with no pager, hiding the day's remaining bookings
severity: minor
status: verified
found_by: "TC-F07-S03-021; PR #26 review (Codex, use-bookings.ts)"
breaks: AC-3
found_on: 2026-11-10
fixed_in: 67d873a43ba26b161d536f5c8eba56cfe517dee7
fix_note: "When a read comes back empty for a page past the last while the day still has bookings, the list shows the last page that exists, replacing the address's page in place; this covers an action and a hold running out."
developer_note: null
verified_on: 2026-11-10
approved_by: null
found_in_release: null
---

# BUG-F07-004 When a change empties the later page being shown, the Bookings page says "No bookings match." with no pager, hiding the day's remaining bookings

## Steps

Account: a salon's Owner (the QA fixture's provisioned salon; any role that releases holds or sees one run out is the
same). Data: 51 holds on one day three days ahead (six professionals performing "Cut and blow-dry", hourly from 09:00), so
50 fill the first page (the page size) and one is alone on the second. QA instance (web 3200, API 8190).

1. Open `/workspace/bookings?day=<that day>`: "51 bookings on <day>" and the pager.
2. **Next**: the second page lists the one hold.
3. Open it and **Release hold**: "Hold released. The time is open again."
4. Close the sheet and look at the list.

The same happens when the second page's only holds run out on screen (AC-2's expiry reads the list again the same way).

## Expected

F07-S03 AC-3: "More than one page of results uses Pagination." AC-9: "After any change the list and the sheet show the new
state without a reload." The day still has 50 bookings, so the list shows them (the last page that exists) or at least the
pager to reach them. The empty state ("No bookings match.") is for a day or filters with no bookings.

## Actual

The heading reads "50 bookings on <day>" (Sat, May 28, 2101 in the first attempt; Sat, Jul 9, 2101 in the run) and directly under it the empty state says "No bookings match. Try
another day, or clear the search." with **+ New appointment**, and no pager and no previous-page control: the 50 bookings
are hidden. The address keeps `page=2`, so (by the code) a reload shows the same; choosing the day or **Today** again goes
back to the first page.

Happens every time: 2 of 2 attempts (the test alone, and the recorded stage run).

Severity: minor. The fault is at an edge (a page emptied under the person, which needs more than 50 bookings in a day) with
an easy way out (choose the day or Today again), and no booking is lost or wrong; the page contradicts itself rather than
misleading silently. The owner has asked for it to be fixed before PR #26 merges, so it is not deferred.

## Evidence

Recorded run: qa/reports/20261110-050643-F07-S03-2411f94895b7, entry 001 bookings-page-web (001-F07-S03-bookings-page-web.log), TC-F07-S03-021 failed: `getByTestId('bookings-empty')`
"not "No bookings match." while the day has 50 bookings" Expected: 0, Received: 1, after the heading had changed to
"50 bookings on <day>". Screenshot (the heading "50 bookings on Sat, Jul 9, 2101" over "No bookings match.", no pager):
qa/reports/20261110-050643-F07-S03-2411f94895b7/playwright/2026-11-09T23-36-43-670Z-13658/web-F07-S03-bookings-page--860b2--them-never-the-empty-state-web/test-failed-1.png.

Where: `apps/web/src/surfaces/workspace/use-bookings.ts` (about line 74) stores the API's empty page without correcting
`query.page`, and `BookingsScreen.tsx` (about line 199) renders the empty state, not the pager, whenever the page's items
are empty.

## Re-verification

2026-11-10, on a5a7d8fe (fix 67d873a4; only apps/web/src/surfaces/workspace/use-bookings.ts and its screen test).
QA instance rebuilt and restarted at a5a7d8fe (the fix merged; e0c1d4bc adds only QA test repairs): API PID 84142, /api/v1/meta commit a5a7d8fe; web PID 84363, apps/web/.next-qa built 06:00 (+05:30) from the clean tree. The old processes (web 67534, API 4997) were stopped by their exact PIDs.

1. The bug's own steps (qa/reports/20261110-060856-F07-S03-d5c497afa957/BUG-F07-002-003-004-steps.log): 51 holds, page 2 with one hold, **Release hold**. Then
   "50 bookings on Fri, Jul 29, 2101" with its 50 rows, no empty state, the address corrected in place from
   `?day=2101-07-29&page=2` to `?day=2101-07-29` (no history entry added), and the same after a reload. With 50 the day fits
   one page, so no pager is shown, as AC-3 says. The symptom is gone.
2. `qa/scripts/sdlc run --stage F07-S03`: 21 of 21 cases passed, TC-F07-S03-021 included, with TC-F07-S03-002 to -004
   (the list, a hold leaving at zero, the tools, the pager, the address through reload and back), which the change to
   use-bookings.ts touches. Report: qa/reports/20261110-060856-F07-S03-d5c497afa957.
3. TC-F07-S03-021 is the test that would catch it again: it failed on aeab2c30's product and passes on a5a7d8fe.

Result: verified.
