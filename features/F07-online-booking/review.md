# F07 Booking engine and staff booking foundations: product review

Reviewed as: a receptionist at the front desk booking a phoned customer on a busy Saturday (tablet at 768, and the
desktop at 1280), a professional checking and starting their own next customer between clients (phone at 360), a salon
owner or manager setting how holds and "No preference" work, and the operator in a support session. Customers meet the
booking engine only through the later React Native app (F15); there is no customer screen in F07.
Compared with: Fresha, from its public help centre, read 2026-11-09 (pages listed under "Compared with other products").

## Test approach

| Type | Applies because | Ruled out because |
| --- | --- | --- |
| `api` | Every new endpoint: holds, the seven state changes, the list and single read, availability, open days, the two new rules. Each boundary of each field as rows of one case, each documented error, the contract checked against the operation's documented response for every status provoked (TC-F07-S01-036, TC-F07-S02-009) | - |
| `concurrency` | One case per invariant: twenty holds at once on one professional's time (exactly one), ten at once for ten professionals (all), two changes at once on one booking, a service delete, a removal and taking a service each racing a hold (swept in both orders), ten "No preference" holds at once for three free people (three different people) | - |
| `data-integrity` | The state machine through every allowed and refused change at its time boundaries (the start minute, the booking's day, the next day), terms, segments, price and rules version copied onto the booking, history and audit for every change including the expiry job's, the outbox event carrying ids only, references unique per salon by constraint, the affected lists and "Most booked" counting only live bookings, free times worked out by an independent oracle (qa/shared/availability.ts) from the arrangement | - |
| `tenant-isolation` | Every new endpoint and both new tables: another salon's booking, professional, service, variant and reference answer as unknown ids by every method and in search; the application role with one salon's context and with none at the database; twenty requests for two salons sent together; availability never worked out from another salon's data; the web page aimed at another salon's booking | - |
| `permissions` | Each of the five roles, signed out and a support session at every booking endpoint; the Professional's own, unlinked and granted (IAM-08) scopes; who may read free times; suspended and read-only salons; on screen, exactly the actions each role and scope may take | - |
| `security` | No customer mobile in any log line or audit entry during a run of every change; errors reveal nothing of another salon (the same body as an unknown id) | Sign-in, sessions and exports are unchanged by F07 |
| `functional` | The Bookings page (list, countdown, tools, empty state, pages, URL), New appointment (fields, free times and their reasons, hold, confirm, choose another, close with a hold, a double click, a time taken meanwhile, a hold running out), the appointment sheet and cancel dialog, every refusal shown, and the Booking rules section | - |
| `responsive` | The page and both sheets at the six widths of `docs/design/ui-ux-standards.md`, with a long customer name; the Booking rules section at every width | - |
| `accessibility` | Axe (WCAG 2.2 AA) on the page and both sheets at 360, 768 and 1280, and on the rules section; keyboard-only booking with focus kept in and returned from the sheet; the countdown as a timer that is not read out every second; the leave guard | - |
| `resilience` | The page's unavailable state when its data calls fail | No provider is added; the expiry job is the scheduler's existing at-most-once claim, proved by the developer |
| `load` | AC-11 and NFR-03: the availability read under load, written as release-suite case TC-F07-S02-018 (qa/api/F07/S02-availability-load.spec.ts, qa/load/F07/S02-availability.js) | Not run: the release suite runs only when the owner asks (decision 068). Deferred to release-prepare (S02 stage.yaml) |
| `branding` | - | F07 changes no brand; the screens use the existing theme |
| `mobile` | - | No customer-app screen in F07 (F15) |
| `payment-integrity` | - | Nothing is charged in F07; pending payment is reached only by F08's in-process contract (deferred, TC-F07-S01-038) |
| `build` | - | No criterion about the build or the tooling |

The cases F04, F05 and F06 deferred to F07 (TC-F04-S03-031, TC-F05-S01-020, TC-F05-S02-015, TC-F06-S01-011 to -013,
TC-F06-S02-028, -029, TC-F06-S03-030, -031 and TC-F06-004) now run with real bookings and are registered under their
own stages (run: qa/reports/20261109-224356-F07-unblocked-deferred-cases).

Each new critical check was shown to reject a realistic violation for the intended reason, by a one-off script run once on
76e3b080 and kept with its result in qa/reports/20261109-223501-F07-10961619c25e/proof/: two held for one contested time
(the invariant of TC-F07-S01-011), a change between "nothing changed" snapshots, a readable booking and another salon's
body against "not found as an unknown id" (TC-F07-S01-029), slot_taken against slot_unavailable, a body missing a required
field against the contract (TC-F07-S01-036, TC-F07-S02-009), free times ignoring a booking against the oracle, and an
allowed role against "refused": 8 of 8 rejected.

## Verdict

The desk can book a phoned customer in one sheet on any open day: free times are offered with who would take them, the
chosen time is held with an honest countdown from the server's expiry, and the booking moves only through the states the
rules allow, each recorded. The engine underneath held every race and every isolation check we put to it. One major bug
stands (BUG-F07-001: a hold that runs out stays on the list at "Held · 0:00 left"). The one change that would most help
the desk is a list row they can read at a glance: the time on one line instead of the full date on every row (SUG-F07-01).

## What works well

- One sheet from phone call to confirmed booking: name, mobile, day, service, professional and source, free times as
  chips with who would take each, a held card with every fact the desk reads back, and a confirmation that says the
  reference. Fresha's staff flow, by its help centre, holds nothing while the desk talks to the customer; ours does.
- The countdown follows the server's expiry, not the browser's clock, and the sheet asks the API at zero instead of
  deciding on its own (decision 111 d). Changing a field while a time is held releases the hold, so a desk cannot leave
  a stray hold behind (decision 111 i).
- The appointment sheet offers exactly the actions the API allows for the caller now, with the prototype's one-line
  explanation of why the others are missing.
- The narrow list and both sheets keep the prototype's layout at 360 pixels, and the Professional's own view on a phone
  shows only their customers and only the next step (Start service after Mark arrived).
- Refusals are never swallowed: a booking changed elsewhere is re-read with "This booking changed. Here is its current
  state.", and a time taken meanwhile offers the fresh times.

## Suggestions

Ranked, most valuable first. A finding that breaks a requirement is a bug, not a suggestion: file it.

| Id | Class | Suggestion | Disposition |
| --- | --- | --- | --- |
| SUG-F07-01 | should | On the wide list, show each booking's time on one line and leave the day to the heading | |
| SUG-F07-02 | should | Name the staff member in a booking's History, not their sign-in email | |
| SUG-F07-03 | should | When a day has no free time, offer the next day that has one | |
| SUG-F07-04 | idea | Put the status and source badges side by side in the wide table | |
| SUG-F07-05 | idea | Show an example mobile number in the empty field, and keep the search placeholder whole at 360 pixels | |

### SUG-F07-01 On the wide list, show each booking's time on one line and leave the day to the heading

- **Observed:** Bookings at 1280 pixels: every row's When cell reads "Sat, Feb 5, 2101" and then "10:00 – 11:00" broken
  over two more lines, so each row is four lines tall although the list shows one day, already named in the heading
  ("4 bookings on Sat, Feb 5, 2101"). The prototype's rows read "Today" and "9:00 AM – 10:15 AM" on two short lines.
- **Why it matters:** a receptionist on a busy Saturday scans the day's list while on the phone; four-line rows show
  half as many bookings per screen and push the time, the one thing they look for, below the date.
- **Instead:** in the wide Table, When shows the start–end on one line (the day only when the row is not on the heading's
  day, which a one-day list never has), and the column is wide enough not to wrap "10:00 – 11:00".
- **Evidence:** owner-page-1280.png and prototype-page-1280.png (every screenshot named here is in
  qa/reports/20261109-223501-F07-10961619c25e/review-shots/); the narrow rows already do this ("10:00" at the side, the
  range under the service).

### SUG-F07-02 Name the staff member in a booking's History, not their sign-in email

- **Observed:** the appointment sheet's History reads "Confirmed / pradeep.ekanayake@salonstaff.lk · Sat, Feb 5, 2101,
  08:00" for every step. Decision 111 (b) accepted the API's label (email, else mobile) because the actor carries nothing
  else the caller can show.
- **Why it matters:** an owner reading why a booking was cancelled thinks of people by name; an email is long, wraps on a
  phone, and for a staff member with no email the history shows their mobile number to everyone who can read bookings.
- **Instead:** the history's actor carries the staff member's display name (the team member's name when linked, else the
  account's name), and the sheet shows it. This is an API change to S01's `BookingActor`, so a change request.
- **Evidence:** owner-appointment-1280.png and professional-appointment-360.png from this review.

### SUG-F07-03 When a day has no free time, offer the next day that has one

- **Observed:** New appointment on a fully booked day says "No free time on <day> for this service. Try another day or
  professional." and the desk must open the calendar and guess day by day.
- **Why it matters:** the customer on the phone usually asks "when is the next one?"; each guess is another search while
  they wait.
- **Instead:** under the reason, a button "Next free day: <day>" that searches it, using the open days S02 already
  serves and stopping after a bounded number of days.
- **Evidence:** Fresha's "Find an available time" offers "Next available date" and "Check all professionals" when no
  slot is free (help centre, Create appointments, read 2026-11-09).

### SUG-F07-04 Put the status and source badges side by side in the wide table

- **Observed:** in the wide Table the status and source badges stack, adding a line to every row; the prototype puts them
  side by side ("Confirmed  WhatsApp").
- **Why it matters:** row height again (see SUG-F07-01); the two badges read as one fact.
- **Instead:** one line, wrapping only when the column is too narrow.
- **Evidence:** owner-page-1280.png beside prototype-page-1280.png.

### SUG-F07-05 Show an example mobile number in the empty field, and keep the search placeholder whole at 360 pixels

- **Observed:** the prototype's Mobile number field shows "077 123 4567" when empty; ours shows nothing. At 360 pixels the
  search reads "Search name, mobile or referen".
- **Why it matters:** small; the example tells a new receptionist which form to type, and a cut word looks unfinished.
- **Instead:** the prototype's placeholder; a shorter placeholder at narrow widths ("Name, mobile or reference").
- **Evidence:** owner-new-times-360.png, prototype-new-360.png, owner-page-360.png.

## Compared with other products

Fresha's public help centre, read 2026-11-09: Create appointments
(https://www.fresha.com/help-center/knowledge-base/calendar/260-create-appointments-1), Update appointment statuses
(.../600-update-appointment-statuses), Mark appointments as no-shows (.../30-mark-appointments-as-no-shows), Cancel
appointments (.../286-cancel-appointments), Set up new appointment assignment (.../102178-set-up-new-appointment-assignment).

| Aspect of this flow | Ours | Fresha | Better for our users |
| --- | --- | --- | --- |
| Finding a time for a phoned customer | One sheet: day, service, professional or No preference, then the day's free times as chips | A time slot on the calendar, or "Find an available time" with a list, "Next available date" and "Check all professionals" | Same for one day; theirs when the day is full (SUG-F07-03). The calendar is F10's here |
| Holding the time while the desk talks | Held for the salon's hold length with a countdown; nobody else can take it | None mentioned; the FAQ says double booking in one slot is allowed | Ours |
| "No preference" | Assigned when the hold is placed, by the salon's strategy (least or most booked), for staff and later the app | Only for online bookings ("most free time that day"); staff bookings choose a person | Ours |
| States | Held, confirmed, arrived, in service, completed (checkout only), cancelled, no-show | Booked, confirmed, completed (checkout), cancelled, no-show; arrived and started only as custom statuses | Ours: arrival and start are built in |
| Cancelling and no-shows | Cancel only before the start with a reason; no-show only after it, undone the same day | The same rules; "Undo no-show" with no stated day limit | Same |
| Reference | Six characters, said to the customer on confirming | None mentioned in the pages read | Ours, for phone and WhatsApp follow-ups |

## Notes for the lead

- The S03 handoff says Bookings sits "right after Account and before Staff"; decision 111 (c) says "before Team". The
  navigation has no Team entry: the Staff entry opens the team. Both describe the built order; the decision's wording
  could name the entry as it reads.
- docs/qa/test-plan.md, "Suites", says smoke will hold "book and pay in the sandbox and see the booking on the salon's
  calendar" once booking exists. Payment and the calendar are F08 and F10, so F07's smoke entry is the desk journey
  (TC-F07-001) without payment.

- F04's Hours tab, not F07: with the test clock in 2101 (where the date after every plan now lies), the salon's Owner sees
  "Your account cannot change the salon's settings." although their principal carries `settings.manage`; on the real
  clock, and with the clock in late 2100 earlier the same day, the tab works. Probed under three conditions
  (qa/reports/20261109-224356-F07-unblocked-deferred-cases); the likely cause, not proved, is that no holiday year is
  published for 2101 and the tab reports it with the permission message. No real salon is in 2101, so it is not filed as
  an F07 bug; TC-F04-S03-031 runs on the real clock. Worth a look by F04's owner: the wrong message, and QA's
  ever-advancing test-clock dates.

## Bugs filed from this review

- BUG-F07-001 (major), found by TC-F07-S03-003 and confirmed by hand with the page's requests recorded.
