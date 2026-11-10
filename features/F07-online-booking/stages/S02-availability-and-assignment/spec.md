# F07-S02 Availability and "No preference" assignment

## Objective

Staff ask which times are free for a service on a date, for one professional or for "No preference", and get the answer
worked out live from every input BKG-01 names. "No preference" times each show the professional the salon's strategy
would give the booking, and a hold placed with "No preference" is assigned at that moment, so a time taken by someone
else between the search and the hold still goes to another free professional. Booking rules gain how long a hold lasts
and the "No preference" strategy, on the existing Booking rules screen. The same calculation is ready for F15's
customers, who will also be bound by the notice and horizon of BKG-02.

## Read first

- `docs/brd/requirements.yaml`: BKG-01, BKG-03, NFR-03; and BKG-02, BKG-04, which this stage reads.
- `features/F07-online-booking/README.md`, F07-S01's `spec.md` and its `handoff` (the endpoints, the overlap mechanism
  and any "Corrections at close"), and decisions 074, 087, 089, 091, 102, 104 and 108 in `docs/decisions/DECISION_LOG.md`.
- `docs/engineering/backend.md`: "Booking integrity", "Provided interfaces", "API conventions", "Persistence and
  migrations", "Time", "Tests".
- `docs/architecture/multi-tenancy.md`: "Adding an endpoint", "Tests you must write".
- `docs/architecture/identity-and-access.md`: "Role permissions" (`settings.manage`, `calendar.read`, `booking.manage`).
- `docs/engineering/web.md`: BFF rules, "Styling" (the component map), "Tests"; `docs/design/ui-ux-standards.md`.
- `docs/design/prototype/index.html`: Salon web, **Settings → Booking rules**, in the F07 block at the end of the file:
  `setRules` (the "Holding and assigning" section), and `f7Find` for how free times read.
- The Javadoc of `staff.OpeningSchedule`, `staff.TeamSchedule`, `staff.TeamMembers`, `catalogue.ServiceTerms` and
  `booking.BookingPolicies`.
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| BKG-01 | Availability is calculated in real time from opening hours, each professional's hours and breaks, leave, blocked time, existing bookings, current holds and service buffers. | Full for staff; the customer read is built and unit tested, and exposed by F15 |
| BKG-03 | "Any available professional" assigns an eligible professional by a strategy the salon chooses. | Full |
| NFR-03 | Availability search under 500 ms at the 95th percentile; other reads under 300 ms. | Developer measurement here; the release suite measures it under load when the owner asks |

## Scope

- **API (`booking`):** the availability calculation as a domain service; the staff availability and open-days reads; the
  hold endpoint accepting "No preference"; the hold length and strategy in the booking rules version, its endpoints and
  its history; a provided interface for the calculation that F09, F10 and F15 will reuse.
- **Web:** the "Holding and assigning" section on the Booking rules screen (F04-S04's screen).

## Out of scope

- The New appointment sheet and anything that shows free times to staff (F07-S03).
- Customer availability over the public API and the shared app link (F15). Rescheduling's search (F09).
- Treatment rooms and other resources, and processing time on screens (F17, F18); the calculation already honours
  processing segments (decision 074).
- Several services in one booking.

## Acceptance criteria

- AC-1: `GET /api/v1/bookings/availability?serviceId=&variantId=&date=&memberId=` returns, for one local date, the start
  times a booking of that service (and variant) could take, in time order, each with the professional who would take it.
  A time is offered only when the whole booking fits, for that professional, inside the branch's opening hours
  (`OpeningSchedule`, with holidays and closures) and inside one of their working windows (`TeamSchedule`, which already
  excludes breaks, leave and blocked time), and overlaps no live booking or unexpired hold of theirs, its buffer included.
  The answer reflects a booking, hold, release, expiry, cancellation, leave or block made a moment before. (BKG-01)
- AC-2: Start times are on the salon's slot interval (BKG-02), counted from the local midnight (with 15 minutes: 9:00,
  9:15, 9:30). For staff, today's times start after the current time, the minimum notice and the horizon do not apply,
  and a date before today or more than one year ahead is 400 on `date` (decision 108). The domain service also answers for
  customers, applying the notice and the horizon, proved by unit tests; no endpoint exposes it yet. (BKG-01, BKG-02)
- AC-3: With `memberId` the times are that professional's alone; a professional who does not perform the service is 409
  `slot_unavailable`, and another salon's or a removed one is 404. Without it ("No preference"), each time is offered once,
  with the eligible professional the strategy picks among those free then: with `least_booked` the one with the fewest
  minutes of live bookings and unexpired holds that local date, with `most_booked` the most, ties in the order the Team
  page lists them. Eligible means not removed and performing the service, shown online or not. (BKG-03, decision 108)
- AC-4: An empty answer says why in `reason`: `closed` (the branch is closed that date), `nobody_performs`, `not_working`
  (the chosen professional, or every eligible one, works no time that date) or `fully_booked`. (BKG-01)
- AC-5: `POST /api/v1/bookings` (F07-S01) also accepts `"memberId": null` for "No preference": the server assigns, at that
  moment and by the same strategy, an eligible professional free for the whole booking, trying the next one when the
  constraint refuses the first, and answers 409 `slot_taken` only when nobody is free. Of simultaneous "No preference"
  holds for one time with three eligible free professionals, three succeed with three different professionals and the
  rest are 409, proved by a concurrency integration test. The booking records that it was assigned, for F13's reports.
  (BKG-03, BKG-05)
- AC-6: `GET /api/v1/bookings/open-days?from=&to=` (at most 400 days, from today on) lists the local dates the branch is
  open, for S03's day choice. (BKG-01)
- AC-7: The booking rules gain `holdMinutes` (5, 10, 15, 20 or 30; 10 for every existing version and for a salon that has
  not chosen) and `noPreference` (`least_booked` or `most_booked`; `least_booked` likewise). A save creates a new version,
  as every rule does; a bad value is a 400 field error. Every hold placed takes its expiry from the current version's
  `holdMinutes`, and a hold already placed keeps its expiry. History rows show the change. The public booking rules read
  does not include `noPreference`. (BKG-03, BKG-04, decision 089)
- AC-8: On **Settings → Booking rules**, an Owner or Manager sees, after "Booking times", the section "Holding and
  assigning": **Hold a chosen time for** (5, 10, 15, 20 or 30 minutes) with "While a time is held nobody else can take it.
  It is released by itself afterwards, unless a payment is in progress.", and **"No preference" bookings go to**
  ("Whoever has the least booked that day", "Whoever has the most booked that day") with "The first shares work evenly.
  The second fills one person’s day first and keeps others free for walk-ins. Only people who do the service and are free
  are considered." Both save with the existing **Save booking rules** and its messages, and the summary sentence of the
  history row names a change to either. (BKG-03, BKG-04)
- AC-9: Roles: the availability and open-days reads need `booking.manage` (Owner, Manager, Receptionist); a Professional,
  an Accountant and a support session get 403 `permission_denied`. The rules follow F04-S04's permissions. (decision 108)
- AC-10: Tenant isolation: no answer is ever worked out from another salon's hours, team, leave, bookings, holds or rules,
  and another salon's service, variant or professional answers as an unknown one. (BKG-01)
- AC-11: With a salon of 20 eligible professionals, each with breaks, a block and 15 bookings that day, the availability
  read answers in under 500 ms at the 95th percentile over 200 sequential calls on the developer's machine, measured by a
  test that prints its percentiles; the handoff records them. (NFR-03)
- AC-12: Every new or changed response validates against the committed OpenAPI document; the Booking rules screen keeps
  its loading, error, keyboard and 360-pixel behaviour and passes the accessibility check with the new section. (NFR-12)

## Design notes

- **One calculation.** A pure domain service in `booking.domain`: given the opening day, each professional's working day,
  their occupied ranges, the service's segments, the interval, the clock and the audience, it returns the times. Unit test
  it exhaustively with a fixed clock: edges of windows and breaks, a buffer reaching a booking, a booking ending at
  closing, a day the clocks change in a zone with one (the shared conversion service decides local times). The endpoint
  gathers inputs: one `OpeningSchedule` call, one `TeamSchedule` call per eligible professional, one read of the day's
  occupied ranges for all of them.
- **Processing segments** (decision 074) occupy no professional; the calculation already treats them so, though R1 has none.
- **Assignment at hold time** uses the calculation for the chosen time and the strategy, then relies on the exclusion
  constraint as S01 does; the check is never the protection.
- **Provided interface.** Expose the calculation through a provided interface in `booking` for F09, F10 and F15, with the
  audience as a parameter. Document it in its Javadoc.
- **Rules version.** New columns with defaults so existing versions read `10` and `least_booked`; the immutable-version rule
  of decision 089 holds.

## API and data changes

| Method/path | Permission | Request / result |
| --- | --- | --- |
| `GET /api/v1/bookings/availability?serviceId=&variantId=&date=&memberId=` | `booking.manage` | `{date, times: [{time, startsAt, member: {id, name}}], reason}` |
| `GET /api/v1/bookings/open-days?from=&to=` | `booking.manage` | `{days: [date]}` |
| `POST /api/v1/bookings` | `booking.manage` | as F07-S01, `memberId` may be `null` |
| `GET`/`PUT /api/v1/salon/booking-rules` and its history | as F04-S04 | rules gain `holdMinutes`, `noPreference` |

`reason` is null when `times` is not empty. Field names may follow the API conventions; the handoff records the final shapes.
One migration adds the two rule columns.

## UI and UX

The Booking rules screen only, from the prototype's `setRules` in the F07 block: a "Holding and assigning" heading after
"Booking times", two Selects from the component map with their help text, saved with the existing form. Words through
next-intl, curly apostrophes, `data-testid` on each control.

## Risks and test focus

- `data-integrity`: the calculation's every input and edge (window edges, breaks, buffers, leave, a block, a closure, a
  holiday choice, an expired hold, a cancelled booking, today after now, a zone with daylight saving). Unit first.
- `concurrency`: "No preference" holds racing for one time across several professionals; a hold racing an availability read.
- `functional`: strategy ties and ordering; a rules change applying to the next hold only.
- `tenant-isolation`, `permissions`, `api` (contract), `accessibility` on the rules screen.
- `performance`: AC-11 on the developer's machine; no load test.

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

- The developer has checked every acceptance criterion on the running product with two synthetic salons and each role, and
  recorded it in the handoff with the AC-11 percentiles.
- The verification commands are green.
- The provided interface's Javadoc states the audience rules for F09, F10 and F15.
