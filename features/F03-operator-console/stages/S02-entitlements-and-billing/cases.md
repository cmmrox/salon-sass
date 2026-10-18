# F03-S02 Entitlements and subscription billing: test cases

Prepared during the F03 build from the approved spec, `docs/architecture/operator-console.md`
("Subscriptions, invoices and entitlements" and "Local test hooks"), decision 049, PLT-04/PLT-05
and `docs/qa/`. Nothing here has been run; the cases are performed once in the F03 feature pass.

Environment: local only, fake billing provider and capturing `email` channel; no real charge or
message. Every billed salon is provisioned by the test (or created with the unbilled legacy salon
hook for AC-2); alpha, beta and gamma are never billed. They are the other salons in isolation
rows. Time moves only through the test clock, and the billing job is run through the run-job
hook at the instant under test, so boundaries are checked one second either side without waiting
for the scheduler. Clock-moving cases run serially and restore real time.

Amounts are integers in minor units (0 to 100,000,000). An invoice's due instant is issue + 7 days;
reminders at due, due + 3 days and due + 10 days; read-only at due + 14 days (decision 049).

## Tables

| Operator billing action | Admin | Support | Staff roles x5, customer | Non-console host |
| --- | --- | --- | --- | --- |
| `GET /salons/{id}/subscription`, `GET /salons/{id}/invoices`, `GET /invoices/{id}` | allow | 403 `permission_denied` | 401 `invalid_session` | 404 `not_found` |
| `POST`/`PATCH /salons/{id}/subscription`, `PUT /salons/{id}/plan`, `POST /invoices/{id}/payments` | allow | 403 `permission_denied` | 401 `invalid_session` | 404 `not_found` |

| Salon billing read (salon host) | Owner | Manager, Receptionist, Professional, Accountant | Customer | Other salon's staff |
| --- | --- | --- | --- | --- |
| `GET /api/v1/salon/entitlements` | allow | allow | 403 `permission_denied` | 404 `not_found` |
| `GET /api/v1/salon/invoices`, `/{id}` | allow | 403 `permission_denied` | 403 `permission_denied` | 404 `not_found` |

## Cases

Every case id here is declared under `cases:` in the yaml beside it, and the reverse.

### TC-F03-S02-001 A salon created with a subscription is trialing until exactly 14 days after creation

- **Type:** api
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** Synthetic Admin
- **Preconditions:** Test clock set to a fixed instant; a plan
- **Steps:**
  1. Create a card salon and a bank-transfer salon with monthly amounts 0 and 100000000
  2. GET each subscription and the salon detail
- **Expected:** Both 201; state `trialing`, `trialEndsAt` = creation instant + 14 days to the second, the amount, the salon's currency, the method, and a `version`.
- **Automation:** `qa/api/F03/S02-billing-access.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-002 A missing or invalid monthly amount or method creates no salon

- **Type:** api
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** Synthetic Admin
- **Preconditions:** Salon total read first
- **Steps:**
  1. Create salons with: no `subscription`; amount missing, -1, 1.5, "100", 100000001; method missing or `paypal`; unknown subscription field
  2. Check each slug and the total
- **Expected:** Each is 400 `bad_request` naming the field; no slug resolves; the total is unchanged.
- **Automation:** `qa/api/F03/S02-billing-access.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-003 An unbilled legacy salon works without a subscription until an Admin starts one, once

- **Type:** api
- **Priority:** high
- **Covers:** AC-2
- **Performed as:** Synthetic Admin; the legacy salon's Owner
- **Preconditions:** A salon from the unbilled legacy salon hook
- **Steps:**
  1. GET its subscription; Owner reads entitlements and performs a staff creation
  2. Start a subscription without a currency; then with LKR and an Idempotency-Key; repeat with the same key; start again with a new key
- **Expected:** Subscription 404; entitlements show no subscription and the mutation works; without a currency the start is 400 naming `currency` (decision 051: a legacy salon is given one when billed); with LKR it is 201 `trialing` for 14 days; the replay returns the original; the second start is 409 `invalid_state` with no change.
- **Automation:** `qa/api/F03/S02-billing-access.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-004 Crossing trial end issues exactly one correct first invoice

- **Type:** data-integrity
- **Priority:** critical
- **Covers:** AC-3
- **Performed as:** Synthetic Admin; run-job hook
- **Preconditions:** A bank-transfer salon created at a fixed clock instant
- **Steps:**
  1. Set the clock to trial end - 1 s and run the billing job
  2. Set it to trial end and run the job three times
  3. List the salon's invoices; read the last invoice number before and after
- **Expected:** Nothing at - 1 s; at trial end exactly one invoice with the next sequential `INV-nnnnnn` number, the plan key and name at issue, the amount and currency, the period from trial end to one month later in the salon's zone, issue = trial end, due = issue + 7 days, status `open`; repeated runs add nothing.
- **Automation:** `qa/api/F03/S02-billing-cycle.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-005 Concurrent billing job runs issue no duplicate invoice

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-3
- **Performed as:** Run-job hook
- **Preconditions:** A salon one second past trial end with no invoice
- **Steps:**
  1. Run the billing job eight times at once
  2. List invoices and read numbering
- **Expected:** Exactly one invoice for the period; invoice numbers stay consecutive with no gap or repeat.
- **Automation:** `qa/api/F03/S02-billing-cycle.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-006 Monthly invoices follow the anchor day, including a 31st anchor in shorter months

- **Type:** data-integrity
- **Priority:** high
- **Covers:** AC-3
- **Performed as:** Synthetic Admin; run-job hook
- **Preconditions:** A salon whose trial ends on a 31st (salon zone Asia/Colombo) and one in Europe/London across a DST change
- **Steps:**
  1. Pay each invoice by transfer and advance the clock through four months, running the job at each issue instant and one second before
- **Expected:** One invoice per month; a 31st anchor issues on the last day of February/April and on the 31st when it exists; issue times follow the salon's local anchor across DST; never two invoices for one period.
- **Automation:** `qa/api/F03/S02-billing-cycle.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-007 A successful card charge pays the invoice and keeps the salon active

- **Type:** payment-integrity
- **Priority:** critical
- **Covers:** AC-4
- **Performed as:** Run-job and fake-provider hooks
- **Preconditions:** A card salon; fake outcome success
- **Steps:**
  1. Cross trial end and run the job
  2. Read invoice, payments and subscription
- **Expected:** The invoice is `paid` with one card payment of the invoice amount; the subscription is `active`; no failed-charge email.
- **Automation:** `qa/api/F03/S02-billing-cycle.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-008 A failed card charge sends one notice and is retried on each reminder day

- **Type:** payment-integrity
- **Priority:** critical
- **Covers:** AC-4
- **Performed as:** Run-job, fake-provider and email-capture hooks
- **Preconditions:** A card salon; fake outcome failure
- **Steps:**
  1. Cross trial end and run the job twice
  2. Advance to due, due + 3 days and due + 10 days, running the job at each
  3. Read charge attempts and captured emails for the Owner contact
- **Expected:** The invoice stays `open`; one failed-charge email per failed attempt, to the Owner contact only; one attempt at issue and one on each reminder day, each with its own stable attempt key; nothing doubled by repeated runs.
- **Automation:** `qa/api/F03/S02-billing-cycle.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-009 Repeated, late, unsigned or tampered provider events pay an invoice at most once

- **Type:** payment-integrity
- **Priority:** critical
- **Covers:** AC-5
- **Performed as:** Anonymous caller on the platform host; fake-provider signing hook
- **Preconditions:** A card salon with an open invoice and its attempt key
- **Steps:**
  1. Post a signed success event; post the same event id again
  2. Post a new signed success for the now-paid invoice
  3. Post an unsigned event, one with a wrong signature, one with a body altered after signing, and one for an unknown invoice
  4. On another invoice record a transfer, then post a late signed success
  5. Post a signed event on a salon or console host
- **Expected:** Each invoice has exactly one payment; the first signed success is acknowledged `paid`, its repeat `duplicate`, a new event for the paid invoice and the late success `already_paid`, an unknown invoice `unknown`, all with no change; unsigned, wrongly signed and altered events are 401 `invalid_credentials` with no provider event recorded (`X-Salon-Billing-Signature`, operator-console.md "As built"); the event route is 404 on non-platform hosts.
- **Automation:** `qa/api/F03/S02-billing-cycle.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-010 Simultaneous duplicate events and a racing transfer record one payment

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-5, AC-6
- **Performed as:** Fake-provider signing hook; synthetic Admin
- **Preconditions:** A card salon with one open invoice
- **Steps:**
  1. Post one signed success event eight times at once while the Admin records a transfer for the same invoice
- **Expected:** Exactly one payment exists and the invoice is `paid`; the losing transfer is refused (paid invoice) or the losing events are acknowledged without effect; one payment audit.
- **Automation:** `qa/api/F03/S02-billing-cycle.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-011 A recorded bank transfer pays an open invoice once per key

- **Type:** payment-integrity
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** Synthetic Admin
- **Preconditions:** A bank-transfer salon with an open invoice
- **Steps:**
  1. POST /invoices/{id}/payments with reference, exact amount and today's date
  2. Repeat with the same key and body; repeat with the same key and a changed reference
- **Expected:** 201, the invoice `paid` with one transfer payment; the replay returns the original with no second payment or audit; the changed body is 409 `idempotency_conflict`.
- **Automation:** `qa/api/F03/S02-billing-cycle.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-012 Partial, excess, future-dated or paid-invoice transfers are refused without change

- **Type:** payment-integrity
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** Synthetic Admin
- **Preconditions:** A salon with an open invoice and a paid invoice; a second salon with an open invoice
- **Steps:**
  1. Record transfers: amount - 1, amount + 1, received tomorrow, against the paid invoice, an unknown invoice id, missing reference, missing key
  2. Read both salons' invoices and payments
- **Expected:** Amount, date, reference and key rows are 400 `bad_request` naming the field (the key row names the header); the paid invoice is 409 `invalid_state`; the unknown id is 404 `not_found`; nothing changes on either salon.
- **Automation:** `qa/api/F03/S02-billing-cycle.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-013 Due, reminder and read-only boundaries change state and send each notice exactly once

- **Type:** data-integrity
- **Priority:** critical
- **Covers:** AC-7
- **Performed as:** Run-job and email-capture hooks
- **Preconditions:** A bank-transfer salon with an unpaid first invoice
- **Steps:**
  1. For each boundary (due, due + 3 d, due + 10 d, due + 14 d) run the job at boundary - 1 s, then at the boundary twice
  2. Read the subscription state, `readOnlyAt` and the Owner's captured emails after each
- **Expected:** At - 1 s nothing changes; at due `past_due` and one due-date reminder; one reminder at + 3 d and at + 10 d; at + 14 d `read_only` and one read-only notice; no duplicate on any repeat.
- **Automation:** `qa/api/F03/S02-billing-cycle.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-014 A read-only salon keeps reads and exempt identity actions and refuses every other mutation

- **Type:** permissions
- **Priority:** critical
- **Covers:** AC-8
- **Performed as:** The read-only salon's Owner, a staff member it created, and a customer
- **Preconditions:** Salon driven to `read_only`; the mutating tenant routes enumerated from docs/api/openapi.json
- **Steps:**
  1. Call every GET; sign in, verify MFA, refresh, sign out, change password; customer export and deletion request
  2. Call every other tenant mutation (including staff creation) with valid bodies
  3. Read the affected records
- **Expected:** Reads and the exempt actions succeed; every other mutation answers 403 `salon_read_only` and changes nothing.
- **Automation:** `qa/api/F03/S02-entitlements.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-015 One salon's billing state, entitlements and invoices never reach another salon

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-8, AC-12
- **Performed as:** Owners of two provisioned salons; alpha Owner
- **Preconditions:** Salon A read-only with invoices; salon B active with invoices
- **Steps:**
  1. alpha and B perform a mutation while A is read-only
  2. B's Owner requests A's invoice id on B's host and on A's host; lists invoices
  3. Each Owner reads entitlements
- **Expected:** alpha and B mutations succeed; foreign invoice ids are 404 `not_found`; lists and entitlements contain only the salon's own records, having first shown each salon has invoices.
- **Automation:** `qa/api/F03/S02-entitlements.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-016 Paying every overdue invoice restores the salon; paying one of two does not

- **Type:** functional
- **Priority:** critical
- **Covers:** AC-9
- **Performed as:** Synthetic Admin; the salon's Owner
- **Preconditions:** A read-only salon with two overdue invoices
- **Steps:**
  1. Record a transfer for the first; Owner attempts a mutation
  2. Record the second; Owner attempts the mutation on the next request
- **Expected:** After one payment the salon is still `read_only` and the mutation is 403 `salon_read_only`; after both it is `active` and the mutation succeeds without running the job.
- **Automation:** `qa/api/F03/S02-billing-cycle.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-017 Staff creation beyond the seat limit is refused and a removal frees a seat

- **Type:** api
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** Owner of a salon on a 2-seat plan
- **Preconditions:** Owner is the first active staff
- **Steps:**
  1. Create one staff member; create another
  2. Remove a staff member; create again
- **Expected:** The over-limit creation is 409 `entitlement_exceeded` naming `staffSeats` and creates nothing; after removal the creation succeeds; entitlements usage matches the active staff count each time.
- **Automation:** `qa/api/F03/S02-entitlements.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-018 Two simultaneous creations for the last seat create exactly one staff member

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-10
- **Performed as:** Owner of a salon with one free seat
- **Preconditions:** Two distinct staff payloads
- **Steps:**
  1. Send both creations, eight copies each with distinct emails, at once
  2. List staff
- **Expected:** Exactly one creation succeeds; the rest are 409 `entitlement_exceeded`; active staff equals the limit.
- **Automation:** `qa/api/F03/S02-entitlements.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-019 A plan move is refused above the new limits and applies only to future invoices

- **Type:** data-integrity
- **Priority:** critical
- **Covers:** AC-11
- **Performed as:** Synthetic Admin; run-job hook
- **Preconditions:** A salon with 3 active staff, an issued open invoice, plans with 2 and 10 seats
- **Steps:**
  1. PUT plan to the 2-seat plan with the current version
  2. PUT plan to the 10-seat plan with a stale version, then the current version
  3. PATCH the amount; run the job at the next issue instant; read both invoices
- **Expected:** The 2-seat move is 409 `entitlement_exceeded` with no change; the stale version is 409 `conflict`; the move succeeds; the issued invoice keeps its plan and amount; the next invoice carries the new plan and amount.
- **Automation:** `qa/api/F03/S02-billing-cycle.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-020 Every staff role reads its own entitlements and only the Owner reads invoices

- **Type:** permissions
- **Priority:** high
- **Covers:** AC-12
- **Performed as:** Each staff role of a provisioned salon, a customer, another salon's Owner
- **Preconditions:** Salon table above
- **Steps:**
  1. Call each salon billing read as each actor
- **Expected:** Each cell matches the table; entitlements show plan key and name, all five limits and features, `usage.staffSeats` and the subscription state.
- **Automation:** `qa/api/F03/S02-entitlements.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-021 Every staff member sees the state banner and the Owner sees Plan and billing

- **Type:** functional
- **Priority:** high
- **Covers:** AC-12
- **Performed as:** Owner and Receptionist of a provisioned salon in the browser
- **Preconditions:** Clock-driven trial ending in 3 days + 1 s and in 3 days, past due, read-only
- **Steps:**
  1. Open workspace pages in each state as each role
  2. Owner opens /workspace/billing; Receptionist opens it directly
- **Expected:** No banner at 3 d + 1 s; the trial banner within 3 days; past-due banner with the due date; read-only banner with how to pay and mutating actions disabled with the reason; Owner's page shows limits, usage and invoices; Receptionist is refused.
- **Automation:** `qa/web/F03/S02-billing-screens.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-022 Support, staff and customers are refused every operator billing action on any host

- **Type:** permissions
- **Priority:** critical
- **Covers:** AC-13
- **Performed as:** Support operator, each staff role, a customer, and an Admin on non-console hosts
- **Preconditions:** Operator table above; a salon with an open invoice
- **Steps:**
  1. Call every operator billing action as each actor
  2. Read subscription, invoices and payments as Admin before and after
- **Expected:** Each cell matches the table and nothing changes.
- **Automation:** `qa/api/F03/S02-billing-access.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-023 Billing events are audited with actor or system and no secret reaches audit, logs, emails or errors

- **Type:** security
- **Priority:** critical
- **Covers:** AC-14
- **Performed as:** Database and log observer
- **Preconditions:** A salon taken through start, amount, method and plan change, invoice issue, failed and successful charge, transfer, past due, read-only and reminders
- **Steps:**
  1. Read platform audit for the salon
  2. Search audit, API and web logs, captured emails and error bodies for the provider key and for operator and Owner credentials (passwords, TOTP seed, tokens)
- **Expected:** One audit row per event with actor (Admin id or `system`), subject, application time and trace id; none of the searched secrets appears.
- **Automation:** `qa/api/F03/S02-billing-records.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-024 Console and workspace billing screens show every state with correct money formatting

- **Type:** functional
- **Priority:** high
- **Covers:** AC-15
- **Performed as:** Admin in the console; Owner in the workspace
- **Preconditions:** Salons in each state; LKR amounts. The web app has English messages only (decision 051), so no `si` or `ta` context is checked
- **Steps:**
  1. Open the subscription card, invoice list, record-transfer and plan-move sheets; submit invalid, conflicting and valid input; simulate unavailable
  2. Open Plan and billing as the Owner
- **Expected:** Loading, empty, validation, conflict, unavailable and success states; a status Badge for every subscription and invoice state; money formatted in the salon's currency and locale; the transfer is visible again through the API.
- **Automation:** `qa/web/F03/S02-billing-screens.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-025 Billing screens fit every width without clipping or horizontal scroll

- **Type:** responsive
- **Priority:** high
- **Covers:** AC-15
- **Performed as:** Admin; Owner
- **Preconditions:** A long invoice list and an empty one
- **Steps:**
  1. At each width in the matrix open the four console views and the workspace page and banner
- **Expected:** No horizontal scroll, nothing clipped, every action reachable; sheets follow the phone and desktop forms.
- **Automation:** `qa/web/F03/S02-billing-screens.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-026 Billing screens pass the automated scan and keyboard-only use

- **Type:** accessibility
- **Priority:** high
- **Covers:** AC-15
- **Performed as:** Admin; Owner, keyboard only
- **Preconditions:** As TC-F03-S02-025
- **Steps:**
  1. Run the axe scan on each view and state
  2. Complete a transfer and a plan move by keyboard
- **Expected:** No serious or critical violations; labelled fields; focus contained and returned; banner announced.
- **Automation:** `qa/web/F03/S02-billing-screens.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-027 A stopped or silent database is reported on billing screens within five seconds and recovers

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-16
- **Performed as:** Admin in the console; Owner in the workspace
- **Preconditions:** Database controlled through local-service.sh
- **Steps:**
  1. Stop the database; open the subscription card and Plan and billing
  2. Start it and mark when it accepts connections; reload
  3. Repeat with the database paused
- **Expected:** Unavailable within five seconds; recovery within five seconds of the database being back, without restart; no partial record.
- **Automation:** `qa/web/F03/S02-billing-screens.spec.ts` (written against the final contract and handoffs; not yet run)

### TC-F03-S02-028 A billing job interrupted after invoice issue resumes without a partial invoice or payment

- **Type:** resilience
- **Priority:** critical
- **Covers:** AC-16
- **Performed as:** Run-job and interrupt-job hooks
- **Preconditions:** A card salon at trial end; interrupt armed after invoice issue before the charge record
- **Steps:**
  1. Run the job (fails at the armed point)
  2. Read invoices, attempts and outbox
  3. Run the job again
- **Expected:** The failed run leaves no invoice, attempt or email (all committed together or not at all); the next run issues one invoice with one attempt and at most one payment.
- **Automation:** `qa/api/F03/S02-billing-cycle.spec.ts` (written against the final contract and handoffs; not yet run)
