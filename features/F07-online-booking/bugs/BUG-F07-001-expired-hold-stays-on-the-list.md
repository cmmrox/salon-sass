---
id: BUG-F07-001
title: A hold whose countdown reaches zero stays on the Bookings list at "Held · 0:00 left" until the page is reloaded
severity: major
status: fixed
found_by: TC-F07-S03-003
breaks: AC-2
found_on: 2026-11-09
fixed_in: bdf2e35499e4ecbc3686c51da911b63136c4b8b2
fix_note: "The Bookings list asks the API again every 2 seconds while it still lists a hold whose time is up, as the appointment sheet does, so the row leaves once the API stops listing it."
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F07-001 A hold whose countdown reaches zero stays on the Bookings list at "Held · 0:00 left" until the page is reloaded

## Steps

Account: a salon's Owner (the QA fixture's provisioned salon; any role with `calendar.read` is the same). Data: one hold
for today, with the API's test clock 8 seconds before its expiry.

1. Place a hold for today at 10:00 (`POST /api/v1/bookings`); note `holdExpiresAt`.
2. Set the test clock to 8 seconds before `holdExpiresAt` (`PUT /local/clock`), sign in, open `/workspace/bookings`.
3. Watch the hold's row: it counts down "Held · 0:08 left" to "Held · 0:00 left".
4. Wait 15 to 25 seconds more.

## Expected

AC-2: "Expired holds disappear without a reload when their countdown reaches zero." The row leaves the list.

## Actual

The row stays at "Held · 0:00 left" indefinitely; nothing changes until the page is reloaded. At zero the page asks for the
day's list once, and its answer still lists the hold as `held`, because the request reaches the API a moment before the
expiry instant by the API's clock. The page never asks again. Meanwhile the API already treats the hold as gone:
`GET /api/v1/bookings/{id}` answers 404 and its time can be held by someone else, so the desk is shown a held time that is
in fact free.

Happens every time: 4 of 4 attempts (TC-F07-S03-003 in a direct run and in the feature run, and two probes recording the page's requests,
one with the browser's clock left as it is, so it is not caused by the test aligning the browser's clock).

The appointment sheet does not have this fault: at zero it asks again every 2 seconds while the API still says held
(S03 handoff, choice 2). The list asks only once.

## Evidence

Probe of 2026-11-09 on the QA instance at 76e3b080 (the hold's `holdExpiresAt` 2101-01-06T08:10:00+05:30, which is
02:40:00Z; customer details left out):

```
GET /identity-web/staff/bookings?from=2101-01-06&to=2101-01-06&page=0&size=50   (page load, API time 02:39:52.288Z)
  -> 200 {"items":[{"id":"bd16cb9f-...","reference":"QHM-BZQ","state":"held",...,"holdExpiresAt":"2101-01-06T08:10:00+05:30",...}]...}
GET /identity-web/staff/bookings?from=2101-01-06&to=2101-01-06&page=0&size=50   (at the countdown's zero; answered by 02:40:00.295Z)
  -> 200 {"items":[{"id":"bd16cb9f-...","reference":"QHM-BZQ","state":"held",...}]...}
(no further request; 15 s later the row reads "Held · 0:00 left")
GET /api/v1/bookings/bd16cb9f-...   -> 404 not_found
```

The feature run's failure of TC-F07-S03-003 ("gone at zero", expected 0 rows, received 1): its screenshot and page
snapshot are in qa/reports/20261109-223501-F07-10961619c25e/playwright/2026-11-09T17-07-00-096Z-36838/
web-F07-S03-bookings-page--c1a40--its-countdown-reaches-zero-web/, and the entry's log is
qa/reports/20261109-223501-F07-10961619c25e/013-F07-S03-bookings-page-web.log.

## Re-verification

None yet.
