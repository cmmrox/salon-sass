# F23-S05 Durable events between modules

## Objective

A module can tell other modules that something happened through a versioned event written to the
tenant-aware outbox in the same transaction as the change, and a subscriber in any module handles
each event once, for one salon at a time, with the outbox's retry and dead-letter rules. The one
event the code already publishes in memory, `identity.CustomerDataErased`, moves onto it. Nothing a
salon, a staff member or the operator does changes.

## Read first

The project documents that hold the rules for this stage. Developer and QA apply the shared
context-reading rule in `salon-sdlc` before their work.

- `docs/changes/CR-021-modular-extractable-architecture.md`: decision 3.
- `docs/engineering/backend.md`: "Events between modules", "Transactions, outbox and external
  calls", "Ownership of tables and paths", "Inside a module" (`event/`), "Logs, traces and meters",
  "Clean code", "Tests".
- `docs/architecture/multi-tenancy.md`: "Background jobs" (including the paragraph on events),
  "Adding a table", the ten rules.
- `docs/architecture/identity-and-access.md`: customer data erasure.
- `docs/operations/verification-evidence.md`.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| NFR-22 | ... offers its capabilities to other modules ... through versioned, durable events ... | part: the event mechanism |
| NFR-01 | No request can read or change another tenant's data ... | part: events and their consumers stay within one salon |
| NFR-04 | The design supports growth without re-architecture | part |

## Scope

API only (`apps/api`):

- **Publishing.** A `shared.outbox` operation publishes an event record: it joins the caller's
  writable tenant transaction (refusing outside one, as writing a message does today), stores the
  event's type name with its version (for example `identity.CustomerDataErased.v1`) and its JSON
  payload, the salon and the trace context. An event published in a transaction that rolls back does
  not exist.
- **Storage.** The outbox table gains what it needs to tell an event from a provider delivery, by an
  additive migration that works while the previous version runs (`backend.md`, "Persistence and
  migrations"). A new table records, per salon, which subscriber has processed which event; it is
  owned by `shared`, carries the prefix rule for new tables, has `tenant_id`, row-level security
  forced, and is in `module-ownership.yaml`.
- **Subscribing.** A module registers a subscriber for an event type in its `event/` folder. The
  dispatcher delivers an event to every subscriber of its type, each in its own transaction with the
  event's salon set; a subscriber's change and its processed record commit together, so a repeat
  delivery is ignored. The event counts as delivered when every subscriber has processed it. A
  failing subscriber is retried with the outbox's backoff and goes dead after the outbox's maximum
  attempts, without blocking other events or repeating subscribers that succeeded. A suspended
  salon's events wait, as its messages do.
- **Versioning.** An event record lives in the publishing module's root package; its type name
  includes a version; the payload is read back into the record the subscriber names. An event type
  with no subscriber is delivered at once (nothing to do) and removed by the outbox's retention.
- **`CustomerDataErased`** is published through the outbox instead of Spring's in-memory publisher,
  and its existing local-profile consumer becomes a subscriber. Its payload holds only ids, never
  personal data.
- **Observability.** Event deliveries carry `tenant.id` and continue the publishing request's trace;
  the outbox meters distinguish events from provider deliveries.
- **Architecture rule**, with a fixture: an event record published through the outbox is a record in
  its module's root package.
- **Clean code** in everything touched; **documentation hand-back** in `handoff.notes`.

## Out of scope

- Moving any in-process contract (`inprocess/`) onto events; they stay transaction-bound (decision
  057). New business events (for example `BookingConfirmed`) arrive with the features that publish
  them.
- A message broker or Spring Modulith's event publication registry (CR-021 decision 1).
- A separate worker process.

## Acceptance criteria

Each criterion is one observable outcome that a test can pass or fail.

- AC-1: An event published inside a committed tenant transaction is delivered to every subscriber
  of its type exactly once per subscriber, with that salon set; an event published in a transaction
  that rolls back is never delivered; publishing outside a writable tenant transaction is refused.
  (NFR-22)
- AC-2: When a delivery is repeated after a subscriber's change committed (for example a lease
  expired before the outbox recorded the outcome), the subscriber's change is not applied twice.
  (NFR-22)
- AC-3: A subscriber that fails is retried with the outbox's backoff and the event goes dead after
  the maximum attempts with its last error, while another subscriber of the same event that
  succeeded is not run again and other events keep flowing. (NFR-22)
- AC-4: A subscriber handling salon A's event can read and change only salon A's rows (proved with
  Hibernate's filter alone and with row-level security alone), and the processed-event table refuses
  another salon's rows under row-level security. (NFR-01)
- AC-5: A suspended salon's events are not delivered until it is active again. (NFR-22)
- AC-6: Erasing a customer's data publishes `identity.CustomerDataErased.v1` through the outbox,
  whose payload contains ids only, and its local-profile subscriber removes the captured messages as
  it did before, after the erasing transaction commits rather than inside it. The identity erasure
  integration tests pass, with exactly three assertions in `CustomerProfileIntegrationTest` narrowed
  to keep their meaning under the asynchronous design (decision 063): the two outbox counts count
  identity's delivery channels (`sms`, `whatsapp`) as the class's other assertions do, and the
  capture removal is awaited for a bounded time after the response. No other assertion changes.
  (NFR-22, NFR-01)
- AC-7: The architecture tests fail on a fixture event record outside its module's root package and
  pass for the application; the new table is in the ownership map with the module prefix rule
  satisfied. (NFR-22)
- AC-8: Behaviour is unchanged elsewhere: every test before this stage still passes with unchanged
  assertions; the OpenAPI document and generated client are byte-identical; provider deliveries
  (SMS and email capture locally) work as before. (NFR-04)

## Design notes

- Reuse the dispatcher's leasing, backoff, circuit and shutdown handling rather than a second loop.
  An event's subscribers run in-process, so no provider breaker applies to them.
- Serialise the payload with the application's strict JSON settings; reading back into the record
  must fail loudly on an unknown or missing field rather than guess.
- The migration is additive: a new nullable or defaulted column and a new table, so the previous
  version keeps writing provider messages unchanged.

## API and data changes

No endpoint changes. One migration: the outbox table gains the event discriminator and type name
(additive), and a new `shared`-owned table records processed events per salon and subscriber, with
row-level security forced and grants to the application role only for the operations it needs.

## UI and UX

Not applicable for this stage.

## Risks and test focus

- **Tenant leakage in a subscriber** (`tenant-isolation`): AC-4, each guard alone.
- **Duplicate or lost effects** (`data-integrity`, `concurrency`): AC-1 to AC-3, including two
  dispatcher instances claiming the same event.
- **Migration compatibility** (`data-integrity`): the previous version writing provider messages
  against the migrated table.
- **Personal data in an event payload** (`security`): AC-6.

## Verification commands

The developer gates, run from the repository root. Copy them into `stage.yaml` `verification.commands`.

```bash
cd apps/api && ./mvnw -q verify
pnpm contracts:check
```

## Done criteria

- The developer has checked every acceptance criterion on the running product and recorded it in
  the handoff.
- The verification commands are green.
- Any difference from `docs/engineering/backend.md` "Events between modules" is listed in
  `handoff.notes`.
