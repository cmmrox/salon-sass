---
id: BUG-F07-002
title: Free times asked for one day are shown after the day (or service) was changed, and choosing one holds that time on the day now in the form
severity: major
status: fixed
found_by: "TC-F07-S03-019; PR #26 review (Codex, use-new-appointment.ts search)"
breaks: AC-5
found_on: 2026-11-10
fixed_in: d65fd1d1718d646609a937f32ed9eae38c504d98
fix_note: "New appointment remembers the form and the search each free-times read was asked for, and ignores an answer (or refusal) for a form changed since, by any field, opening or closing, or overtaken by a later search."
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F07-002 Free times asked for one day are shown after the day (or service) was changed, and choosing one holds that time on the day now in the form

## Steps

Account: a salon's Owner (the QA fixture's provisioned salon; Manager and Receptionist use the same sheet). Data: one
professional performing "Cut and blow-dry", open 09:00 to 19:00 every day. QA instance (web 3200, API 8190).

1. Open `/workspace/bookings`, **+ New appointment**; fill the customer's name and mobile, choose day D, the service and
   the professional, and press **Find free times**.
2. While that search is still on its way (in the test its BFF answer, `GET /identity-web/staff/booking-availability?...&date=D`,
   is held back until step 3 is done; on a slow connection the same window is open for as long as the request takes),
   change the Day to D+1.
3. Let the answer for D arrive.
4. Choose the first chip.

## Expected

F07-S03 AC-5: "Changing any field clears the times." The times are of the form as it stands; an answer for values the form
no longer has is not shown, and AC-6's hold is for the day, service and professional the chosen time was offered for. After
step 3 no chip and no "Free times on <D>" sentence is shown while the form says D+1.

## Actual

After step 3 the sheet shows D's times under "Free times on <D>. Choose one to hold it." while its Day field says D+1.
Choosing a chip holds that time on D+1, a day the person never searched: the hold is sent with the form's current values
and the stale chip's time. The same window exists for a change of service, variant or professional (the code path is the
same: `change()` clears the times but the search in flight still writes them when it lands). The hold card shows the real
day, so a careful person can notice it before confirming; nothing else warns them.

Happens every time the answer lands after the change: 3 of 3 attempts (the test alone, the probe below, and the
recorded stage run).

## Evidence

Recorded run: qa/reports/20261110-050643-F07-S03-2411f94895b7, entry 002 new-appointment-web (002-F07-S03-new-appointment-web.log), TC-F07-S03-019 failed: "Fri, Jul 15, 2101's times are not shown
while the form says Sat, Jul 16, 2101", Expected: 0, Received: 37 (37 chips of the earlier day). Screenshot and page snapshot:
qa/reports/20261110-050643-F07-S03-2411f94895b7/playwright/2026-11-09T23-37-23-844Z-14038/web-F07-S03-new-appointmen-c2c39-them-for-the-day-now-chosen-web/. In
the first attempt (the test alone) the snapshot showed `Free times on Tue, May 24, 2101. Choose one to hold it.` beside
`button "Day": Wed, May 25, 2101`.

Probe of the bug's steps through step 4 (qa/reports/20261110-050643-F07-S03-2411f94895b7/BUG-F07-002-steps.log; the customer's mobile
left out):

```
PROBE form day after change: Thu, May 26, 2101
PROBE times sentence: Free times on Wed, May 25, 2101. Choose one to hold it.
PROBE first chip: 09:00 · Achini
PROBE hold placed: starts_at 2101-05-26T03:30:00+00:00 state held (first day 2101-05-25 , second day 2101-05-26 )
PROBE hold card: Holding this time | 10:00 left | ... | When | Thu, May 26, 2101, 09:00 – 10:00 | Source | Phone | ...
```

Where: `apps/web/src/surfaces/workspace/use-new-appointment.ts`, `search()` (about line 80): the answer is written to
`times` without checking that the values it was asked for are still the form's.

## Re-verification

<QA, after a fix: what was rerun, the run folder, and the result.>
