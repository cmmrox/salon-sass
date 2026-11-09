# F07 Booking engine and staff booking foundations

## What the customer gets

A salon's front desk books customers who phone, message or walk up, on any day the salon is open.
Staff enter the customer's name and mobile number, the service and, if the customer has one, a
preferred professional. They see the free times worked out live from the salon's hours, each
person's hours, leave and blocked time, and existing bookings and holds. Choosing a time holds it
for a few minutes with a visible countdown, so nobody else can take it while the desk confirms.
"No preference" bookings go to an eligible, free professional by the strategy the salon chooses.
Each booking has a short reference, records where it came from, and moves only through the
allowed states (arrived, in service, cancelled before the start, no-show after the start), with
who did what, when and why. Two bookings or holds can never overlap for one professional, even
when two people book the same time at once. The Services and Team pages start refusing deletes
that would strand upcoming bookings, and closures, leave and blocked time list the bookings they
affect.

Customers book themselves later, in the React Native app (F15), on the same engine.

## Requirements

- Live availability from every input: BKG-01
- "No preference" assignment by the salon's strategy: BKG-03
- Holds with a countdown, automatic release, kept while a payment is in progress: BKG-04
- No overlap, guaranteed by the database under simultaneous requests: BKG-05 (NFR-05)
- One booking state model with an audit of every change: BKG-06
- Staff booking on any open day, mobile required, free times offered, source recorded: BKG-11
  (moved from F10, decision 108)
- Short, readable references unique within the salon: BKG-14
- The channel of every booking: BKG-16
- Availability search under 500 ms at the 95th percentile: NFR-03; the new screens meet WCAG 2.2
  AA: NFR-12

BKG-02 (slot interval, notice, horizon) is F04's and is read here. BKG-15 (universal time) is F01's.

## How it is built

- **Surfaces:** API and staff web. No customer screens in R1 (CR-010).
- **Design:** the prototype's Bookings page, New appointment sheet and appointment sheet, redrawn
  for F07 in `docs/design/prototype/index.html` ("F07 booking screens") and confirmed by the owner
  on 2026-11-09 (decision 108). Not built here, though the original sheets show them: payment
  columns and Take payment (F08, F10), Reschedule (F09), the calendar, Today and walk-ins (F10),
  reminders (F11).
- **Module:** `booking` (`docs/engineering/backend.md`, "Booking integrity"). It reads
  `staff`'s `OpeningSchedule`, `TeamSchedule` and `TeamMembers`, `catalogue`'s `ServiceTerms` and
  its own `BookingPolicies`. Dependencies stay one way: `booking` depends on `staff` and
  `catalogue`, never the reverse.
- **One table for holds and bookings,** with a state, the service's segments copied onto it, and
  an exclusion constraint per salon and professional over the time each segment occupies. The
  check before inserting exists only to give a good message; the constraint is the protection.
- **Terms are frozen:** each booking stores the booking-rules version and the service terms
  version it was made under (decision 089, CAT-04).
- **The contracts other modules declared for F07** are implemented here: `BookingsOnDate`,
  `UpcomingMemberBookings`, `MemberBookingsAffected` (`staff`), `UpcomingServiceBookings` and
  `ServiceBookingCounts` (`catalogue`). Their "none" stand-ins are retired.
- **Payment and checkout seams:** "pending payment" and "completed" are states of the machine, but
  only an in-process contract reaches them, for F08 (payment) and F10 (checkout). No endpoint sets
  them.
- **The customer on a booking** is a name and a mobile number in E.164 kept on the booking. F12's
  customer records link to bookings later.
- **Times** are stored as instants and shown in the salon's time zone (BKG-15). Branch scope:
  every new table carries `branch_id`, the default branch in R1 (decision 073).
- **Depends on:** F02 (roles and the principal's data scope), F04 (hours, closures, booking rules),
  F05 (services, variants, segments), F06 (team, working windows, leave, blocked time).

## Stages

The order and status of the stages is in `features/README.md` (generated). This section explains
why the feature is divided the way it is.

| Stage | Delivers | Why it is its own stage |
| --- | --- | --- |
| F07-S01 | The booking API: holds placed for a professional and time, confirmed or released, expiring by themselves; bookings with references and sources; the state machine with its history; day lists and single reads under the calendar scope; the five contracts other modules wait for. | The riskiest part: the database guarantee under simultaneous requests, and the rules every later feature (payment, rescheduling, checkout, reports) builds on. |
| F07-S02 | Availability search across the eligible team for a service on a date, "No preference" assignment by the salon's strategy, and the two new booking-rule settings (hold length, strategy) on the Booking rules screen. | The calculation with the most inputs and a performance target; it deserves its own proof before a screen depends on it. |
| F07-S03 | The Bookings page, the New appointment sheet with free times, hold countdown and confirm, and the appointment sheet with its allowed actions and history. | The staff-facing part, built on two proven APIs; accessibility and wording are its own focus. |

## Feature-level test focus

- **Journey:** the desk books a customer with "No preference", the time disappears for everyone
  else while held, the booking is confirmed with a reference, and is then arrived, started or
  cancelled with a reason, each change in its history. Deferred F06 case TC-F06-004 runs: the
  times offered for a person with a week, a half day, leave and a block are exactly their
  windows inside the salon's hours.
- **Concurrency (NFR-05):** many simultaneous holds on one professional and time produce exactly one.
- **Isolation:** another salon's bookings, professionals, services and references are invisible
  and unusable; a reference is unique only within its salon. A Professional sees only their own
  bookings unless granted; an Accountant none.
- **State rules:** no completed except through checkout, no no-show before the start, no
  cancellation after it, undo of a no-show only the same day, every change audited.
- **Guards now live:** deleting a service or removing a person with upcoming bookings is refused;
  closures, hours changes, leave and blocked time list the bookings they affect.
- **Performance (NFR-03):** availability under 500 ms at the 95th percentile; measured once in the
  release suite, when the owner asks.

## Decisions and open questions

- **Confirmed by the owner on 2026-11-09 (decision 108):** the drawn design, the outline, and:
  - "No preference" defaults to whoever has the least booked that day; the salon may choose
    whoever has the most instead. Only people who perform the service and are free are considered.
  - Owner, Manager and Receptionist create, cancel and change bookings. A Professional reads
    their own bookings (salon-wide if granted) and marks their own customers arrived or in
    service, but does not create or cancel. An Accountant has no booking access.
  - The customer notice and horizon limit customer bookings only. Staff book any open day from now
    to one year ahead, never in the past.
  - References are six characters from an unambiguous alphabet, shown as `K7M-4QX`.
  - CR-024 stays proposed and is not built with F07.
