---
id: CR-028
title: Requirement corrections from the foundation review
status: approved
requested_by: owner
requested_on: 2026-11-10
decided_on: 2026-11-10
requirements: [BKG-05, BKG-06, BKG-10, BKG-11, BKG-16, BKG-17, BKG-21, BKG-24, BKG-25, BKG-26, BKG-27, NOT-03, NOT-08, PAY-06, PAY-09, PAY-13, PAY-16, PAY-17, PAY-18, PLT-08, PLT-16, REP-01, REP-06, REP-08, STF-08, APP-06, MKT-01, BRD-03, BRD-12, IAM-01, IAM-02, WEB-01, WEB-02, WEB-03]
features: [F07, F08, F09, F10, F11, F12, F13, F14, F15, F24, F25]
stages: [F07-S01, F07-S02, F07-S03]
---

# CR-028 Requirement corrections from the foundation review

## Request

Before F08, the owner asked for a review of the requirements against popular salon products, of the design and
implementation of F01 to F07, and of the delivery process. The review is recorded in
`docs/reviews/2026-11-10-foundation-review/README.md`. This change request carries its requirement changes; the process
changes are CR-029. The owner approved the review's five recommendations as made ("approve as recommended").

## Why

- **No online booking in the first release.** None of the eleven leading products requires an app install to book, and the
  BRD's own section 5.5 called the booking site a first-class channel before CR-010 removed it. Through all of M1 the
  product had no customer booking at all, and NOT-03's reminder links to confirm, reschedule or cancel had nowhere to go.
- **One service per booking.** A cut, colour and blow-dry is one appointment in every product compared, in the adopted
  prototype (its appointments carry a list of services) and in PAY-06 ("add or remove services"). F07 built one service
  per booking because F07-S02 listed several as out of scope and no feature owned it.
- **Everyday desk work the register missed**: a deposit paid by bank transfer, a tip, a note on an appointment, a printed
  receipt, a sale with no appointment, a walk-in who gives no number, a customer with a foreign number, and two booking
  states with no way out.
- **Work scheduled where the market does not put it**: commission had no owning feature, tax and refund reports were R2
  although their data is R1, and R1 carried four things a first paying salon would not miss.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | **Added:** BKG-24 (a visit of several services), BKG-25 (staff book over a booking), BKG-26 (booking note), BKG-27 (walk-in queue, R2), PAY-16 (deposit recorded outside the gateway), PAY-17 (printed receipt), PAY-18 (counter sale), PLT-16 (import of appointments and history, R2), WEB-01 to WEB-03 (customer booking page). **Changed:** BKG-05, BKG-06, BKG-10, BKG-11, BKG-16, NOT-03, PAY-06, PAY-09, PLT-08, REP-01, APP-06, MKT-01, BRD-03, BRD-12, IAM-01, IAM-02. **Moved:** PAY-13 (tips) R3 could to R1 should; REP-06 R2 to R1; BKG-21 (recurring) R3 could to R2 should; REP-08 R3 to R2; BKG-17 (waitlist) and NOT-08 (notification centre) R1 to R2 |
| Features and stages | **F07** (merged, not released): owns BKG-24 and BKG-26 and the changed BKG-06 and BKG-11; one follow-up PR under decision 098, criteria below. **F12** starts before F08 (`started_early`; decision 115). **F08** gains PAY-16 and waits for F12. **F09** loses BKG-17. **F10** gains BKG-25, PAY-13, PAY-17, PAY-18, and BKG-10 loses its queue. **F11** loses NOT-08. **F13** gains REP-06. **F14**: PLT-08 no longer imports future appointments. **F15** hands MKT-01 to F24 and keeps it in `extends`. **New F24** Customer booking page (M1-web, after F08, F09, F11, F12; decision 117). **New F25** Commission and staff performance (M3-growth): STF-08, REP-08 |
| Test cases and automation | F07's cases that place, read or change a booking change shape with the API; QA rewrites them for the follow-up and adds cases for the new criteria. No other delivered feature's cases change. The stale regression cases the review found are repaired separately (CR-029) |
| Data, API and released apps | The booking tables are reworked by a forward migration (decision 114); nothing is released and no client but the workspace exists, so the old request and response shapes are replaced. Local and QA databases keep their data through the migration |
| Size and delay | F07's follow-up is the largest part: roughly the size of F07-S01 and S03 again. F24 adds one feature to M1. The four cuts and F12's move cost nothing. F08 starts after the follow-up and F12 |

## Recommendation

Approve all of it. Every booking, payment, notification and report built from F08 onwards hangs off the booking model, so
the visit must exist before them; and a salon that cannot take a booking from a link will not leave a product that can.

## Decision

Approved in full by the owner on 2026-11-10. The five decisions, as approved:

1. **A customer booking page in M1** (F24, WEB-01 to WEB-03). This reverses CR-010 for booking and managing a booking
   in a browser; there is still no customer account area, marketing website or marketplace (decision 117).
2. **Several services in one booking in R1** (BKG-24), built now as a follow-up to F07 (decisions 114, 116).
3. **R1 scope moves:** the additions, earlier scheduling and cuts in the Impact table.
4. **Staff may deliberately book over another booking** with a warning, a reason and an audit entry (BKG-25, in F10;
   decision 123).
5. **QA's scope shifts** from repeating the developer's API tests to browser state and timing: CR-029.

Not changed here, and left for the features that own them: OCC-15 and OCC-16 still say bridal customers act only in the
mobile app, though F19 ships before F20; the lead revisits them at `feature-new F19`. The facts the review reported from
secondary sources (the data-protection act's start date, SMS sender registration, the VAT invoice format, WhatsApp
pricing, PayHere's refund limits) are listed as open checks in the review record and become requirements only through
their own change request once confirmed.

## Criteria for F07's follow-up

Decision 098 adds a merged feature's new criteria to its stage specs. `qa/scripts/sdlc validate` counts every criterion
in a spec as untested until a case covers it, which would leave `main` failing until the follow-up merges. So the
criteria are recorded here now; the lead copies each section to the end of its stage's `spec.md`, unchanged, as the
first commit of the follow-up branch, and QA's cases join them in the same PR. The numbers continue each spec's own.

Nothing is released and the web workspace is the only client, so the old single-service request and response shapes
are replaced, not kept beside the new ones. The design reference for the changed sheets is the prototype's New
appointment and appointment sheets, whose appointments already carry a list of services; the lead confirms the changed
sheets with the owner before the screens are built.

### F07-S01 (`features/F07-online-booking/stages/S01-bookings-holds-and-states/spec.md`)

- AC-16: A booking is one visit with 1 to 6 services. `POST /api/v1/bookings` takes `items`, an ordered list of
  `{serviceId, variantId?, memberId}` (`memberId` null for "No preference"), with one `date`, `time`, `customer`, `channel`
  and `Idempotency-Key`. The first service starts at that time. Each later one starts when the service before it ends for
  the customer (its active and processing segments), or after that service's buffer when the same professional takes
  both. Each service keeps its own professional, terms version, variant, segments, price and occupied time, as AC-1 and
  AC-4 say for one. The visit has one reference, one state, one hold expiry and one history, and answers with `items` in
  order (each `{id, startsAt, endsAt, member, service, priceMinor}`) and `totalMinor`, the sum of its services' prices.
  A list of one service behaves exactly as AC-1 did. (BKG-24, BKG-04, BKG-14)
- AC-17: A visit is placed completely or not at all. If any service is refused for a reason AC-2 or AC-3 gives, nothing
  is stored and the answer names the first refused service by its position (`items[1]`): 400 field errors use that path,
  and a 409 carries it in `detail`. Two simultaneous requests that each need a time the other takes end with exactly one
  visit stored whole and the other refused 409 `slot_taken` with no service of its own left behind. Repeating a request
  with the same key returns the same visit; the same key with a different list or order is 422. (BKG-24, BKG-05, NFR-05)
- AC-18: Every state change acts on the whole visit in one transaction: `confirm`, `release`, `arrive`, `start`, `cancel`,
  `no-show`, `undo-no-show`, expiry by the job, and the in-process payment and checkout contracts. No endpoint changes the
  state of one service alone, and at no instant are two services of one visit in different states. `undo-no-show` is
  refused 409 `slot_taken`, changing nothing, when any service's time has since been taken. History has one entry per
  change of the visit, not one per service. (BKG-24, BKG-06)
- AC-19: Two more changes, each needing `booking.attend`: `undo-arrive` takes `arrived` back to `confirmed`, and
  `undo-start` takes `in_service` back to `arrived`, each only on the booking's local date. Each writes a history entry
  like any other change. (BKG-06)
- AC-20: A booking nobody marked on its day can still be dealt with. `arrive` on a later local date than the booking's is
  allowed with the new permission `booking.correct` (Owner and Manager) and a required `note` of 2 to 200 characters, and
  is 403 without the permission and 400 on `note` without the reason; `arrive` before the booking's date stays 409
  `invalid_state`. `no-show` accepts the same optional `note`. The reason is shown in the booking's history. (BKG-06)
- AC-21: `release` also ends a booking in `pending_payment`, with `booking.manage`. When the in-process payment contract
  abandons a payment, the booking returns to `held` with an expiry no earlier than the salon's hold length from that
  moment, so the time is still held while the customer tries again. (BKG-06, BKG-04, PAY-04)
- AC-22: The customer's mobile number may be any country's. A number typed without a country code is read in the
  configured region as today; one typed with `+` and a country code is accepted when libphonenumber finds it valid and
  possibly a mobile (`MOBILE` or `FIXED_LINE_OR_MOBILE`), and is stored in E.164. With the channel `walk_in` the mobile
  may be left out and is stored as absent; with every other channel a missing mobile is 400 on `customer.mobile`. The
  name stays required. Searching by mobile finds numbers of any country. (BKG-11, BKG-10)
- AC-23: A booking has an optional `note` of up to 500 characters, given when placing it or changed afterwards with
  `PUT /api/v1/bookings/{id}/note` (`booking.manage`, or `booking.attend` on a booking the caller may see), while the
  booking is held, pending payment, confirmed, arrived or in service. The read returns it. A change writes a history
  entry "note changed" with who and when; the audit trail records that it changed, never its text. It is returned only
  to callers who may read the booking, and never across salons. (BKG-26)
- AC-24: Reads: the list returns one row per visit with its services, professionals and total; `memberId` selects visits
  with a service for that professional; the own scope of a Professional shows visits with a service of theirs, and of
  those the whole visit. A reference or mobile given as `q` finds the booking without a date range. (BKG-24, BKG-14)
- AC-25: The contracts of AC-13 answer per service as before: a professional's upcoming bookings, a service's upcoming
  bookings and "Most booked" count each service of a visit on its own. Tenant isolation (AC-14) and the contract check
  (AC-15) hold for every changed and new endpoint. (BKG-24, NFR-01)

### F07-S02 (`features/F07-online-booking/stages/S02-availability-and-assignment/spec.md`)

- AC-13: The availability read takes the same ordered list of 1 to 6 services that `POST /api/v1/bookings` takes, each
  with its variant and its professional or "No preference", and one local date. It returns the start times at which
  every service can be done in that order under F07-S01 AC-16's rule for when each begins, each wholly inside the branch's
  opening hours and its professional's working time and clear of every live booking and hold. Each time names who would
  take each service. A list of one service answers exactly as AC-1 did. (BKG-24, BKG-01)
- AC-14: With "No preference" on a service of a visit, a professional already taking another service of that visit is
  chosen when eligible and free; otherwise the salon's strategy chooses (AC-5). The professional the availability read
  named for a time is the one a hold placed at that time gets, unless that time was taken in between, which is 409
  `slot_taken`. (BKG-03, BKG-24)
- AC-15: An empty answer says why as AC-4 does, for the first service in the list that makes it empty, and names that
  service's position. (BKG-01)
- AC-16: AC-11's limit holds for a visit of three services with 20 eligible professionals each. A read of the days in a
  range of up to 31 days that have at least one free time for a given list answers within the same limit as one day,
  without one schedule read per professional per day. (NFR-03, BKG-01)

### F07-S03 (`features/F07-online-booking/stages/S03-staff-booking-screens/spec.md`)

- AC-13: New appointment takes several services. After the first service (and its variant and professional), **Add
  another service** adds a row with its own service, variant and professional or "No preference", up to six; a row can
  be removed, and the rows keep their order. A running summary shows the total time and the total price. **Find free
  times** offers times for the whole visit; each time names the professional when one person takes every service, and
  otherwise "2 people". Choosing a time holds the whole visit, and the held summary lists each service with its time and
  professional. (BKG-24, BKG-11)
- AC-14: With the source "Walk-in" the mobile field is marked optional and the booking can be held and confirmed without
  it; with any other source the field is required as today. A number typed with `+` and a country code is accepted and
  shown in international format; a local number is shown in national format as AC-12 says. (BKG-11, BKG-10)
- AC-15: The Bookings list shows one row per visit: its first start time, the customer, its services (the first, then
  "+2 more" when it has more), its professionals, status, source and total. The appointment sheet lists every service
  with its time, professional and price, then the total. (BKG-24)
- AC-16: The appointment sheet offers **Undo arrived** and **Undo start** when the API allows them, and for an Owner or
  Manager, on a confirmed booking whose day has passed, **Mark arrived late**, which asks for the reason before it is
  sent. A no-show can be given a reason. A booking waiting for a payment offers **Release**. Each appears only when
  `allowedActions` names it. (BKG-06)
- AC-17: New appointment and the appointment sheet have a **Note** field of up to 500 characters with a counter. The
  note shows on the sheet under the services and can be changed there by anyone who may change the booking; the history
  shows "Note changed" with the person and time. (BKG-26)
- AC-18: Every new control and state meets AC-11 (loading, empty, error and refused states at every width, keyboard use
  and the automated accessibility check). A refusal that names a service of the visit is shown beside that service's
  row. (NFR-12)

