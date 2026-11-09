# F06-S02 Working hours and date changes

## Objective

Each team member gets a schedule. The Owner and Manager set a person's hours for each weekday, with
breaks, and change single dates for a half day, an extra working day or a day off. New people start on
the salon's opening hours. F07 reads a person's working times for any date through a provided interface.
Existing bookings are never moved or cancelled by a change: those left outside the new hours are listed.

## Read first

- `docs/brd/requirements.yaml`: STF-02.
- `features/F06-staff-schedules-and-leave/README.md`, and F06-S01's `spec.md` with its "Corrections at close":
  the member, roles, and how the team's requests and screens work.
- `docs/decisions/DECISION_LOG.md`: decisions 073, 087, 099, 100, 101 and 102.
- `features/F04-salon-setup/stages/S03-hours-holidays-and-closures/spec.md`: AC-1, AC-7, AC-9 and AC-11, the
  grammar these hours follow: 15-minute steps, "today", a date's limits, a second one on a date.
- `docs/architecture/multi-tenancy.md`: "Adding a table", "Adding an endpoint", "Tests you must write".
- `docs/architecture/identity-and-access.md`: "Role permissions".
- `docs/engineering/backend.md`: "Provided interfaces", "Ownership of tables and paths", "Persistence and
  migrations", "Time", "Tests".
- `docs/engineering/web.md`: BFF rules, "Styling" (the component map), "Tests".
- `docs/design/ui-ux-standards.md` and `docs/design/prototype/index.html`: Salon web, **Staff**, in the F06 block
  at the end of the file: `hrsLine`, `schOpen`, `schTabs`, `schErr`, `schOut`, `schWeek`, `schCopy`, `schSalon`,
  `schSave`, `schOv`, `ovSave`, `schList`, `conflictNote`, `schPaint`.
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| STF-02 | Working hours per person per weekday, with breaks, and overrides for specific dates. | Full. Which bookings a change affects is asked of `booking` through an interface that answers "none" until F07 |

## Scope

- **API (`staff`):** a member's weekly hours and breaks, date changes, the provided interface `TeamSchedule`,
  and the declared interface `MemberBookingsAffected` with an implementation that answers "none". A new member's
  week is copied from the salon's opening hours. `GET /api/v1/team/members` gains each member's week.
- **Web:** the **Schedule** button and the **Hours** row on each card, and the schedule sheet with **Weekly hours**
  and **Date changes** tabs.

## Out of scope

- Leave and blocked time, and their tabs on the sheet (F06-S03). `TeamSchedule` does not subtract them yet.
- Combining a person's hours with the salon's opening hours, closures and holidays into bookable times, which is
  F07's: it reads this answer and `OpeningSchedule` and offers what both allow.
- Booking, and showing a person's bookings or flagged bookings on a calendar (F07, F10).
- A person's hours in the public team list or the customer's screens (F15 reads availability from F07).
- Hours per branch (F18), a schedule that changes from a future date, rotas repeating over several weeks, and a
  Professional changing their own hours.

## Acceptance criteria

- AC-1: A new member's week is copied from the salon's weekly opening hours when they are added: each open weekday
  as a working day with those times and no break, each closed weekday as not working. A salon that has never saved
  opening hours gives a week with no working days. Changing the salon's hours afterwards does not change anyone's
  week. A member added before this stage reads as having no working days. (STF-02, decision 102)
- AC-2: An Owner or Manager opens **Schedule** on a card, sees **Weekly hours**, and sets each weekday as working,
  with a start and a finish in 15-minute steps, or as not working. **Save hours** saves the whole week at once and
  says "Hours saved. New bookings follow them. Existing bookings are kept." A finish at or before the start, or a time
  not on a 15-minute step, is a 400 field error on that weekday, nothing is saved, and the sheet says "Mon: the finish
  must be after the start." and disables **Save hours** while it lasts. (STF-02)
- AC-3: A working day has up to 5 breaks, added with **+ Add break** and removed with its own control. A break ends
  after it starts, on 15-minute steps, inside the day's start and finish. Two breaks may touch but not overlap. A broken
  rule is a 400 field error on that weekday, nothing is saved, and the sheet says "Mon: a break must end after it
  starts.", "Mon: a break must be inside the working hours." or "Mon: breaks can't overlap." The sixth break is refused
  the same way. A weekday set to not working keeps no breaks when saved. (STF-02)
- AC-4: **Copy Monday to working days** copies Monday's times and breaks to every other working day, and is disabled
  while Monday is not working. **Use the salon's hours** sets every weekday from the salon's opening hours, leaves the
  breaks of days that stay working, and is disabled, with its reason, when the salon has none. Neither saves until
  **Save hours**. (STF-02)
- AC-5: When a working day falls outside the salon's opening hours the sheet warns, without refusing: "Outside opening
  hours. Sun: the salon is closed. Mon: the salon is open 9:00 AM – 6:00 PM. Bookings still follow the salon's hours."
  Saving is allowed, and the hours are stored as entered. (STF-02)
- AC-6: Saving weekly hours is optimistic: a stale `version` is 409 `conflict` and changes nothing, and of two saves of
  one version sent at once exactly one succeeds. A save that changes nothing is 409 `invalid_state`. The sheet shows
  the conflict with a way to reload, and guards leaving with unsaved edits. (STF-02)
- AC-7: On **Date changes** an Owner or Manager adds a change for one date, today or later and at most one year ahead by
  the salon's date, as **Different hours** (a start and a finish with breaks, under the rules of AC-2 and AC-3) or
  **Not working**, with an optional note of up to 60 characters. A date that is past or beyond a year is a 400 field
  error. A second change on one date is 409 `conflict`, the sheet says "This date already has a change. Remove it
  first.", and of two sent at once exactly one is kept. A member has at most 100 upcoming date changes, the next being
  409 `limit_reached`. **Remove** takes a change away ("Back to the weekly hours on that date."), and removing one
  that has passed or is gone answers 404. The tab lists changes from today on, in date order, and otherwise says "No
  date changes." (STF-02)
- AC-8: A date change replaces the weekly hours on that date only: a working day becomes not working, a day off becomes
  working, and different hours replace both the times and the breaks. (STF-02)
- AC-9: `TeamSchedule`, for the caller's salon only, answers for a member and a local date, or each date of a range of
  at most 400 days: whether they work, and the windows they can be booked in, being the hours with the breaks taken out,
  in time order. It says whether a date change decided it. A removed member works no date. An unknown or another salon's
  id is "not found", and a range that is too long or reversed is refused. It ignores opening hours, closures and holidays:
  a day the salon is closed still answers the person's hours. Nothing is cached, and a change applies to the next call.
  Javadoc names it as the contract F07 uses. (STF-02, decision 102)
- AC-10: Dates follow the salon's time zone: with the test clock at 23:30 on a day in the salon's zone that is already the
  next day in UTC, "today" is the salon's, and a date change for the salon's today is accepted. (STF-02)
- AC-11: The answer to saving weekly hours lists the upcoming bookings the new hours leave outside the person's working
  time, and the answer to adding a date change those it affects, from `MemberBookingsAffected`: date, time, customer and
  service. Nothing is moved or cancelled. The sheet says "N upcoming bookings fall outside these hours. They are kept."
  with the list. An integration test with an implementation that reports a booking proves the list. (STF-02)
- AC-12: Each card's **Hours** row summarises the week: "Mon–Sat, 9:00 AM – 6:00 PM · break 1:00 PM" when the working days
  run in one unbroken stretch with the same times and breaks ("· 2 breaks" with more than one, nothing with none), a
  single day as "Mon, 9:00 AM – 6:00 PM", "Varies by day" otherwise, and "No working days" with none. It does not reflect
  date changes. (STF-02)
- AC-13: Roles:
  - `team.read` (Owner, Manager, Receptionist, Professional, a support session) reads a person's schedule, read-only
    where it lacks `team.manage`: no **Save hours**, **+ Add break**, **+ Change a date** or **Remove**;
  - only a caller with `team.manage` receives a date change's note;
  - other roles get 403 `permission_denied` from every change endpoint, and a support session 403 `support_read_only`;
  - an Accountant gets 403 `permission_denied` from every schedule endpoint;
  - nothing of a schedule appears in the public team list.

  Suspended and read-only salons answer as the catalogue endpoints do. (STF-02)
- AC-14: Tenant isolation: another salon's member and date changes are never readable or changeable and answer "not
  found", including through `TeamSchedule`. With no tenant context the new tables return no rows. (STF-02)
- AC-15: Every save of weekly hours, naming the weekdays it changed, and every date change added or removed is written to
  the salon's audit trail with actor, time and before and after values. (STF-02)
- AC-16: Both tabs of the sheet provide, at every width `docs/design/ui-ux-standards.md` names:
  - loading, empty, field-error, conflict, refused and unavailable states;
  - keyboard-only use, with accessible names saying which weekday and which break a control belongs to ("Monday break
    1 starts");
  - no sideways scrolling at 360 pixels;
  - no WCAG 2.2 AA violation in the accessibility check.
  (STF-02)

## Design notes

- **Module.** `staff` only. It reads the salon's opening hours through its own opening-hours code. No new module
  dependency and no in-process contract: a schedule takes no seat, so it takes no identity lock.
- **Branch scope.** Every new table carries `tenant_id` and `branch_id` (the default branch).
- **Time.** Local dates and local times, as opening hours are (decision 087); no instant is stored. "Today" comes from the
  application's `Clock` and the salon's time zone.
- **Storage.** A week of seven rows per member, with their breaks, whose member version guards the save, or an equivalent
  the developer records in the handoff. A date change is a row with its breaks. A unique index on salon, member and date
  refuses a second change under simultaneous requests. Enforce the steps and "finish after start" with `CHECK`s, as
  `staff_opening_hours` does. The erasure function learns the new tables.
- **Versions.** A schedule has its own version, so saving someone's hours never conflicts with editing their profile.
- **A new member's week** is read before the transaction that adds them, since `OpeningSchedule`'s rule is to call it
  outside one. A change to the salon's hours between the two simply is not copied.
- **The bookings question.** `MemberBookingsAffected` is declared in `staff`'s root package, reusing `AffectedBooking`,
  with an implementation in `staff` answering "none", replaced by `booking`'s in F07 (decision 087's pattern). It asks
  which of a member's upcoming bookings a proposed week leaves outside, and which fall on a date under a proposed change.
- **`TeamSchedule` is written to be extended:** F06-S03 subtracts leave and blocked time inside it, with no change for F07.

## API and data changes

Routes are `staff`'s, on the salon's workspace host. Times are `HH:mm`, dates `YYYY-MM-DD`.

| Method/path | Permission | Request / result |
| --- | --- | --- |
| `GET /api/v1/team/members/{id}/schedule` | `team.read` | `{version, weekly: [day], dateChanges: [change]}` |
| `PUT /api/v1/team/members/{id}/schedule/weekly` | `team.manage` | `{version, weekly: [day]}`, each of the seven weekdays once -> 200 `{version, weekly, affected: [booking]}` |
| `POST /api/v1/team/members/{id}/schedule/date-changes` | `team.manage` | `{date, working, starts, finishes, breaks, note}` -> 201 `{change, affected: [booking]}` |
| `DELETE /api/v1/team/members/{id}/schedule/date-changes/{changeId}` | `team.manage` | 204 |

A day is `{day: "monday", working, starts, finishes, breaks: [{starts, finishes}]}`, the times `null` and `breaks` empty when
not working. A change is `{id, date, working, starts, finishes, breaks, note}`, `note` absent without `team.manage`. A
booking is `{date, time, customerName, serviceName}`. `GET /api/v1/team/members` and `/{id}` gain `weekly`, which is additive.
Field names may follow the API conventions where they differ; the handoff records the final shapes.

Append migrations with the full "Adding a table" checklist. Update the ownership map, the erasure function and the
database isolation test's lists, and validate every new response with the contract validator.

## UI and UX

Follow the prototype (`schWeek`, `schOv`, `schPaint`) and the component map:
- Sheet for the schedule, over the Team page, headed with the person's avatar and name;
- Tabs for Weekly hours and Date changes (F06-S03 adds two), scrolling sideways on a phone as the prototype's do;
- Toggle with the `chip` variant for a weekday, F04's `TimeSelect` for every time;
- Field and Input for the date and note, the date with the component map's date control;
- a segmented choice for Different hours and Not working, as the component map names `.seg2`;
- Item rows for date changes, Alert for the information, warning and refusal messages, Sonner for the saves.

| Screen | Where | Behaviour |
| --- | --- | --- |
| Card | Team tab | The Hours row of AC-12 above Online booking, and **Schedule** beside **Edit profile**, for every role with `team.read` |
| Weekly hours | Schedule sheet | The banner "Customers can book this person inside these hours while the salon is open.", the seven days, their breaks, the two shortcuts and **Save hours** |
| Date changes | Schedule sheet | The banner "A date change replaces the weekly hours on that date only. Use it for a half day or an extra working day.", **+ Change a date**, the form and the list |

The Profile sheet's closing line, "New people start on the salon's opening hours. Change them under Schedule.", appears now.
Messages go through next-intl, English only in R1, times through `@salon/format`'s `formatClock`. Every interactive element
has a `data-testid`.

## Risks and test focus

- `data-integrity`: `TeamSchedule`'s windows are the highest risk, since F07 trusts them: breaks taken out correctly at their
  edges (a break touching the start or finish, two touching breaks, a break covering the whole day is impossible by rule);
  a date change replacing times and breaks; a weekly save leaving date changes alone; a week copied once and never linked to
  the salon's hours afterwards.
- `boundary`: 15-minute steps, 23:45, the fifth and sixth breaks, today, a year ahead and one day beyond, the 100th and 101st
  date change, the salon's date against UTC.
- `concurrency`: stale versions; two saves of one version; two changes on one date; a save beside a profile edit, which must
  not conflict.
- `tenant-isolation` and `permissions`: every table and endpoint; `TeamSchedule`; each role and a support session; the note;
  nothing in the public list.
- `api` (contract): every response, including null times, an absent note and the 409 codes.
- `functional`, `responsive`, `accessibility`: both tabs at 360 pixels with several breaks, read-only roles, the warning, the
  shortcuts, the leave guard.

## Verification commands

```bash
cd apps/api && ./mvnw -q verify
pnpm -r lint
pnpm -r typecheck
pnpm -r test
pnpm --filter @salon/web test:a11y
pnpm --filter @salon/web build
pnpm contracts:check
```

## Done criteria

- The developer has checked every acceptance criterion on the running product with two synthetic salons, each role and a
  support session, and recorded it in the handoff.
- The verification commands are green.
- `TeamSchedule` and `MemberBookingsAffected` are documented in their Javadoc as the contracts F07 uses and implements.

## Corrections at close

Recorded by the lead when the stage closed (decision 103). No requirement changed. These were given during the build and
take precedence over the criteria above:

- **Clock format.** F06 uses the existing 24-hour `formatClock`, like F04: "Mon: the salon is open 09:00 – 18:00.",
  "Mon–Sat, 09:00 – 18:00 · break 13:00", "Mon, 09:00 – 18:00". The owner may ask for 12-hour across the product later.
- **A working day has time left.** Breaks that leave none are a 400 field error: "Mon: breaks can’t fill the whole day. Set it
  as not working.", and on a date change, on `breaks`, "Breaks can’t fill the whole day. Set it as not working." A break
  touching only the start or only the finish is allowed. `TeamSchedule` with `working: true` always has a window.
- **"Today" (AC-10)** is the salon's date in either direction. The spec's example cannot happen in Colombo.
- **Versions.** Adding or removing a date change does not raise the schedule's version.
- **No saved opening hours (AC-5):** one warning, "The salon hasn’t set its opening hours yet, so nobody can be booked."
- **AC-5's days** run Monday first.
- **The sixth break.** The API answers 400; on the sheet **+ Add break** is disabled at five, with "Up to 5 breaks a day."
- **AC-11's sentences.** "1 upcoming booking falls outside these hours. It is kept." and "N upcoming bookings fall outside these
  hours. They are kept."; for a date change "1 upcoming booking on this date is affected. It is kept." and "N upcoming bookings on
  this date are affected. They are kept."
- **The 100 date-change limit** holds under simultaneous requests: adding a change locks the member's row, which also stops a
  change being added to someone being removed.
- **`GET` schedule** returns date changes from the salon's today on, breaks in time order, and also `today`, so the sheet's
  calendar follows the application's clock.
- **The Hours row.** The week starts on Monday, and Sunday to Monday is not one stretch.
- **The sheet.** Moving between its tabs keeps unsaved edits with no prompt, and the leave guard applies on leaving the sheet.
  **Save hours** is disabled while nothing has changed.
- **Apostrophes** in messages are curly, as in the prototype and F05.
- **No backfill (AC-1):** a member with no week rows has no working days.
- **Audit.** A weekly save names only the weekdays that changed. A date change's note is never written to the audit trail,
  which records `hasNote`, because a support session can read the audit and must not receive notes. This limits AC-15.
- **As built.** The sheet reads the salon's week from the public opening-hours endpoint, so roles without `settings.read` get
  the warning. Every member answer gains a required `weekly`. A removed member's schedule rows stay, unreadable by any route,
  until salon erasure. A wait beyond the API's 3-second request limit answers 503.
- **Not proved on the running product until F07:** AC-9, AC-11 and the part of AC-8 that shows only through `TeamSchedule`, proved
  by integration tests and, for the sheet's words, by replacing the API's answer.
- **The sheet's handle on a phone** measures 0px when content overflows, on this sheet and F06-S01's. F06-S03 corrects it in the
  shared Sheet (decision 103).

## CR-026 (follow-up after the merge of PR #23)

This changes how the Date changes rows print their dates, and is built and verified in one follow-up PR after F06's (decision 098). Read
`docs/changes/CR-026-team-page-refinements.md` and decision 107.

- AC-17: The **Date changes** tab's rows print their dates in the short day form of decision 107: a date in the salon's
  current year, by the salon's own date, without the year ("Thu, Nov 12"), and a date in another year with it ("Tue, Jan 5,
  2027"). The Remove button's accessible name uses the same form. The form's date button keeps the year. (STF-02, CR-026)
