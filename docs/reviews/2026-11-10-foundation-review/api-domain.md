# Domain review: F04-F07 and forward readiness (apps/api)

Date: 2026-11-10. Read-only review; nothing in the repository was changed, built or run.

Path prefixes used below:

- `J/` = `apps/api/src/main/java/com/salonplatform/`
- `M/` = `apps/api/src/main/resources/db/migration/`
- `F/` = `features/`
- `D/` = `docs/`

Confidence: **V** = verified by reading the code or migration; **S** = suspected (reasoned, not executed).

Context that lowers the cost of every fix below: nothing is released and there is no production data
(CR-023, "Nothing is released, so there is no compatibility constraint"). Schema changes are code cost only today;
each becomes a data migration once salons hold bookings.

## Verdict

The booking core is sound where it matters most: the double-booking guarantee is a real database exclusion
constraint, the state machine has one mutation site, terms and prices are frozen on the booking, module boundaries
are clean, and the tests hit real PostgreSQL with concurrency cases. I found no race that produces a double booking.

The risks are in the shape of the model, not its correctness: a booking is exactly one service with one professional
for one free-text customer, immutable after placing, with one event and no read contract. F08-F12 each need something
that shape does not have. Three workflow gaps (state dead-ends, local-mobile-only customers, no booking outside opening
hours) will be felt by real front desks.

## Findings, ranked

### H1. A booking is one service; the adopted design and APP-02 are multi-service (high, V schema / S prototype reading)

- Evidence: `M/V32__create_booking_appointments.sql:28-45` (one `member_id`, one `service_id`, one `service_terms_id`,
  one segment list, one `price_minor` per row); `:52,:63` one idempotency key per row.
  `F/F07-online-booking/stages/S02-availability-and-assignment/spec.md:49` puts "Several services in one booking" out of
  scope and no feature owns it. The prototype (design reference, decision 070) models an appointment as `a.items`
  (a list of service lines, `a.gap=gapOf(a.items)`) and the customer selection as a list `S.sel` summed with
  `items.reduce` (`D/design/prototype/index.html`, read as source, not run).
- Requirements that need it: APP-02 (R1, F15: "services, professional, time ... persistent summary of price and
  duration"), PAY-06 (F10: "add or remove services"), CAT-07 add-ons, CAT-10 packages, BKG-19, BKG-22, OCC-08.
- Failure today: cut + colour for one customer is two unrelated holds, two references, two confirmations, two
  `BookingConfirmed` events, no atomicity (the second hold can lose after the first won) and availability cannot
  search for back-to-back time.
- Smallest fix: decide now that `booking_appointment` is the **line** (one professional, one service, one time) and add
  a parent visit: nullable `visit_id` plus a small `booking_visit` row that owns the reference, the customer, the
  channel and the idempotency key; place N lines in one transaction (the exclusion constraint already works per row).
  Do it before F08, because the payment record (decision 076, "one payment record per booking or sale") must hang off
  the visit, not a line.

### H2. State machine dead-ends (high for F08/F10, V)

Evidence: `J/booking/domain/BookingStateMachine.java`.

- (a) `:71-78` ARRIVE only on the booking's local date, `:115-118` COMPLETE only from arrived/in_service. A confirmed
  booking nobody tapped "Arrived" on before midnight can only ever become `no_show` (`:91-98`). The visit cannot be
  checked out, its money cannot be attached, and the customer gets a false no-show (CRM-01 counts, BKG-20 risk rules,
  the no-show retention of BKG-09). The date rule is the spec's addition (F07-S01 AC-7), not BKG-06's.
- (b) No exit from `arrived`/`in_service` except COMPLETE. A wrong customer marked arrived, or one who leaves before
  the service, stays live forever (and keeps occupying the time: `arrived` is in the constraint's predicate,
  `M/V32:68`). There is no undo-arrive.
- (c) `pending_payment` has no staff exit and no timeout: CANCEL is from `confirmed` only (`:83-90`), RELEASE from
  `held` only (`:63-66`), the expiry job skips it by design. A payment link (PAY-09) the customer never pays blocks the
  time until F08 calls `abandon`. Nothing in the booking module forces that.
- (d) `abandon` returns to `held` "with its expiry as it was" (`J/booking/inprocess/BookingPayments.java:27`) and
  `hold_expires_at` is `updatable = false` (`J/booking/entity/Booking.java:115-116`), so a payment failing after the
  original expiry frees the time at once. PAY-04 says "failure (slot stays held)".
- Smallest fix: add `UNDO_ARRIVE` (arrived -> confirmed, same day), allow ARRIVE/COMPLETE for a past-date confirmed
  booking with a reason (manager permission), let CANCEL/RELEASE act on `pending_payment` for staff, and make the hold
  expiry extendable on abandon. All are additions to one enum, one switch and one column mapping.

### H3. Only local-region mobile numbers can be booked, and a number is mandatory (high, V)

- Evidence: `J/booking/domain/CustomerMobile.java:32-37` (`isValidNumberForRegion`, MOBILE only);
  `J/booking/service/impl/HoldRequests.java:85-88`; `M/V32:49` `customer_mobile NOT NULL`.
- Failure: a tourist, an expatriate or an overseas bride with a +44/+61/+971 number cannot be booked at all (400 on
  `customer.mobile`); neither can a landline-only customer or a walk-in who gives no number. BKG-11 only says "a mobile
  number is required"; F07-S01 AC-2 narrowed it to the configured region. Bridal (F19) makes foreign numbers common.
- Smallest fix: accept any valid E.164 mobile when typed with a country code, keep the region as the default for
  national input; decide for F10 whether a walk-in may have no number (nullable column).

### M1. No tax version on the booking, though the catalogue contract says there is (medium, V)

- Evidence: `J/catalogue/TaxSettings.java:10-13` and `M/V27__create_catalogue_tax_and_products.sql:11` ("A booking or a
  sale (F07, F08, F10) keeps the id of the version it was made under"); `M/V32` has no such column and no F07 spec
  mentions tax. `booking` never calls `TaxSettings`.
- Cost: F08 receipts (PAY-07 "show tax and service charge lines") and F10 checkout must choose a version for bookings
  made before a VAT change with nothing recorded. Decide: tax at time of payment (then fix the Javadoc and the
  migration comment) or add nullable `tax_version_id` to the booking/visit now.

### M2. Money has no type; deposit arithmetic does not exist (medium, V)

- Evidence: `D/engineering/backend.md:35` says `shared` holds money; `J/shared/` has no money package. Money is a bare
  `long` plus a `String` everywhere. `MinorUnits` is duplicated (`J/booking/domain/MinorUnits.java`,
  `J/catalogue/domain/MinorUnits.java`; the stated reason, "booking will depend on catalogue", has expired).
  `BookingPolicies.depositFor` (`J/booking/service/impl/BookingPoliciesImpl.java:54-57`) only picks a rule; nothing
  computes an amount, rounding or cap. Rules amounts are "read in the rules' currency" while the price is in the terms
  version's currency, with no equality check.
- `vat_rate_percent integer` (`M/V27:21,:39`) and the service charge are whole percentages; a 2.5% levy or a 17.5% rate
  needs a migration of an immutable-version table.
- Smallest fix before F08: a `shared.money.Money(minor, currency)` with checked add/percentage, one deposit calculator
  in `booking.domain` with a unit-test table, and rates in basis points.

### M3. Moving a service between categories silently changes who performs it and bypasses the booking guard (medium, V)

- Evidence: `J/catalogue/service/impl/SalonServicesImpl.java:152-183` changes the category (`:174`) with no question to
  anyone; `J/staff/domain/PerformedServices.java:68-75` derives a member's services from whole categories at read time;
  the guard `refuseLosingBookedServices` runs only on a team save (`J/staff/service/impl/TeamMemberServiceImpl.java`
  ~`:191,:207-208`).
- Failure: Nimal performs category Colour whole and has a confirmed Balayage next week. A manager moves Balayage to
  Treatments. He no longer "performs" it: availability stops offering him, undo-no-show refuses
  (`J/booking/service/impl/BookingChangeServiceImpl.java:153-156`), nothing is flagged. The reverse is worse commercially:
  moving a bridal service into a whole category makes every junior eligible for "No preference".
- Smallest fix: `catalogue` asks a contract `staff`/`booking` implement (the decision 087 pattern) before a category
  change, or the service sheet shows who gains and loses the service and the booking attention list (BKG-13) flags it.

### M4. Mixed transaction propagation on provided interfaces; a pool-starvation trap for F08 (medium, V code / S failure)

- Evidence: joining the caller (default REQUIRED): `J/staff/service/impl/TeamMembersImpl.java:42-43`,
  `J/catalogue/service/impl/CatalogueListingsImpl.java:29-30`, `J/staff/service/impl/OpeningScheduleImpl.java:39-40`,
  `J/staff/service/impl/TeamScheduleImpl.java:58-60`. New connection (REQUIRES_NEW):
  `J/catalogue/service/impl/ServiceTermsImpl.java:30-32`, `TaxSettingsImpl.java:32-34`,
  `J/booking/service/impl/BookingPoliciesImpl.java:32-34`, `BookingReads.java:30-32`. Every Javadoc says "opens its own
  ... does not join".
- Consequences: (1) inside `placeHold` (`J/booking/service/impl/HoldServiceImpl.java:277-283`) staff's `Member` and
  catalogue's entities are loaded in booking's persistence context and transaction, which is the coupling CR-021
  forbids and changes behaviour on extraction; `TeamSchedule`'s REPEATABLE READ snapshot is silently lost if it is
  ever called inside a transaction. (2) F08's confirming transaction will naturally call `BookingPolicies.version`,
  `ServiceTerms.version` and `TaxSettings.version` to work out a deposit; each takes a second connection from a pool of
  10 with a 2 s wait (`apps/api/src/main/resources/application.yaml:22,:28`).
  Ten concurrent gateway notifications each holding one connection and waiting for another fail together.
- Smallest fix: one rule, one helper (a shared `ReadOnlyWork` that is REQUIRES_NEW and refuses to run inside a writable
  transaction unless the contract is `inprocess`), and an architecture test. F08 must read versions before opening its
  transaction.

### M5. One event, ids only, and no way to read a booking from another module (medium, V)

- Evidence: `J/booking/service/impl/BookingChanges.java:74-77` publishes only `BookingConfirmed(bookingId, memberId,
  serviceId)` (`J/booking/BookingConfirmed.java:17`). The module root offers `Availability`, `BookingPolicies` and
  value records only; there is no `Bookings` read contract.
- Cost: F08 (price, customer, deposit), F10 (calendar, checkout), F11 (NOT-01: cancelled, rescheduled, reminder),
  F12 (visits, no-shows), F13 (read models "fed through events", `D/engineering/backend.md:68-69`) each need it.
  Read models built later have no events for bookings that already exist.
- Smallest fix: add a provided `Bookings.byId/byIds` returning a record, and publish `BookingPlaced`, `Cancelled`,
  `NoShow`, `Arrived`, `Completed` (and later `Rescheduled`) from the single mutation site now, each carrying
  `startsAt`, `branchId`, `channel` and price, so consumers need no call back.

### M6. The booking row is write-once; reschedule, customer actors and reassignment need the entity reopened (medium, V)

- Evidence: `J/booking/entity/Booking.java:58-116` every column except `state`/`updated_at` is `updatable = false`
  (member, times, `occupied`, price, `hold_expires_at`). `M/V32:138-142` the history accepts only actor kinds
  `staff`/`system`, reasons from the four cancellation reasons, and a note only with a reason; it records state changes
  only. `:54` `created_by NOT NULL` is a staff account.
- Cost: F09 reschedule ("any payment carries over", so the same row must move), the reschedule limit of the rules
  (`J/booking/Rescheduling.java`, needs a counter), BKG-12 drag to another professional, a customer cancelling in F15,
  a no-show "why". Removing a member is refused while they have bookings
  (`TeamMemberServiceImpl.java:239-240,:278-283`) and there is no reassign, so until F09 a departed stylist's bookings
  must each be cancelled.
- Smallest fix: an entity method `moveTo(member, startsAt)` that recomputes `occupied` (an UPDATE is atomic under the
  exclusion constraint), a `booking_change` history that is not state-only, actor kind `customer`, nullable
  `created_by`.

### M7. Branch scope is real in the schema, absent in the code paths (medium, V)

- What is true: every F04-F07 table has `branch_id NOT NULL` and per-branch uniqueness (for example
  `M/V21:34,:129`, `M/V23` `booking_rules_version_number`, `M/V25:107`). It is not "just a nullable column".
- What is missing: (1) every use case resolves `SalonBranches.defaultBranch()` itself (9 call sites:
  `BookingCallers.java:87`, `BookingScope.java:37,:43`, `AvailabilityImpl.java:45,:65`, `CatalogueScope`, `StaffScope`),
  and the provided contracts have no branch parameter (`BookingPolicies.current()`, `TaxSettings.current()`,
  `CatalogueListings.current()`, `TeamMembers.performing(serviceId)`, `Availability.Query`). (2) Booking reads do not
  filter by branch (`J/booking/repository/BookingRepository.java:82-91` list, `:109-110` occupying). (3) Nothing in the
  database checks that a `branch_id` is one of the salon's branches (no cross-module key by rule, and no trigger).
  (4) A team member has exactly one branch (`M/V28:33-36`), a weekly pattern is unique per member
  (`M/V30:45`), and tax settings are per branch although CAT-05 says per tenant.
- Verdict for F18: tables fit; every Scope class, five contracts and every endpoint gain a branch. Also
  `defaultBranch()` is its own transaction and is resolved about five times per availability request.

### M8. Availability cost grows with team size and has no range form (medium, V counts / S latency)

- Evidence: `J/booking/service/impl/AvailabilityInputs.java:125-126` one `TeamSchedule.on` per eligible member, each its
  own transaction of 5-7 statements (`TeamScheduleImpl.java:74-90`); `Availability.freeTimes` takes one date
  (`J/booking/Availability.java:86,:117`). Roughly 12+N transactions and 25+7N statements per call (about 165
  statements for 20 professionals).
- Cost: CR-027 SUG-F07-03 ("offer the next day that has one") and F15's date strip need "which of the next 30 days
  have time": 30 calls, about 5,000 statements. AC-11 measured one day on a developer machine only.
- Smallest fix: `TeamSchedule.between(Collection<memberId>, from, to)` (the batch read already exists internally,
  `J/staff/service/impl/ScheduleRows.java:51-79`), one `occupying` read for the range, and a
  `Availability.firstFreeDay`/range method over the same pure `FreeTimes`.

### M9. Bookings cannot exist outside opening hours, and opening hours are one window a day (medium, V)

- Evidence: `J/booking/service/impl/HoldTimes.java:60-66` and `J/booking/domain/FreeTimes.java:131-133,:153` refuse any
  booking not wholly inside the branch's opening window, for staff too; opening hours are one `opens`/`closes` pair per
  weekday (`M/V21`), special hours exist only for published holidays, a closure is a whole day. There is no staff
  override of any refusal.
- Failure: a 5 am bridal dressing before a 9 am opening (routine in Sri Lanka) cannot be recorded without rewriting
  the salon's weekly hours; a salon closing at 1 pm for a private function must close the whole day or block each
  person. F19 off-site events (BKG-23) hit the same wall.
- Smallest fix: a per-date special-hours row (the holiday `special` choice generalised) and, for staff with a
  permission, a hold flag that skips the opening-hours check (never the exclusion constraint).

### M10. History that reports will need is being overwritten (medium for F13, V)

- Evidence: a weekly schedule save deletes and rewrites the pattern (`ScheduleRows.java:100-101`), with no effective
  dates; there is no employment end date (only removal, refused while bookings exist).
- Cost: occupancy by professional (REP-05), commission (STF-08) and "was she working that day" cannot be computed for
  past dates once a pattern changes. Fix going forward only: a daily snapshot row per member written by a job from F13,
  or `valid_from` on the schedule.

### Low

| # | Finding | Evidence | Fix |
| --- | --- | --- | --- |
| L1 | Ended holds are never purged: each slot tap is a permanent booking row, two history rows and two audit rows; the application role cannot delete | `M/V32:105`, `D/engineering/backend.md:424-426` | Purge job for `released`/`expired` older than N days, before F15 |
| L2 | Unbounded entity loads to "far future" | `J/booking/service/impl/BookingsOnDateImpl.java:54`, `MemberBookingsAffectedImpl.java:45` | Bound to the one-year booking limit or project to columns |
| L3 | The service guard lock is exclusive, so all hold placements for one service across professionals run one at a time; the listing (up to 350 entities) is loaded twice under it | `HoldServiceImpl.java:271-283`, `J/shared/persistence/TransactionLocks.java:43` | `pg_advisory_xact_lock_shared` for placements |
| L4 | A booking cannot be found by reference or mobile without a date window of 31 days | `BookingReadServiceImpl.java:95`, `BookingRepository.java:82-91` | Drop the window when `q` is a reference or a mobile |
| L5 | Confirm is not idempotent: a retried confirm answers 409 `invalid_state` | `BookingStateMachine.java:143-148` | Answer the booking when already confirmed by the same action |
| L6 | Terms and rules are read before the lock and not re-read, so a hold placed milliseconds after a price change keeps the old version | `HoldServiceImpl.java:208` vs `:269-283` | Compare `current_terms_id` under the lock |
| L7 | A hold at a local time the clocks skip is silently moved forward, while availability refuses to offer it | `HoldTimes.java:47`, `FreeTimes.java:127` | Same round-trip check in `startOf` (no effect in LK) |
| L8 | Parsing helpers copied three times | `HoldRequests.java:41-47`, `AvailabilityServiceImpl.java:33-37`, `BookingReadServiceImpl.java:60-61` | One package-private parser |
| L9 | The hold-expiry job opens a transaction and logs an INFO line for every active salon every minute, sequentially | `J/shared/jobs/JobScheduler.java:221-246,:261-280` | A `SECURITY DEFINER` "salons with run-out holds" query, as `outbox_due` does |
| L10 | Leave, blocked time, hours and closures race a simultaneous hold (check before the transaction, no lock); the result is an unflagged-at-save booking, later flagged on read | `HoldServiceImpl.java:216`, `J/staff/service/impl/MemberTimeOffServiceImpl.java:83-108` | Accept, or take the member guard lock in both |

## What I checked and found sound

- **Double booking.** `M/V32:64-68` `EXCLUDE USING gist (tenant_id, member_id, occupied)` over live states, half-open
  multiranges; only SQLSTATE 23P01 on that constraint becomes `slot_taken`
  (`J/booking/service/impl/BookingConstraints.java:28-31`). Run-out holds are ended under row locks before the insert
  (`BookingChanges.java:86-91`, `BookingRepository.java:55-66`).
- **Races traced by hand, no defect:** two holds one time; confirm vs expiry job (row lock, `SKIP LOCKED` in the job,
  clock read after the lock); confirm vs a placement ending the run-out hold; undo-no-show vs a new booking; same
  idempotency key twice, including "No preference" with different candidates; service delete / member removal /
  service removal vs hold (advisory locks in one order, re-check inside). No lock-order deadlock found.
- **Idempotency on create:** unique key per salon plus a request fingerprint, 422 on a different body
  (`HoldServiceImpl.java:179-193`).
- **State machine:** pure, one decision function, one mutation site (`BookingChanges.java:63-79`), every change in
  history and audit with actor and time; history rows immutable by trigger.
- **Segments:** stored on terms, variants and bookings; `Occupation` leaves processing out of the occupied range and
  both availability and the constraint use it (`J/booking/domain/Occupation.java:44-54`, `FreeTimes.java:268-270`).
- **Price history:** terms versions and variants are immutable rows with an update-refusing trigger; the booking copies
  price, currency, segments and names; nothing rewrites them (CAT-04 holds).
- **Money representation:** bigint minor units plus ISO currency on every money row; tax-inclusive split with half-even
  rounding whose parts always sum to the price (`J/catalogue/domain/TaxParts.java:23-48`).
- **Time:** instants stored, local dates through one `SalonTime`; availability handles skipped and repeated hours.
- **Schedules:** weekly windows, breaks, date overrides, leave, blocked time compose correctly at every edge I traced
  (`J/staff/domain/ScheduleRules.java:32-94`); opening rules precedence is clear (`OpeningRules.java:31-54`).
- **Module boundaries:** no module reads another's tables; booking uses only root-package contracts of staff and
  catalogue; dependencies point one way.
- **Tests:** integration tests use a real PostgreSQL container
  (`apps/api/src/test/java/com/salonplatform/testing/TestPostgres.java`);
  `BookingConcurrencyIntegrationTest` (9 races), `NoPreferenceHoldsIntegrationTest`, `BookingOverlapIntegrationTest`
  (constraint alone), `FreeTimesTest` (25 unit cases including both clock changes). Not covered: expiry job vs confirm
  race, late settle after abandon, leave vs hold race.
- **Class size and dead code:** largest class 483 lines; no TODOs; the unused payment/checkout seams are tested.

## Forward readiness

| Feature | Verdict | What is there / what must change |
| --- | --- | --- |
| F08 payments, deposits, refunds | **Additive, plus small rework** | `pending_payment` and the `BookingPayments` seam exist. Missing: any payment table or sale concept (module empty), deposit calculation (M2), payment-option rules for PAY-01 (rules have deposit none/percentage/amount only), tax version (M1), a timeout and staff exit for `pending_payment`, hold extension on failure (H2 c, d), staff CONFIRM that bypasses a required deposit, a booking read contract (M5), propagation rule (M4) |
| F09 reschedule, cancel, waitlist | **Rework of the Booking entity and history** | M6. Policy data exists (`BookingTerms`), no calculation. Waitlist is a new table |
| F10 calendar, walk-ins, checkout, cash drawer | **Additive in `payment`; rework if multi-service** | No order/sale/invoice exists; `BookingCheckout.complete` is the only seam, so checkout is planned as a separate sale that completes a booking. H1 (add services at checkout), H2 a/b (late arrival, undo), walk-in without a number (H3), BKG-13 "until staff resolve" needs a stored acknowledgement (flags are computed on read, decision 104 c) |
| F11 notifications | **Additive** | Only `BookingConfirmed`; needs more events and a read contract (M5). WhatsApp opt-in and preferences need a customer record, which is F12, while F11 is ordered before it |
| F12 customer records | **Additive with a backfill** | No customer entity beyond the sign-in identity `identity_customer` (`M/V9`, unique mobile per salon). Bookings hold free-text name and mobile, no `customer_id`. CRM-02 allows two people on one number, so matching by mobile is ambiguous; decide the rule and add nullable `customer_id` before bookings accumulate. Customer identity is per salon by decision 072 / IAM-03, so the shared app needs no global identity |
| F13 reports | **Additive** | Counts by state, channel, service, professional and the `assigned` flag are derivable. Revenue needs F08/F10. Utilisation history is not recoverable (M10). Reporting may not read booking's tables, so it needs M5 |
| F14 import | **Additive, S** | Imported future appointments must pass the exclusion constraint and the opening-hours rule; an import path that skips the pre-checks but not the constraint is needed |
| F15 customer app | **Rework (H1)** | Customer audience exists in `FreeTimes`. Hold placement is staff-only (permission, `created_by`, channel filter, 5-minute rule without notice/horizon). "Shown online" is not applied to "No preference" |
| F17 options, add-ons, packages, resources | **Additive** | Processing segments already honoured. Resources need per-unit rows and their own exclusion table; add-ons change segments and price per booking; price levels fit (`Prepared` terms are built per candidate); CAT-14 final price belongs on the sale |
| F18 multi-branch | **Additive schema, wide code change** | M7 |
| F19 bridal | **Rework without H1's visit; additive with it** | Needs several professionals and services per occasion, travel segments (`M/V32:77` CHECK lists three kinds), bookings outside opening hours (M9), non-catalogue lines (`service_id NOT NULL`), foreign numbers (H3). Quotes and staged payments are greenfield on the F08 ledger |
| F21 offers, loyalty, gift cards | **Additive on F08/F10** | Booking price is fixed at placing; time-dependent pricing (MKT-05) needs a price per offered time in `Availability.FreeTime` |

### Decisions that get expensive to reverse

1. One service per booking row, with reference, customer, idempotency key and price on that row (H1).
2. Customer as free text on the booking (F12 row above).
3. Write-once booking with a state-only history (M6).
4. Whole-percent tax rates in immutable version rows (M2).
5. In-place weekly schedules (M10).
6. Events limited to confirmation (M5): every booking made before more events exist is invisible to read models.

## Requirement conformance (F04-F07)

Implemented as claimed, verified in code: BKG-05/NFR-05, BKG-14, BKG-16, BKG-03, BKG-01 (staff), BKG-04 (hold,
expiry, payment-in-progress state), CAT-01 delete guard, CAT-03, CAT-04, CAT-06, STF-01 removal guard, STF-02, STF-03,
STF-05, STF-04 (affected bookings listed), BKG-02/BKG-09 as stored rules.

Silent narrowing or gaps:

| Requirement | Gap | Evidence |
| --- | --- | --- |
| BKG-11 "a mobile number is required" | Narrowed to a mobile of the configured region (H3) | F07-S01 AC-2; `CustomerMobile.java:32` |
| BKG-06 "who, when, and why" | "Why" can be recorded only for a cancellation; no-show and its undo take none | `M/V32:140-142` |
| BKG-06 arrival | "Only on the booking's date" is added by the spec and creates the dead-end in H2 a | F07-S01 AC-7 |
| CAT-05 / F05-S03 | The tax contract says bookings keep a tax version; they do not (M1) | `TaxSettings.java:10-13` |
| STF-01 / F06-S01 AC-8 | "A save may not stop someone performing a service they are booked for" holds for team saves, not for a category move (M3) | `SalonServicesImpl.java:174` |
| CAT-05 "per tenant" | Tax settings are per branch | `M/V27:18` |
| BKG-09 / BKG-02 | Stored and versioned; notice and horizon are enforced only in the customer audience of the calculation, and nothing yet consumes cancellation, no-show or reschedule rules (owned by F09, stated) | `FreeTimes.java:86-104` |
| NFR-03 | One-day availability measured on a developer machine only (stated in the spec) | F07-S02 AC-11 |

## Not verified

- No build, test or query plan was run; latency and pool behaviour in M4 and M8 are reasoned from code.
- The prototype was read as source text, not exercised; the multi-service reading rests on `a.items` and `S.sel`.
- F04-F06 specs were spot-checked by acceptance-criteria headings against code, not read line by line; controllers,
  DTOs and mappers of the three modules were not read; the web app is out of scope.
- The BRD PDF, CR-020, CR-021 and ARCHITECTURE.md were not read in full; CR-023, the decision log 071-112,
  backend.md and the F07 specs were.
- Whether a salon's time zone or currency can be changed after bookings exist (operator side, the other reviewer's
  area).
