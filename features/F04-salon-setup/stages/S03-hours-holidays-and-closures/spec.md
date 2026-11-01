# F04-S03 Opening hours, holidays and closures

## Objective

An Owner or Manager sets the salon's weekly opening hours, decides what the salon does on each
public holiday, and closes the salon for a day. The operator publishes the year's holiday and Poya
calendar from the official gazette, and every salon reviews it. All of this saves immediately and
is public through the API. It is also available to the booking features through one interface,
so F07 can offer only open times and list the bookings a change affects.

## Read first

- `docs/brd/requirements.yaml`: STF-04 and NFR-13.
- `features/F04-salon-setup/README.md`: saving vs publishing, closures before bookings exist, and
  the feature-level test focus.
- `features/F04-salon-setup/stages/S01-profile-and-publishing/` (spec and handoff): the Settings
  page, permissions, branch scope and `SalonBranches` this stage builds on.
- `docs/architecture/operator-console.md`: "Holiday calendar (F04-S03)", and authority, roles and
  audit.
- `docs/architecture/multi-tenancy.md`: "Adding a table" (with the branch item), "Adding an
  endpoint", "Platform data and operator access", "Tests you must write".
- `docs/decisions/DECISION_LOG.md`: decisions 058, 073, 078, 083, 085, 087 and 088.
- `docs/engineering/backend.md`: modules, provided interfaces, ownership of tables and paths,
  time (the clock and the salon's time zone), jobs, tests.
- `docs/engineering/web.md`: BFF rules, "Styling" (the component map, including the Popover with
  Calendar and the Toggle rows), "Tests".
- `docs/design/ui-ux-standards.md` and `docs/design/prototype/index.html`:
  - Salon web, Settings, **Hours & closures** (`setHours`), and the **Add a closure** sheet
    (`cloPaint`, `cloImpact`);
  - the Platform console overview's **Holiday calendar** card (`holPlat`).
- `docs/qa/test-plan.md`: the test clock.
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| STF-04 | Salon opening hours, special closures and public holidays. The Sri Lankan holiday calendar, including Poya days, is published each year by the operator from the official government calendar, not built into the code, and each salon accepts or edits it. Closing a date lists the bookings it affects for action. | Full, except that the bookings a closure affects are listed from F07, when bookings exist. This stage provides the interface F07 implements |

NFR-13 applies: dates and times are in the salon's time zone, and the time and date formats come
from configuration.

## Scope

- **API (`staff`):**
  - weekly opening hours per branch;
  - each salon's choices for published holidays;
  - special closures;
  - the public opening information;
  - the provided interface `OpeningSchedule`;
  - the declared interface `BookingsOnDate`, with the implementation that returns none.
- **API (`platform`):**
  - the holiday calendar per year: draft, CSV import, editing, publication and revisions;
  - the provided interface `HolidayCalendar`.
- **Web (workspace):** the **Hours & closures** tab, with the opening hours, public holidays and
  special closures cards, and the Add a closure sheet.
- **Web (console):** a **Holiday calendar** page for operators.

## Out of scope

- Each professional's own working hours, breaks and leave (STF-01 to STF-03, F06).
- Listing real bookings on a closed day or outside new hours, and the "Needs attention" list:
  bookings arrive in F07, and the attention list in F10 (BKG-13). Both consume this stage's
  interfaces.
- Closing part of a day. A closure is a whole day; shorter opening on a day is "special hours" on
  a holiday only.
- More than one branch (F18). No screen names a branch.

## Acceptance criteria

- AC-1: An Owner or Manager opens **Settings → Hours & closures** and sets each weekday as open,
  with an opening and a closing time in 15-minute steps, or as closed. **Save hours** saves at
  once. Until a salon first saves, the form shows the suggested week marked "Not saved yet":
  Monday to Friday 09:00–18:00, Saturday 09:00–19:00, Sunday closed. The public opening
  information then has no weekly hours. A closing time at or before the opening time is a 400
  field error on that day, and nothing is saved. (STF-04)
- AC-2: `GET /api/v1/salon/opening-hours` is public on the salon's hostname. It returns:
  - the weekly hours;
  - the exceptions from today to 180 days ahead, in the salon's time zone: closed days, and
    holidays with special hours, each with its date, its kind, its times where open, and the
    holiday's name;
  - nothing of a closure's reason.
  A saved change shows on the next request. Another salon's hours never appear. (STF-04)
- AC-3: The operator Admin imports a year from a CSV file (`date,name,kind`) as a draft:
  - A valid file replaces the draft's days.
  - A file with any bad line is refused whole, naming each bad line's number and reason, and
    changes nothing. A bad line is a date outside the year, a repeated date, an unknown kind, an
    empty name, or a malformed row.
  - The Admin can add, change and remove single days.
  - Support operators can read the calendar and are refused every change.
  - Every import, edit and publication is in the platform audit.
  (STF-04)
- AC-4: Publishing a year asks for confirmation in the prototype's words, then makes the year
  visible to every salon. Each salon's **Public holidays <year>** card shows "To review" and lists
  the year's days that are not yet past, each with its kind and name. Nothing changes for a salon
  (its public opening information, `OpeningSchedule`) until it accepts its choices. (STF-04)
- AC-5: A salon chooses, for each holiday, Closed, Open as usual, or Special hours with times, and
  presses **Accept these choices**. The card then shows "Reviewed". Closed days and special hours
  apply to the public opening information and `OpeningSchedule` at once. After acceptance,
  changing one day's choice saves at once. Another salon's choices are unaffected. (STF-04)
- AC-6: The Admin edits a published year and publishes again. Before publishing, the console shows
  how many days the revision adds, changes and withdraws. After it, each salon sees only the added
  and changed days as "To review" again, and its earlier choices for unchanged days still apply. A
  withdrawn day disappears, and the salon's choice for it no longer applies. (STF-04)
- AC-7: **Add closure** opens a sheet where the user picks a date (today or later, at most one
  year ahead) and an optional reason that only the team sees (up to 60 characters). The sheet
  shows the bookings on that date through `BookingsOnDate`, which in this stage says "No bookings
  on this day". **Close the salon this day** saves the closure. The date is then closed in the
  public opening information and in `OpeningSchedule`, even if it is a holiday with other
  choices. **Reopen** removes a closure that has not passed. The following are refused:
  - a past date, or one beyond a year, as a 400 field error;
  - a second closure on the same date, as 409 `conflict`, with exactly one closure kept when two
    are sent at once.
  (STF-04)
- AC-8: `OpeningSchedule` answers, for a branch and a local date, whether it is open and between
  which times, from weekly hours, accepted holiday choices and closures, in that order of
  increasing priority. Integration tests prove it for an ordinary day, a closed weekday, an
  accepted closed holiday, accepted special hours, a holiday still to review (weekly hours apply),
  and a closure on a holiday. With a test implementation of `BookingsOnDate`, the closure sheet
  and the hours card show the bookings a change affects ("N upcoming bookings fall outside these
  hours"), and saving keeps every booking. (STF-04)
- AC-9: Dates and "today" follow the salon's time zone: with the test clock at 23:30 on a day in
  `Asia/Colombo` (18:00 UTC), today's closure is allowed and yesterday's is refused. A holiday
  becomes past, and leaves the review list, after its local date ends. (STF-04, NFR-13)
- AC-10: Tenant isolation, roles and states:
  - A salon's settings user cannot read or change another salon's hours, choices or closures. An
    id of another salon's closure is 404, as for one that does not exist.
  - With no tenant context the new tenant tables return no rows.
  - Receptionist, Professional and Accountant are refused every change with 403
    `permission_denied`, and see no Hours & closures tab.
  - A support session reads it and is refused changes with 403 `support_read_only`.
  - Suspended and read-only salons answer as the other settings endpoints do.
  - Salon users cannot reach operator calendar routes, and salon hosts answer 404 for them.
  (STF-04)
- AC-11: A stale `version` on saving weekly hours or accepting holiday choices is 409 `conflict`
  and changes nothing. Every hours change, holiday acceptance or choice, closure and reopening is
  in the salon's audit trail, with actor, time, action and the changed day or date. No closure
  reason appears in logs or the public API. (STF-04)
- AC-12: The Hours & closures tab, the closure sheet and the console's Holiday calendar page
  provide the following at every width `docs/design/ui-ux-standards.md` names:
  - loading, saved, field-error, conflict, unavailable and empty states;
  - the CSV refusal with its line list;
  - keyboard-only use, with focus kept inside the sheet and returned on close;
  - accessible names for every day toggle and time select;
  - no WCAG 2.2 AA violation in the accessibility check.
  (STF-04)

## Design notes

- **Modules (decision 087).**
  - `staff` owns the opening hours, holiday choices and closures, each with `tenant_id` and
    `branch_id`. It checks the branch through `branding`'s `SalonBranches`, and reads published
    holidays through `platform`'s `HolidayCalendar`.
  - `platform` owns the calendar tables, with no `tenant_id` and grants for its own role. The
    application role reads only what `HolidayCalendar` needs, or reads through a narrow function.
    Choose the mechanism that keeps the platform tables out of the tenant role's reach, as
    `docs/architecture/multi-tenancy.md`, "Platform data and operator access", requires. Record
    the choice in the handoff.
  - Update `allowedDependencies` for `staff` (shared, branding, platform). No module may come to
    depend on `staff` except `booking` later.
- **Priority.** A closure overrides a holiday choice, which overrides the weekly hours. A holiday
  with no accepted choice changes nothing.
- **Revisions.** A salon's choice records the revision of the day it was made against. A day whose
  date, name or kind a later revision changes is "To review" again; an unchanged day keeps its
  choice. A choice for a withdrawn day is kept for the audit, but no longer applies.
- **`BookingsOnDate`.** Declared in `staff`'s root: the bookings of a branch on a local date, and
  those that a proposed week of hours leaves outside. Each booking is a time, a customer display
  name and a service name. The implementation in this stage returns none. F07 replaces it, with
  no change to `staff`.
- **Time.** All dates are local dates in the salon's time zone (`tenant` already has it). Times
  are stored as local times, never as instants. "Today" comes from the application's clock in
  that zone.
- **The suggested week** is shown only by the web form. Nothing is stored until the first save.

## API and data changes

Salon routes, on the salon's workspace host:

| Method/path | Permission | Request / result |
| --- | --- | --- |
| `GET /api/v1/salon/opening-hours` | none (public) | `{weekly: [{day, open, opens, closes}] or null, exceptions: [{date, kind: closed or special, opens, closes, holidayName}]}` |
| `GET /api/v1/salon/opening-hours/settings` | `settings.read` | `{version, weekly or null, outsideHours: [booking]}` |
| `PUT /api/v1/salon/opening-hours/settings` | `settings.manage` | `{version, weekly}` -> 200 as above; 400 field errors; 409 `conflict` |
| `GET /api/v1/salon/holidays` | `settings.read` | `{years: [{year, revision, publishedAt, reviewed, version, days: [{date, name, kind, choice, opens, closes, toReview, past}]}]}` |
| `POST /api/v1/salon/holidays/{year}/acceptance` | `settings.manage` | `{version, choices: [{date, choice, opens, closes}]}` -> 200 the year; 400; 409 `conflict` |
| `PUT /api/v1/salon/holidays/{year}/days/{date}` | `settings.manage` | `{choice, opens, closes}` -> 200 the year; 409 `invalid_state` before acceptance |
| `GET /api/v1/salon/closures` | `settings.read` | Upcoming closures: `[{id, date, reason, affectedBookings}]` |
| `GET /api/v1/salon/closures/impact?date=` | `settings.read` | `{date, bookings: [booking]}` |
| `POST /api/v1/salon/closures` | `settings.manage` | `{date, reason}` -> 201; 400; 409 `conflict` |
| `DELETE /api/v1/salon/closures/{id}` | `settings.manage` | 204; 404; 409 `invalid_state` once passed |

Operator routes, console host only, under `/api/v1/operator/holiday-calendars`:

| Method/path | Role | Request / result |
| --- | --- | --- |
| `GET /` | Admin, Support | Years with state, revision, day count and publication time |
| `GET /{year}` | Admin, Support | The year's days, its draft changes, and the counts a publication would add, change and withdraw |
| `POST /{year}/import` | Admin | `multipart/form-data` CSV -> 200 the year; 400 with one error per bad line (`line`, `reason`) |
| `POST /{year}/days`, `PUT /{year}/days/{date}`, `DELETE /{year}/days/{date}` | Admin | Single-day edits to the draft |
| `POST /{year}/publication` | Admin | `{version}` -> 200 the year; 409 `conflict` or `invalid_state` when nothing changed |

`booking` in the salon responses is `{time, customerName, serviceName}`. Append migrations: the
`staff` tables with the full "Adding a table" checklist, including `branch_id`, and the `platform`
calendar tables with the platform data rules. Add every table and path to the ownership map, and
validate every new response with the contract validator.

## UI and UX

Follow the prototype, using the component map in `docs/engineering/web.md`, "Styling".

| Screen | Route | Behaviour |
| --- | --- | --- |
| Hours & closures | `/workspace/settings/hours` | Three cards, as in `setHours`. **Opening hours:** each weekday has a Toggle and two Selects, and there is **Save hours** and the outside-hours warning. **Public holidays <year>:** a "To review" or "Reviewed" Badge, the publication date, each day with its kind Badge and a Select (Closed, Open as usual, Special hours, the last showing two time Selects), and **Accept these choices**. **Special closures:** **+ Add closure**, upcoming closures as Item rows with **Reopen**, or the Empty state "No closures planned" |
| Add a closure | Sheet from the tab | Date by Popover with Calendar (past dates and dates beyond a year disabled); reason Field; the affected-bookings list or "No bookings on this day"; **Close the salon this day** |
| Holiday calendar | `/console/holidays` and `/console/holidays/{year}` | Years as Item rows with a state Badge; a year's days in a Table (date, name, kind); Import CSV (the file-choice row), add, edit and remove day; the revision counts; **Publish to salons** with the prototype's Alert Dialog wording; the line-by-line refusal in an Alert |

The tab bar shows Profile, Hours & closures and Branding in the prototype's order. When no year is
published, the holidays card says the operator has not published this year's holidays yet. The
console page uses the neutral theme and the console's navigation. Messages go through next-intl,
English only in R1. Every interactive element has a `data-testid`.

## Risks and test focus

- `time`: the salon's time zone at day boundaries; "today" and past; 180-day window; the test clock.
- `data-integrity`: priority of closures over choices over weekly hours; revisions keeping
  unchanged choices; all-or-nothing import.
- `tenant-isolation`: three new tenant tables and their endpoints. The platform calendar is
  readable by salons only as published, and each salon's choices are its own.
- `permissions`: salon roles, support session, operator Admin vs Support, host binding for
  operator routes.
- `concurrency`: two closures on one date; stale versions on hours and acceptance; two Admins
  publishing.
- `contract`: nullable weekly hours, exception shapes, the CSV refusal's per-line errors.
- `functional`, `responsive`, `accessibility`: the three cards, the sheet, the console page.

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

- The developer has checked every acceptance criterion on the running product, with two synthetic
  salons, each role, a support session, an operator Admin and Support, and the test clock, and
  recorded it in the handoff.
- The verification commands are green.
- `OpeningSchedule` and `BookingsOnDate` are documented in their Javadoc as the contract F07 uses.
  The handoff names the mechanism by which the application role reads the published calendar.

## Corrections at close

Recorded by the lead when the stage closed. No requirement changed:
- **CSV refusal shape.** Each bad line is an item of the shared problem's `errors`, with
  `field: "file"`, the reason in `detail` and the line number in `line`, not a new item with
  `reason` (`docs/engineering/backend.md`).
- **`staff`'s dependencies** also include `identity`, for checking who is acting, as `branding`
  does.
- **`BookingsOnDate`** carries each booking's date as well as its time, customer and service. F07
  replaces the empty implementation by registering its own as `@Primary`. `OpeningSchedule`
  answers at most 400 days per call; a branch with no weekly hours is closed.
- **Holiday calendar access.** The application role reads the published calendar only through
  `holiday_calendar_published(first_year, last_year)` (decision 090).
- **No leave-with-unsaved-edits guard on this tab.** Everything saves at once, as the prototype
  shows; weekly edits not yet saved are lost on leaving.
- **Prototype differences kept:** 24-hour times and dates from the configured locale (NFR-13);
  15-minute steps over the whole day; past holidays left out of the review list; the closure sheet
  picks its date from a Popover with Calendar and closes with Escape or the backdrop; the console's
  Holiday calendar is its own page; on narrow phones a weekday's times sit below its toggle (at
  768 px they stay beside it; corrected at F04-S04 close).
