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
own stages. Each new critical check was shown to reject a realistic violation for the intended reason (see "Evidence").
