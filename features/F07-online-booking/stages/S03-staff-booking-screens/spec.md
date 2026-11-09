# F07-S03 Staff booking screens

## Objective

The staff workspace gains **Bookings**. The front desk lists any day's bookings and holds, books a customer who phones,
messages or walks up on any open day through **New appointment** (free times offered, the chosen time held with a visible
countdown, then confirmed), and opens any booking to see its details and history and take the next step the rules allow.
A Professional sees their own day and marks their customers arrived and in service. This completes F07's staff side on
the APIs of F07-S01 and F07-S02.

## Read first

- `docs/brd/requirements.yaml`: BKG-04, BKG-11, NFR-12; and BKG-06, BKG-14, BKG-16, which these screens show.
- `features/F07-online-booking/README.md`; F07-S01's and F07-S02's `spec.md` and `handoff` (the final endpoint shapes and
  any "Corrections at close"); decisions 084, 103, 106 and 108.
- `docs/engineering/web.md`: tenant routing, the BFF rules, sessions, "Styling" (the component map), "Tests".
- `docs/design/ui-ux-standards.md`: states, formats, widths, accessibility, wording.
- `docs/design/prototype/index.html`: Salon web, **Bookings**, in the F07 block at the end of the file: `aApptsW`,
  `aAppts`, `f7Tools`, `f7Badges`, `f7New`, `f7Find`, `f7Hold`, `f7HoldPaint`, `f7Confirm`, `f7Expire`, `openA`, `f7Set`,
  `f7Cancel`. The block's opening comment lists what is not built.
- `docs/architecture/identity-and-access.md`: "Role permissions" (`calendar.read` and its scope, `booking.manage`,
  `booking.attend`).
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| BKG-04 | Selecting a time places a hold (10 minutes by default) with a visible countdown. The hold appears on the staff calendar, is not released while a payment is in progress, and is released automatically on expiry. | Part: the countdown and holds on the Bookings list. The calendar is F10's |
| BKG-11 | Staff can create a booking on a customer's behalf on any open day: a mobile number is required, free times across eligible professionals are offered, and the source (phone, WhatsApp, front desk) is recorded. | Full (decision 108) |
| NFR-12 | Customer surfaces and workspace meet recognised accessibility criteria. | For the new screens |

## Scope

- **Web:** the Bookings navigation entry and page (`/workspace/bookings`), the New appointment sheet, the appointment sheet
  and the cancel confirmation; their BFF routes to F07-S01's and S02's endpoints.

## Out of scope

- Payment columns, Take payment and deposits (F08, F10), Reschedule (F09), Today, the calendar and walk-in queue (F10),
  customer records and "Book appointment" from a customer (F12), messages to the customer (F11).
- The Booking rules section (F07-S02). Any API change: a gap in S01's or S02's endpoints is reported to the lead.

## Acceptance criteria

- AC-1: Anyone with `calendar.read` sees **Bookings** in the workspace navigation, after Today's place in the prototype's
  order of delivered entries, opening `/workspace/bookings`; others neither see it nor open the page (they get the
  workspace's not-permitted state). (BKG-11)
- AC-2: The page lists one local day's bookings and holds, today by default, in time order. Wide (from 900 pixels) a Table
  with When (day and start–end), Customer (name and mobile in the national format), Service (with the variant), Professional,
  Status (Badge with the prototype's tones, a hold reading "Held · 8:41 left" and counting down) and Reference; narrower, the
  prototype's rows with the same facts. The heading says "N bookings on <day>" with singulars. Expired holds disappear
  without a reload when their countdown reaches zero. (BKG-04, BKG-06, BKG-14, BKG-16)
- AC-3: Tools: search by name, mobile or reference, applied as typed (debounced) through the API's `q`; a day chooser (a
  Button showing the day, opening a Calendar, with a **Today** shortcut); a status Select (All statuses, Held, Confirmed,
  Arrived, In service, Completed, Cancelled, No-show); a professional Select (Everyone and each member) for callers with the
  salon scope. With no match: Empty with "No bookings match." / "Try another day, or clear the search." and **Clear
  filters**. More than one page of results uses Pagination. The URL keeps the day and filters, so reload and back restore
  them. (BKG-06)
- AC-4: **+ New appointment** (shown with `booking.manage`) opens the sheet: "For phone calls, messages and front-desk
  bookings on any open day."; Customer name; Mobile number ("Required. The confirmation goes to this number."); Day, a
  Calendar offering only open days from today to a year ahead (S02's open days); Service, with each service's price as the
  prototype shows, and its variant Select when it has variants; Professional (No preference, then each member who performs
  the chosen service); **How did they book?** (Phone, WhatsApp, Front desk, Walk-in, Instagram, Google; Phone first); and
  **Find free times**. Missing or invalid name or mobile show the prototype's messages ("Add the customer’s name.", "Add a
  valid mobile number so the customer gets the confirmation.") on their fields, and no search runs. (BKG-11)
- AC-5: **Find free times** lists the day's times as chips "10:30 AM · Nimal" under "Free times on <day>. Choose one to hold
  it." (with "No preference": "Each time shows who would take it, sharing bookings evenly." or "… filling the busiest day
  first." per the salon's strategy). An empty answer shows the reason: closed ("The salon is closed on <day>."), nobody
  performs it ("Nobody on the team does this service."), not working ("<Name> isn’t working on <day>." or "Nobody who does
  this service is working on <day>."), fully booked ("No free time on <day> for this service. Try another day or
  professional."). Changing any field clears the times. (BKG-01, BKG-03, BKG-11)
- AC-6: Choosing a time places the hold (with "No preference" sending no professional, S02's AC-5) and replaces the times with
  the card "Holding this time" with a live countdown from the hold's `holdExpiresAt` (by the server's expiry, not a local
  ten minutes), the customer, service, professional, when and source, and "Nobody else can take this time while it is held.
  It is released by itself when the time runs out." **Confirm booking** confirms it, closes the sheet and says "Booked for
  <day> at <time> with <first name>. Reference K7M-4QX."; **Choose another time** releases the hold and searches again.
  A time taken meanwhile shows "That time was just taken. Choose another." and the fresh times; a hold that runs out shows
  "The hold ran out and the time was released. Find free times again." and confirming after expiry says the same. Closing
  the sheet with a hold releases it. A double click places one hold (the `Idempotency-Key` is per attempt). (BKG-04, BKG-05,
  BKG-11)
- AC-7: Selecting a row opens the appointment sheet: the customer's name, status and source Badges, the rows Reference,
  Mobile, Service, Professional, Date, Time and Source, the actions the booking's `allowedActions` permits (**Mark arrived**,
  **Start service**, **Mark no-show**, **Undo no-show**, **Cancel booking**, **Release hold**), each sending its change and
  saying "Marked arrived.", "Service started.", "<First name> marked no-show.", "No-show undone.", "Hold released. The time
  is open again.", and the prototype's explanation under the actions ("Arrival is marked on the day.", "You can mark a
  no-show once the start time has passed.", "Cancel and no-show are no longer possible once the customer has arrived.",
  "The booking is completed at checkout, which arrives with the front desk.", "A no-show can be undone today only.",
  "Cancelled bookings can’t be changed. Make a new booking instead."). **History** lists every change, newest first, with
  what, who ("You", the label, or "System") and when, and a cancellation's reason and note. (BKG-06)
- AC-8: **Cancel booking** asks first, in an Alert Dialog: "Cancel this booking?" with the customer, day and time and "The
  time opens again for others.", a required **Reason** Select (Customer asked to cancel, The salon can’t do it, Booked by
  mistake, Other), an optional **Note** of up to 200 characters ("your team only"), **Cancel booking** (destructive) and
  **Keep booking**. On success "Cancelled. The time is open again." (BKG-06)
- AC-9: Every refusal from the API is shown, not swallowed: `invalid_state` (the screen refreshes the booking and says
  "This booking changed. Here is its current state."), `slot_taken`, `hold_expired`, `slot_unavailable`, `rules_not_set`
  ("Set your booking rules in Settings before taking bookings.", with a link for those who may), `permission_denied`,
  `support_read_only` and the unavailable state. After any change the list and the sheet show the new state without a
  reload. (BKG-06)
- AC-10: Roles: Owner, Manager and Receptionist use everything; a Professional with the own scope sees their own bookings,
  no professional filter, no **New appointment**, and only **Mark arrived** and **Start service**; with the IAM-08 grant the
  salon's bookings, still without booking or cancelling; a support session sees everything read-only; an Accountant has no
  entry. (decision 108)
- AC-11: Every screen and sheet provides, at every width `docs/design/ui-ux-standards.md` names: loading, empty, error and
  unavailable states; keyboard-only use with accessible names, focus kept in and returned from sheets, the countdown
  announced at most once a minute (and at one minute left) rather than every second; no sideways scrolling at 360 pixels;
  the leave-with-unsaved-edits guard on a started New appointment form; and no WCAG 2.2 AA violation in the accessibility
  check. (NFR-12)
- AC-12: Times use the existing `formatClock`, dates the existing day formats (decisions 103, 107), mobiles the national
  format; words through next-intl with curly apostrophes and correct singulars; every interactive element has a
  `data-testid`. (BKG-14, BKG-16)

## Design notes

- **Module.** A `bookings` area under `apps/web/src/features/booking` (which already holds the booking-rules client), its
  screens under `surfaces/workspace`, routes under `app/(workspace)/workspace/bookings`, as earlier features are laid out.
- **The countdown** reads `holdExpiresAt` from the API and the server's offset; it never decides expiry itself: on zero it
  asks the API and shows what the API says.
- **One hold at a time** per sheet. The sheet owns its hold's id and releases it on close, on "Choose another time" and on
  leaving the page.
- **Data scope** comes from the principal; the screen never filters for security, the API does.

## API and data changes

Not applicable for this stage, beyond the BFF routes that pass F07-S01's and S02's endpoints through under the existing BFF
rules (Origin check, permission, no caching of personal data).

## UI and UX

From the prototype's F07 block and the component map:

| Screen | Components |
| --- | --- |
| Bookings page | Sidebar entry; heading; Input Group search; Popover with Calendar for the day; Select for status and professional; Table (wide) and Item rows (narrow); Badge (status tones, `caution` for held); Empty; Pagination; Skeleton |
| New appointment | Sheet; Field with Input (name, mobile); Popover with Calendar (open days only); Select (service, variant, professional, source); Button; Toggle `chip` variant for the times; Card for the hold with a `role="timer"` countdown; Alert for refusals; Sonner for success |
| Appointment | Sheet; Badge; Card with key-value rows; Buttons for actions; Item rows for History |
| Cancel | Alert Dialog with Select and Field with Textarea |

## Risks and test focus

- `functional`: the hold lifecycle on screen (confirm, choose another, expiry, close with a hold, taken meanwhile); each
  action appearing exactly when `allowedActions` permits.
- `permissions`: each role and scope sees and does exactly what AC-10 says.
- `accessibility` and `responsive`: sheets, the timer's announcements, focus, 360 pixels, keyboard.
- `usability`: messages for every refusal; filters kept in the URL.
- `tenant-isolation`: the BFF never lets a request reach another salon (the API already refuses; prove the route).

## Verification commands

```bash
pnpm -r lint
pnpm -r typecheck
pnpm -r test
pnpm --filter @salon/web test:a11y
pnpm --filter @salon/web build
pnpm contracts:check
```

## Done criteria

- The developer has checked every acceptance criterion on the running product, in a browser at 360, 768 and 1280 pixels,
  with each role, and recorded it in the handoff.
- The verification commands are green.
