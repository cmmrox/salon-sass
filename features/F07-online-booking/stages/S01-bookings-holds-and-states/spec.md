# F07-S01 Bookings, holds and booking states

## Objective

The `booking` module gains bookings. Staff place a **hold** on a professional's time for a service, which nobody else
can take until it is confirmed, released or runs out, and confirm it into a booking with a short reference and the
channel it came from. A booking then moves only through the states BKG-06 allows, each change recorded with who, when
and why. The database itself refuses two holds or bookings that overlap for one professional, however many requests
arrive at once. The questions `staff` and `catalogue` have been asking with "none" stand-ins are now answered from real
bookings, so their guards and lists come alive. This stage is API only; S02 offers free times and S03 the screens.

## Read first

- `docs/brd/requirements.yaml`: BKG-04, BKG-05, BKG-06, BKG-14, BKG-16, NFR-05; and BKG-02, BKG-09, BKG-15, CAT-04, which
  this stage reads but does not own.
- `features/F07-online-booking/README.md`, and decisions 073, 074, 075, 083, 084, 087, 089, 091, 093 to 096, 099 to 104 and
  108 in `docs/decisions/DECISION_LOG.md`.
- `docs/engineering/backend.md`: "Booking integrity" (all of it), "Provided interfaces", "Events between modules",
  "Ownership of tables and paths", "API conventions" (idempotency, problem codes, lists), "Persistence and migrations",
  "Time", "Transactions, outbox and external calls" (recurring jobs, the audit trail), "Security", "Tests".
- `docs/architecture/multi-tenancy.md`: "Adding a table", "Adding an endpoint", "Background jobs", "Tests you must write".
- `docs/architecture/identity-and-access.md`: "Role permissions" (`calendar.read` and its data scope, IAM-08, what a
  support session reads), `Principals`, `StaffLabels`.
- The Javadoc of every interface this stage reads or implements: `staff.TeamSchedule`, `staff.OpeningSchedule`,
  `staff.TeamMembers`, `staff.BookingsOnDate`, `staff.UpcomingMemberBookings`, `staff.MemberBookingsAffected`,
  `catalogue.ServiceTerms`, `catalogue.UpcomingServiceBookings`, `catalogue.ServiceBookingCounts`,
  `booking.BookingPolicies`.
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| BKG-04 | Selecting a time places a hold (10 minutes by default) with a visible countdown. The hold appears on the staff calendar, is not released while a payment is in progress, and is released automatically on expiry. | Part: the hold, its expiry and its automatic release, and the payment-in-progress state F08 enters. The length becomes a setting in S02; the countdown is S03's; the calendar is F10's |
| BKG-05 | Two bookings or holds can never overlap for the same professional. This is guaranteed by the database under simultaneous requests, not only checked by the application. | Full |
| BKG-06 | Booking states: held, pending payment, confirmed, arrived, in service, completed, cancelled, no-show. Completed is reached only through checkout; no-show can be set only after the start time and undone the same day; cancellation is only before the start time. Each change records who made it, when, and why. | Full for the machine and its staff actions. Pending payment and completed are reached only through in-process contracts for F08 and F10 |
| BKG-14 | Booking references are short, readable and unique within the salon. | Full |
| BKG-16 | Every booking records its channel: customer app, staff web, shared app link, Instagram, Google, WhatsApp, phone, front desk or walk-in. | Full for storage and the staff channels; the app channels are written by F15 |
| NFR-05 | Simultaneous attempts on one slot produce exactly one booking. | Proved by a concurrency integration test here; the load test is in the release suite |

## Scope

- **API (`booking`):** holds and bookings in one table, their segments and the exclusion constraint; references; the
  state machine and its history; the staff endpoints below; the recurring job that clears expired holds; the in-process
  contracts F08 and F10 will call.
- **API (`booking` implementing others' contracts):** `BookingsOnDate`, `UpcomingMemberBookings`,
  `MemberBookingsAffected`, `UpcomingServiceBookings` and `ServiceBookingCounts`. The "none" stand-ins in `staff` and
  `catalogue` are deleted.
- **API (`identity`):** the permissions `booking.manage` and `booking.attend` (decision 108).
- **Local seed:** each synthetic salon gets saved booking rules if it has none, so bookings can be made locally.

## Out of scope

- Finding free times, "No preference" assignment and the hold length setting (F07-S02). Here the client names the
  professional and the time, and a hold lasts 10 minutes.
- Any screen (F07-S03). The Booking rules screen is unchanged in this stage.
- Payment, deposits and the payment-in-progress flow itself (F08), rescheduling and the waitlist (F09), checkout,
  completing a booking, walk-ins and the calendar (F10), messages to the customer (F11), customer records (F12),
  customer self-booking (F15). No `BookingConfirmed` consumer exists yet; see Design notes.
- Showing affected bookings on new screens: `staff`'s existing screens and endpoints show what these contracts answer.

## Acceptance criteria

- AC-1: `POST /api/v1/bookings` with an `Idempotency-Key` places a hold for one professional, one service (and its
  variant when the service has variants), a local start date and time, the customer's name and mobile number and a
  channel. It answers 201 with the booking in state `held`, its reference, its start and end as offset date-times in the
  salon's zone, and `holdExpiresAt` 10 minutes after it was placed by the application's clock. The booking keeps the
  service terms version, the variant, a copy of the segments and price, and the current booking-rules version. Repeating
  the request with the same key returns the same booking and places nothing new; the same key with a different body is
  422 `request_rejected`. (BKG-04, BKG-16, CAT-04, decision 089)
- AC-2: The hold is refused, and nothing is stored, when:
  - the name (trimmed) is not 2 to 60 characters, or the mobile number is not a valid mobile of the configured region
    (decision 084): 400 field errors on `customer.name` and `customer.mobile`;
  - the channel is not one of `phone`, `whatsapp`, `front_desk`, `walk_in`, `instagram`, `google` or `staff_web`: 400 on
    `channel` (`customer_app` and `shared_app_link` are written only by F15);
  - the service needs a variant and none, or another service's, is named: 400 on `variantId`;
  - the professional, the service or the variant is another salon's, removed, deleted or unknown: 404 `not_found`, the
    same body for each;
  - the professional does not perform the service, the start is not on a 5-minute step, the start is in the past or more
    than one year ahead by the salon's date, or any part of the booking falls outside both the branch's opening hours
    (`OpeningSchedule`) and the professional's working windows (`TeamSchedule`, which already excludes breaks, leave and
    blocked time) on that date: 409 `slot_unavailable`, with a `detail` saying which;
  - the salon has never saved its booking rules: 409 `rules_not_set`.

  The customer notice and horizon of BKG-02 do not apply to staff (decision 108). (BKG-04, BKG-11, decision 108)
- AC-3: A hold or booking whose occupied time overlaps another live one of the same professional is refused with 409
  `slot_taken`, by the database's exclusion constraint, not only by a check. Bookings that only touch (one ends as the
  next starts) are both kept. Live means held (not yet expired), pending payment, confirmed, arrived, in service or
  completed. Cancelled and no-show bookings, released holds and expired holds occupy nothing; an expired hold never blocks
  a new one, even before the job has cleared it. Of N simultaneous holds for overlapping times of one professional,
  exactly one is 201 and the others 409 `slot_taken`, proved by a concurrency integration test that fires them at once
  through the endpoint, and holds for different professionals at the same time all succeed. (BKG-05, NFR-05)
- AC-4: The booking occupies its professional for its segments: R1 services have active and buffer segments, which both
  occupy the professional, so it occupies from its start to the end of its last segment. A processing segment (F17)
  occupies no professional: the data model and the constraint already handle a booking with one, proved by an integration
  test that inserts one through the domain. (decision 074)
- AC-5: `POST /api/v1/bookings/{id}/confirm` turns a hold that has not expired into `confirmed`. Confirming an expired
  hold is 409 `hold_expired`, and the time is free for others; confirming anything that is not a hold is 409
  `invalid_state`. `POST /api/v1/bookings/{id}/release` gives up a hold at once (204), freeing the time; releasing
  anything else is 409 `invalid_state`, and a released or expired hold is 404 afterwards. (BKG-04, BKG-06)
- AC-6: A recurring job, every minute and at most once per occurrence across instances, removes holds whose expiry has
  passed, salon by salon, and never one in `pending_payment`. Until it runs, an expired hold is already invisible to every
  read and blocks nothing (AC-3). (BKG-04)
- AC-7: State changes, each `POST /api/v1/bookings/{id}/<action>`:
  - `arrive`: `confirmed` to `arrived`, only on the booking's local date;
  - `start`: `arrived` to `in_service`;
  - `cancel` with a required `reason` (`customer_asked`, `salon_unable`, `booked_by_mistake`, `other`) and an optional
    `note` of up to 200 characters: `confirmed` to `cancelled`, only before the start time;
  - `no-show`: `confirmed` to `no_show`, only at or after the start time;
  - `undo-no-show`: `no_show` to `confirmed`, only on the booking's local date; when the time has since been booked by
    someone else it is 409 `slot_taken` and the booking stays `no_show`.

  Every other change, and a permitted change at the wrong time, is 409 `invalid_state` with a `detail` naming the rule,
  and changes nothing. No endpoint sets `pending_payment` or `completed`. Of two simultaneous changes to one booking, one
  applies and the other is judged against the result. (BKG-06, decision 075)
- AC-8: `booking` provides two in-process contracts, unused until F08 and F10, each proved by integration tests through
  the domain: one moves a hold to `pending_payment` and back to `confirmed` or `held`, and a hold in `pending_payment` is
  never expired by the job (BKG-04); one completes an `arrived` or `in_service` booking, the only way to `completed`
  (BKG-06). Confirming, by either route, writes a `BookingConfirmed` event to the outbox in the same transaction. (CR-021)
- AC-9: Every change of state, including placing, confirming, releasing and expiring a hold, writes one history entry on
  the booking (from, to, when by the application's clock, who as the staff account or "system", and the reason and note
  for a cancellation) and an entry in the salon's audit trail with actor, time, before and after. A customer's mobile
  number never appears in a log line. (BKG-06)
- AC-10: Each booking has a reference of six characters from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`, returned and searchable
  as `K7M-4QX` (the hyphen optional and case ignored in search), unique within its salon by a constraint, generated
  randomly and retried on a collision. Two salons may hold the same reference. (BKG-14, decision 108)
- AC-11: Reads, with `calendar.read`:
  - `GET /api/v1/bookings` lists bookings by start time, paginated, for a local date range of at most 31 days
    (`from`, `to`), filtered by `state` (repeatable), `memberId` and `q` (name, mobile in any common form, or reference);
    expired holds and released holds are never listed;
  - `GET /api/v1/bookings/{id}` returns one booking with its history, `allowedActions` for the caller right now, and
    `holdExpiresAt` while held.

  The mobile number is returned in the national format. A Professional whose data scope is `own` sees only the bookings of
  the team member linked to their account (none when unlinked), and another's is 404; with the IAM-08 grant they see the
  salon's. (BKG-06, decision 108)
- AC-12: Roles (decision 108):
  - `booking.manage` (Owner, Manager, Receptionist) places, confirms, releases, cancels, marks no-show and undoes it;
  - `booking.attend` (Owner, Manager, Receptionist, Professional) marks arrived and starts service; a Professional only on
    their own bookings, another's being 404;
  - an Accountant gets 403 `permission_denied` from every endpoint here;
  - a support session reads bookings and gets 403 `support_read_only` from every change;
  - suspended and read-only salons answer as the catalogue endpoints do.
- AC-13: The contracts other modules declared answer from real bookings, and their stand-ins are gone:
  - deleting a service with an upcoming live booking is refused as the catalogue already words it, and one whose only
    bookings are cancelled, no-show or past succeeds; removing a team member, or taking a service from them, with an
    upcoming live booking is refused likewise;
  - of a service delete (or a member removal) and a hold for it sent at once, never both succeed, proved by a
    concurrency integration test for each (the window the Javadoc asks F07 to close);
  - `BookingsOnDate`, `MemberBookingsAffected` (all three questions) and `ServiceBookingCounts` answer as their Javadoc
    says, proved through `staff`'s and `catalogue`'s existing endpoints: a closure or an hours change lists the bookings
    it affects, leave and blocked time count them, and "Most booked" ranks by real bookings. Holds and pending payments
    count as upcoming for the guards; cancelled and no-show bookings count for nothing. (STF-01, STF-03, STF-04, CAT-01,
    CAT-06)
- AC-14: Tenant isolation: no endpoint, contract, job or reference search ever reads or changes another salon's booking,
  professional, service or rules; another salon's id answers as an unknown one; with no tenant context the new tables
  return no rows. The exclusion constraint and the reference constraint are per salon. (BKG-05, BKG-14)
- AC-15: Every new endpoint's responses, including each documented error status, validate against the committed OpenAPI
  document, and the new problem codes (`slot_taken`, `slot_unavailable`, `hold_expired`, `rules_not_set`) are in
  `ErrorCode` and the contract.

## Design notes

- **One table, one machine.** A hold is a booking in state `held` (`docs/engineering/backend.md`, "Booking integrity").
  States are `held`, `pending_payment`, `confirmed`, `arrived`, `in_service`, `completed`, `cancelled`, `no_show`. All
  changes go through one state machine in `booking.domain`, unit tested with a fixed clock for every pair of states and
  every boundary (the start minute, local midnight, the expiry second).
- **The guarantee.** Store the time a booking occupies as ranges (`tstzrange`) on the booking or on segment rows, and put
  an exclusion constraint (`btree_gist`) on salon, professional and range, limited to live rows. "Live" must exclude an
  expired hold without the job: either the placing transaction first removes that professional's expired holds that
  overlap, or the constraint's predicate is arranged so they cannot collide. Translate the violation, and only it, to
  `slot_taken`. The pre-insert check exists only for the clearer `slot_unavailable` and `detail` messages.
- **What a hold reads.** `OpeningSchedule` and `TeamSchedule` open their own transactions; call them before the placing
  transaction, as their Javadoc requires. `TeamMembers.performing` decides who performs a service.
- **Terms.** Store the `ServiceTerms` version id, the variant id, the copied segments and price, and the
  `BookingPolicies.current()` version id. Nothing later rewrites them (CAT-04, decision 089).
- **Customer.** The name and the mobile in E.164 live on the booking. F12 adds a link to a customer record.
- **Closing the guard windows.** `UpcomingMemberBookings` and `UpcomingServiceBookings` are asked before `staff`'s and
  `catalogue`'s transactions open. Close the window with a transaction-scoped advisory lock keyed by salon and member, or
  salon and service, taken by the placing transaction and by the removing one before it asks again inside its
  transaction; the lock helper belongs in `shared` (no business rule), as `platform`'s stores already use
  `pg_advisory_xact_lock`. Record the mechanism in the handoff for the docs.
- **Branch.** Every new table carries `branch_id`, the default branch (decision 073), checked through `SalonBranches`.
- **Outbox.** `BookingConfirmed` carries ids only. Until F11 nothing consumes it; it must not be dead-lettered for having no
  consumer (follow "Events between modules").
- **Permissions** are new rows in `identity`'s permission table and `docs/architecture/identity-and-access.md` (the lead
  updates the document at close from the handoff).
- **Paths.** `booking` serves its staff endpoints under its own top-level segment, `/api/v1/bookings`; the booking rules
  stay at `/api/v1/salon/booking-rules`. Update the ownership map.

## API and data changes

Routes are on the salon's workspace host. Local dates are `YYYY-MM-DD`, local times `HH:mm`, instants offset date-times
in the salon's zone.

| Method/path | Permission | Request / result |
| --- | --- | --- |
| `POST /api/v1/bookings` | `booking.manage` | `Idempotency-Key`; `{memberId, serviceId, variantId?, date, time, customer: {name, mobile}, channel}` -> 201 booking |
| `POST /api/v1/bookings/{id}/confirm` | `booking.manage` | -> 200 booking |
| `POST /api/v1/bookings/{id}/release` | `booking.manage` | -> 204 |
| `POST /api/v1/bookings/{id}/arrive` | `booking.attend` | -> 200 booking |
| `POST /api/v1/bookings/{id}/start` | `booking.attend` | -> 200 booking |
| `POST /api/v1/bookings/{id}/cancel` | `booking.manage` | `{reason, note?}` -> 200 booking |
| `POST /api/v1/bookings/{id}/no-show` | `booking.manage` | -> 200 booking |
| `POST /api/v1/bookings/{id}/undo-no-show` | `booking.manage` | -> 200 booking |
| `GET /api/v1/bookings?from=&to=&state=&memberId=&q=&page=&size=` | `calendar.read` | page of bookings |
| `GET /api/v1/bookings/{id}` | `calendar.read` | booking with `history` and `allowedActions` |

A booking is `{id, reference, state, startsAt, endsAt, holdExpiresAt, member: {id, name}, service: {id, name, variantName},
customer: {name, mobile}, channel, priceMinor, currency, createdAt}`; `holdExpiresAt` is null unless held. A history entry is
`{at, from, to, by: {kind, label}, reason, note}`. `allowedActions` lists the action names above that the caller may take
now. Field names may follow the API conventions; the handoff records the final shapes.

Tables: the bookings (with state, times, occupied ranges, references and the copied terms), their history, and whatever
the guarantee needs, each with the full "Adding a table" checklist: `tenant_id`, `branch_id`, row-level security, grants,
the ownership map, the erasure function, the database isolation test's lists and `MigrationIntegrationTest`'s list.

## UI and UX

Not applicable for this stage. S03 builds the screens; the existing Services, Team and Settings screens show the guards and
lists AC-13 brings to life, with no change.

## Risks and test focus

- `concurrency`: the core risk. Simultaneous holds on one time; touching versus overlapping; an expired hold under a new
  one; two state changes at once; undo-no-show against a new booking; delete or removal racing a hold. Deterministic tests,
  each asserting exactly one winner.
- `data-integrity`: the state machine's every pair and time boundary in the salon's zone, including a day the clocks change
  in a zone that has one; terms copied, never re-read from current; history and audit for every change, including the job's.
- `tenant-isolation`: every table, endpoint, contract, the job and reference search with two salons holding the same
  reference.
- `permissions`: each role, own versus granted scope for a Professional, an unlinked Professional, a support session.
- `api` (contract): every response and error status; strict bodies; idempotency replay and key reuse with another body.
- `boundary`: names of 1, 2, 60 and 61 characters; landline and foreign numbers; now, a year ahead and a day beyond; a
  booking ending exactly at closing time or a break.

## Verification commands

```bash
cd apps/api && ./mvnw -q verify
pnpm -r lint
pnpm -r typecheck
pnpm -r test
pnpm --filter @salon/web build
pnpm contracts:check
```

## Done criteria

- The developer has checked every acceptance criterion on the running product with two synthetic salons, each role and a
  support session, and recorded it in the handoff.
- The verification commands are green.
- The handoff records the final endpoint shapes, the overlap mechanism and the guard-window mechanism, for the lead to
  write into `docs/engineering/backend.md` and `docs/architecture/identity-and-access.md` at close.
