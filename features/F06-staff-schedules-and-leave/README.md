# F06 Staff, schedules and leave

## What the customer gets

A salon's Owner or Manager keeps the salon's team on one Team page:

- **Team:** add, edit and remove a person with a name, photo, title, highlights, short bio and the
  services they perform, and choose whether customers can see them for online booking. A person can
  take bookings without a sign-in account. Removal is refused while they have upcoming bookings.
- **Sign-in accounts:** the staff accounts screen from F02, now a tab of this page, which also shows
  the team profile each account is linked to.
- **Schedule:** each person has weekly working hours with breaks, changes for specific dates, leave
  and labelled blocked time. Bookings already in a period of leave or blocked time are kept and
  flagged for action, and none is cancelled for the salon.

Customers read the profiles of the people shown for online booking through the public API now. Their
screens arrive with the app (F15).

## Requirements

- Profiles, the services a person performs, online visibility, add, edit and remove with the guard,
  bookable without a sign-in: STF-01 (the customer screen is F15's, decision 082)
- Working hours per weekday with breaks, and date overrides: STF-02
- Leave and time off, with bookings kept and flagged: STF-03
- Blocked time with a label: STF-05

STF-04 (the salon's own hours and closures) is F04's. STF-06 ("my day") is not in this feature.

## How it is built

- **Surfaces:** API and staff web. There are no customer screens in R1 (CR-010).
- **Design:** the reference is the prototype's Team page and profile sheet (`stOpen`, `aStaff`). The
  schedule sheet, the Sign-in accounts tab and the service picker were drawn under CR-023 for the
  owner to confirm before planning (decision 070, `docs/design/ui-ux-standards.md`).
- **Not built, though the original prototype sheet shows them:** the price premium (price levels
  replace it, decision 081), consultations (F17), the owner badge, ratings (STF-07), "Works at"
  branches (F18), and closing the salon from this page (F04's Settings owns closures).
- **Module:** `staff` owns team members and their schedules (`docs/engineering/backend.md`). It
  reads services and categories through `catalogue`'s provided interface, so `catalogue` joins its
  allowed dependencies, and branches through `branding` as it does today.
- **A team member is not a staff account.** A member belongs to `staff`, an account to `identity`.
  A member may link to at most one account of the same salon, and an account to at most one member.
  Removing one never removes the other. F10 uses the link for a Professional's "own" data scope.
- **Provided to F07:** who is bookable and for which services, and a person's working windows on a
  date after breaks, date changes, leave and blocked time. F07 intersects those with the salon's
  `OpeningSchedule`.
- **Questions `booking` answers (decision 087's pattern):** does this person have upcoming bookings
  (for a service), and which of their bookings fall in a period. Until F07 an implementation that
  answers "none" applies, so nothing is refused or flagged in practice. F10 shows flagged bookings
  and blocked time on the calendar.
- **Double booking:** leave and blocked time are not part of `booking`'s exclusion constraint,
  which covers holds and bookings only. F07 reads them when it offers a time. A booking that
  races a new block is kept and flagged, like any other booking in that period.
- **Times** are local to the salon, as opening hours are. A person's hours may go beyond the
  salon's: the screen warns, and bookings still follow the salon's hours.
- **Branch scope (decision 073):** every new table carries `branch_id`. A team is salon-wide in R1,
  under the default branch.
- **Photos:** stored and served like service photos (decision 086), through the shared image checks.
- **Depends on:** F04 (default branch, opening hours), F05 (services and categories, photos).

## Stages

The order and status of the stages is in `features/README.md` (generated). This section explains
why the feature is divided the way it is.

| Stage | Delivers | Why it is its own stage |
| --- | --- | --- |
| F06-S01 | The Team page with its Team and Sign-in accounts tabs and the empty state. Members added, edited and removed unless upcoming bookings need them, with photo, title, highlights, bio, the services they perform, online visibility and an optional sign-in link. The public team API. | The riskiest part: the data F07 books against, the link between member and account, the seat rule and the cross-module guard. Everything else hangs off a member. |
| F06-S02 | The schedule sheet with Weekly hours and Date changes: hours per weekday, several breaks, copy to working days, use the salon's hours, a warning outside opening hours, one change per date. New members start on the salon's hours. A person's working windows on a date. | The rules and the provided answer F07's availability depends on. Date arithmetic and validation deserve their own proof before anything subtracts from them. |
| F06-S03 | The Leave and Blocked time tabs: leave as a range of whole days with a type and a private note, blocked time as part of one day with a required label, the bookings each affects listed and kept. The working windows now exclude both. | Both take time away and share the flag-and-keep behaviour, and they change the answer S02 provides. Built last because they read through it. |

## Feature-level test focus

- **Journey:** an Owner adds a person without a sign-in, assigns services, sets their week and a half
  day, adds leave and a block; the working windows and the public team API then say exactly that.
- **Isolation:** another salon's staff, and the operator outside a support session, can neither read
  nor change any of it. One salon's public team API never shows another's people. A member cannot
  link to another salon's account or perform another salon's service.
- **Privacy:** the public API never returns leave, its notes, blocked-time labels, sign-in details or
  hidden people. Roles without the team permissions are refused.
- **Guards:** removal and dropping a service are refused while upcoming bookings need them, and
  bookings in leave or blocked time are listed and kept (proved through the interfaces until F07,
  when QA's deferred cases run).
- **Boundaries:** a break outside the hours, overlapping breaks, a finish before a start, leave ending
  before it starts, overlapping leave or blocks, two changes on one date, simultaneous saves.
- **Seats:** the plan limit holds for one person, linked or not, under simultaneous requests from
  both the Team page and the staff accounts screen, and in a plan move.

## Decisions and open questions

- **Confirmed by the owner on 2026-11-02 (decision 099):** the drawn design, the outline, and:
  - A staff seat is one person: seats used are active accounts plus members with no linked account.
  - A person's services are chosen by whole category, which also covers services added to it later,
    or by single services.
  - Owner and Manager change the team, hours, leave and blocked time, and alone read leave notes. A
    Receptionist also adds and removes blocked time. Receptionist and Professional read the team and
    hours. An Accountant has none of it. Professionals cannot request leave in R1.
  - Blocked time over an existing booking is allowed; the booking is kept and flagged, as for leave.
- **Planning S01 (decision 100):** paths, permissions, the member-and-account link, how seats hold
  across `identity` and `staff`, and what `catalogue` provides.
