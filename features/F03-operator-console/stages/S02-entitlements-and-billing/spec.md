# F03-S02 Entitlements and subscription billing

## Objective

Every salon has a subscription: a 14-day trial, then monthly invoices paid by a recurring charge or
a recorded bank transfer, with reminders and a grace period before the salon becomes read-only.
The backend enforces the plan's limits, and the salon's Owner sees the plan, its usage, the
subscription state and the invoices in the workspace.

## Read first

- `docs/brd/requirements.yaml`: PLT-04 and PLT-05.
- `docs/architecture/operator-console.md`: "Subscriptions, invoices and entitlements (F03-S02)"
  holds every term and rule of this stage, plus the S01 rules it extends (authority, hostname,
  platform connection, idempotency, lists, audit).
- `docs/architecture/multi-tenancy.md`: access states of a salon, platform data and the tests for
  every new endpoint.
- `docs/architecture/identity-and-access.md`: permission matrix, staff creation and audit reads.
- `docs/engineering/backend.md`: modules, transactions, jobs, outbox, clock, errors and contract.
- `docs/engineering/web.md`: server data access, BFF rules and "Styling" (CR-016, CR-017).
- `docs/design/ui-ux-standards.md` and `docs/design/prototype/index.html`: Salon-mode cards, lists,
  status labels and the width matrix.
- `docs/decisions/DECISION_LOG.md`: decisions 027, 032 and 049.
- `docs/qa/test-plan.md` and `docs/qa/test-types.md`: test clock and fake providers.
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| PLT-04 | Plans define features and limits (staff seats, branches, message allowance, custom domain, dedicated app). Entitlements are enforced by the backend and reflected in every client. | Full for the web workspace and console: staff seats enforced now, the other limits through the facade by the features that create those things (F04, F11, F16); the customer app reflects them in F15. |
| PLT-05 | Subscription billing: trial period, recurring charge or recorded bank transfer, invoices, reminders for failed or overdue payment, a grace period, then a read-only suspended state. | Full, with a local fake billing provider; a real provider waits for D-06. |

## Scope

- API (operator): subscription detail per salon; start a subscription for an unbilled salon; set
  the monthly amount and payment method; move a salon to another plan; invoice list/detail;
  record a bank transfer. Salon creation gains a required monthly amount and payment method and
  starts the trial in the same transaction.
- API (salon): the salon's entitlements, usage and subscription state for its staff; its invoices
  for the Owner. Read-only enforcement on every tenant mutation. Staff-seat enforcement on staff
  creation.
- Billing: an idempotent platform job for invoice issue, charge attempts, state changes and
  reminders; the billing provider interface with a local fake and its signed event intake; the
  `email` outbox channel with a local capturing fake.
- Web: console subscription card on the salon detail, invoice list, record-transfer and plan-move
  sheets; workspace "Plan and billing" page for the Owner and a state banner for every staff member.
- Local hooks (loopback-only, `local` profile, outside OpenAPI; see `operator-console.md`, "Local
  test hooks"): fake provider outcome and signed events, email capture, running the billing job
  now, an interrupted-job fault, an unbilled legacy salon fixture and disabling a fixture operator.

## Out of scope

- Published tiers, list prices, discounts, tax, proration, refunds of subscription payments, card
  entry screens and a real billing provider: D-06 is open.
- Suspension, reactivation and closure by an operator: F03-S03. Support sessions: F03-S04.
- Enforcing branches, message allowance, custom domain and dedicated app at the point of use:
  F04, F11 and F16 call the facade. Customer app display: F15.

## Acceptance criteria

- AC-1: An Admin creating a salon must supply a monthly amount and payment method; the new salon
  is `trialing` with a trial ending exactly 14 days after its creation instant, and a missing,
  negative, fractional or above-bound amount or unknown method is a field error with no salon. (PLT-05)
- AC-2: An unbilled existing salon shows "no subscription" in the console and workspace and works
  normally; after an Admin starts its subscription it is `trialing` for 14 days. Starting a second
  subscription is 409 `invalid_state`. (PLT-05)
- AC-3: When the test clock crosses trial end, exactly one invoice is issued with the next number,
  the plan at issue, the amount and currency, the first monthly period and a due instant 7 days
  later; later months issue one invoice each on the anchored day, including a 31st anchor in a
  shorter month, and running the job repeatedly or concurrently issues no duplicate. (PLT-05)
- AC-4: For a card subscription, a successful fake charge pays the invoice and leaves the salon
  `active`; a failed charge leaves it open, sends one failed-charge email to the Owner contact and
  is retried on each reminder day. (PLT-05)
- AC-5: A repeated provider event id, an event for an already-paid invoice, an unsigned or wrongly
  signed event and a late success after a manual payment each pay an invoice at most once and
  record no second payment; invalid signatures are refused without effect. (PLT-05)
- AC-6: An Admin records a bank transfer with reference, exact amount and received date against an
  open invoice; it becomes `paid`. A partial or excess amount or a future date is a 400 field error,
  a paid invoice is 409 `invalid_state` and an unknown invoice id is 404, each without change, and repeating the same Idempotency-Key and
  input returns the original result with one payment and one audit. (PLT-05)
- AC-7: An invoice unpaid at its due instant makes the subscription `past_due`; reminder emails go
  to the Owner contact once each on the due date, 3 days and 10 days after it; at 14 days after the
  due instant the salon becomes `read_only` and the read-only notice is sent once. One second
  before each boundary nothing has changed. (PLT-05)
- AC-8: In a read-only salon every staff and customer read still works, as do sign-in, MFA,
  renewal, sign-out, password change and customer privacy rights; every other tenant mutation,
  including staff creation, answers 403 `salon_read_only` and changes nothing. Another salon is
  unaffected throughout. (PLT-05)
- AC-9: Paying every overdue invoice returns a `past_due` or `read_only` salon to `active` on the
  next request, and mutations work again; paying one of two overdue invoices does not. (PLT-05)
- AC-10: Creating a staff member beyond the plan's staff-seat limit is 409 `entitlement_exceeded`
  naming the limit and creates nothing; removing a staff member frees a seat; two simultaneous
  creations for the last seat create exactly one. (PLT-04)
- AC-11: An Admin moves a salon to another plan; usage above the new plan's limits refuses the move
  with 409 `entitlement_exceeded` and no change; after a move the next invoice carries the new plan
  and a changed amount applies from the next invoice, never to an issued one. (PLT-04, PLT-05)
- AC-12: Every staff member of a salon can read its plan features and limits, staff-seat usage and
  subscription state from the API, and only its own; the workspace shows a banner to all staff for
  a trial ending within 3 days, `past_due` and `read_only`, and the Owner sees the "Plan and billing"
  page with limits, usage and invoices. Other roles get 403 on invoices. (PLT-04, PLT-05)
- AC-13: Support operators, every salon staff role and the customer principal are refused every
  operator billing action and read; operator billing routes on any host but the console are 404
  before side effects. Denied actions change nothing. (PLT-05)
- AC-14: Subscription start, amount, method and plan changes, invoice issue, payments, state
  changes and reminders are recorded in the platform audit with actor or `system`, subject, time and
  trace id; audits, logs, emails and errors contain no card data, provider secret or credential. (PLT-05)
- AC-15: The console shows the subscription card, invoice list and both sheets, and the workspace
  shows the banner and the Plan and billing page, with loading, empty, validation, conflict,
  unavailable and success states at every documented width, keyboard-only use, accessible labels
  and status Badges for every state; money is formatted in the salon's currency and locale. (PLT-04, PLT-05)
- AC-16: With a stopped or silent database, billing screens report unavailable within the five-second
  outage deadline and recover without restart; a billing job interrupted mid-run leaves no partial
  invoice or payment and resumes on the next run. (PLT-05)

## Design notes

Billing lives in `platform` on the platform connection; identity calls the public entitlements
facade inside its staff-creation transaction and locks so that the last-seat race has one winner.
The read-only check belongs with the tenant filter that already refuses suspended salons, with an
explicit allowlist of the exempt identity and privacy routes; a new mutating route is refused by
default. Use the application clock for every instant and the existing job scheduler and outbox.
Invoice issue, its charge attempt record and its outbox message commit together; the provider call
itself happens outside the transaction through the outbox, with the attempt's idempotency key.

## API and data changes

Operator routes are under `/api/v1/operator` and console-host-only, as in S01.

| Method/path | Request / result |
| --- | --- |
| `POST /salons` | Adds required `subscription:{monthlyAmount,paymentMethod}` |
| `GET /salons/{id}/subscription` | `{version,state,plan,monthlyAmount,currency,paymentMethod,trialEndsAt,currentPeriod,nextInvoiceAt,readOnlyAt}` or 404 for unbilled |
| `POST /salons/{id}/subscription` | Idempotency-Key; `{monthlyAmount,paymentMethod}` -> 201, unbilled salons only |
| `PATCH /salons/{id}/subscription` | `{version,monthlyAmount?,paymentMethod?}` -> 200 |
| `PUT /salons/{id}/plan` | `{version,planId}` -> 200 or 409 `entitlement_exceeded` |
| `GET /salons/{id}/invoices`, `GET /invoices/{id}` | Paginated summaries / detail with payments |
| `POST /invoices/{id}/payments` | Idempotency-Key; `{reference,amount,receivedOn}` -> 201 |
| `POST /api/v1/billing/provider-events` | Signed fake-provider event; platform host only, no operator session |

Salon detail gains `version` (optimistic concurrency for plan moves and S03 lifecycle actions);
a stale version is 409 `conflict`.

Salon routes, staff-authenticated on the salon's host: `GET /api/v1/salon/entitlements` ->
`{plan:{key,name},limits,features,usage:{staffSeats},subscription:{state,trialEndsAt,readOnlyAt}}`;
`GET /api/v1/salon/invoices` and `/{id}` for the Owner only. Register `salon_read_only`,
`entitlement_exceeded` and `invalid_state`. Generate the contract and client.

Append migrations for subscriptions, invoices with a unique number sequence and a unique
(subscription, period start), payments with unique provider event ids, charge attempts, reminder
records unique per (invoice, kind) and the registry's access state. Grants go to the platform role
only; the tenant role gets nothing on these tables.

## UI and UX

No billing screen exists in the prototype. Use the Salon-mode card, list and status grammar with
the shadcn/ui component map (CR-016, CR-017): Card for the subscription, Item rows or Table for
invoices, Badge for states, Sheet for record-transfer and plan-move, Field and Select in forms,
Alert for the workspace banner, Empty, Pagination, Skeleton and Spinner. No pricing cards or upsell.

| Screen | Route | Behaviour |
| --- | --- | --- |
| Salon subscription | `/console/salons/{id}` | Card with state, plan, amount, method, trial end, next invoice; Start subscription for unbilled |
| Invoices | `/console/salons/{id}/invoices` | Paginated list; open invoices offer Record transfer |
| Plan and billing | `/workspace/billing` | Owner only: plan, limits with usage, state, invoices; no payment entry |
| State banner | every workspace page | Trial ending, past due with due date, read-only with how to pay |

Read-only workspace screens disable their mutating actions with the reason, rather than letting
them fail. Messages go through next-intl in `en`, `si` and `ta`.

## Risks and test focus

- `time`: trial end, month anchors, due, reminder and read-only boundaries, one second either side,
  with the test clock; late and repeated job runs.
- `concurrency` and `idempotency`: duplicate invoices, double payment from events and transfers,
  the last staff seat.
- `security` and `permissions`: signed provider events, operator roles, staff roles, host binding.
- `tenant-isolation`: entitlements and invoices of one salon never visible to another; read-only on
  one salon never affects another.
- `data-integrity`: invoice numbering, immutability of issued invoices, payment exactness.
- `functional`, `responsive`, `accessibility`: console and workspace screens and banner states.
- `resilience`: database outage and an interrupted job.

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

- The developer has checked every acceptance criterion on the running product, with the test clock
  and fake provider, and recorded it in the handoff with synthetic data identity and evidence paths.
- The verification commands are green.
- The handoff lists the billing job schedule, local hooks and email capture so the lead can update
  the environment document.
- No price, tier or real provider is inferred; D-06 stays open.
