# F01-S04 Platform services: errors, time, audit trail, background jobs and observability

## Objective

After this stage, the services every later feature builds on are in place and can be seen working
from outside the API. Errors have one complete format. Times are stored in a universal form and
shown in the salon's own time zone. A salon's sensitive changes can be written to an append-only
audit trail. Anything that leaves the system goes through an outbox, which delivers with retry,
time limits and a circuit breaker. Recurring work runs salon by salon. Every log line and trace says
which salon it was for. Local test hooks (a test clock and a probe with a provider fake that can be
told to fail) let QA drive all of it through the API.

## Read first

- `docs/engineering/backend.md`: modules (all of this is `shared`), API conventions (problem
  details, error codes), persistence and migrations (backward-compatible migrations), health and
  the limits on every database wait, virtual threads, "Transactions, outbox and external calls",
  security (what is never logged), tests
- `docs/architecture/multi-tenancy.md`: rules T-1, T-3, T-4, T-7 and T-8; "How the tenant reaches
  the database" (the `SECURITY DEFINER` pattern of `tenant_by_slug`, resolving the salon before the
  transaction, the nil UUID); "Adding a table"; "Background jobs"; "Caches, files, search and logs";
  "Tests you must write"
- `docs/architecture/tech-stack.md`: the stack, and the rule for adding a dependency
- `docs/operations/environments-and-releases.md`: the local environment, its scripts, where logs go,
  restarting after a rebuild, local-only behaviour
- `docs/qa/test-plan.md`: the synthetic salons; "Time, messages and payments" (this stage provides
  the test clock hook)
- `docs/changes/CR-001-error-codes-in-the-contract.md`: approved, and built here
- `docs/decisions/DECISION_LOG.md`: decisions 021 and 029
- QA, at the feature pass: `docs/qa/test-types.md`, `docs/qa/automation.md`

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| BKG-15 | All times are stored in a universal form and shown in the salon's time zone | In part: the salon's time zone, the rules for converting, and showing a stored time in it. Each later feature applies them to its own times |
| NFR-15 | A failure of a messaging or payment provider degrades one function, not the system; work is retried | In part: the outbox, retry, time limits, concurrency limits and the circuit breaker, proved with the probe's fake provider. The real providers arrive with F08 and F11 |
| NFR-16 | Every log line, trace and error carries the tenant, and operators can see health per tenant | In part: the API's logs, traces and errors, and the meters for message failures and job delays. Per-salon health for operators is F03; shipping logs, traces and meters somewhere and alerting on them is F01-S05; the web app's own logs are F01-S05 |
| NFR-10 | Sensitive actions are logged immutably with actor, time, tenant and before-and-after values | In part: the audit trail and how it is written. Each sensitive action writes its entry in the feature that adds the action; the 24-month retention is a later release |
| NFR-01 | Tenant isolation | In part: the two new tenant tables, jobs that run salon by salon, and logs tagged by salon |

## Scope

**API (`apps/api`, module `shared` unless stated)**

- **Errors.** Every problem details body also carries `traceId`, the request's trace id. A request
  body that fails validation answers 400 `bad_request` with `errors`, a list of
  `{ "field", "detail" }` naming each field that failed. The contract lists every error code the API
  returns, from the one list in `ErrorCode` (CR-001), with a test that no code can be returned that
  is not listed.
- **Time.** The API runs in UTC whatever the machine's time zone is, and talks to the database in
  UTC. Every salon has a time zone (an IANA name). The tenant context carries it, and one shared
  service converts between an instant and the salon's local date and time, with written rules for
  the hour that is skipped and the hour that repeats when clocks change. `GET /api/v1/salon` adds
  `timeZone`. Every time the application decides on (when something is due, when it expires, when
  it happened) comes from the application's one `Clock`, never from the database's `now()`.
- **Audit trail.** A tenant table `audit_entry` and one way to write to it, which joins the caller's
  transaction and refuses to run outside one. An entry records the salon, when, the actor (kind and
  id), the action, the subject (kind and id), an optional reason, the values before and after, and
  the trace id. Values under a key that names a secret are never stored. The application's role may
  only insert and read; no role may change an entry.
- **Outbox.** A tenant table `outbox_message`. Writing to it joins the caller's transaction, so a
  message exists only if the change that caused it was committed. A dispatcher delivers due
  messages through a provider interface looked up by channel. It works one salon at a time with
  that salon's context set (T-7), never holds a lock or a transaction across the provider call,
  passes the message id as the idempotency key, and retries with backoff until a limit, after which
  the message is `dead` with its last error. A message whose delivery was interrupted (the API
  stopped mid-call) is attempted again once its lease runs out. Two dispatchers, in one process or
  two, never deliver the same message at the same time.
- **External calls.** Every provider call has a time limit, a limit on how many run at once, and a
  circuit breaker per channel. While a channel's breaker is open its messages wait without being
  attempted and without using up their attempts; nothing else is slowed down.
- **Recurring jobs.** A scheduler for recurring work. A job runs at most once per occurrence across
  every running instance. A job over salon data runs one salon at a time, each in its own
  transaction with that salon set; one salon's failure is logged and does not stop the others. The
  first job is real: it removes messages delivered more than 30 days ago, salon by salon.
- **Logs.** The API logs one JSON object per line, with the salon's id as `tenant.id` whenever a
  salon is bound (a request on its hostname, a delivery or a job run for it), and `trace.id` and
  `span.id`. Each API request writes one line with its method, its path without the query string,
  its status and its duration.
- **Traces.** A span for each request, each delivery and each job run for a salon, carrying the
  salon's id as `tenant.id`. A W3C `traceparent` sent by the caller is continued. A message's
  delivery belongs to the trace of the request that wrote it. Spans are exported over OTLP when an
  endpoint is configured and are otherwise not exported.
- **Meters** for messages waiting, the age of the oldest message that is due, messages that went
  `dead`, deliveries by channel and outcome, and how late each job started.

**Local test hooks (`local` profile only)**

- `GET`, `PUT` and `DELETE` `/local/clock`: read the application's clock, set it to a given instant
  (it runs on from there), and return it to real time.
- `POST /local/probes` on a salon's hostname writes, in one transaction, one outbox message on the
  channel `probe` and one audit entry (`probe.created`). `GET /local/probes/{id}` shows that
  message's state and its audit entries. The probe's fake provider does what the probe asked:
  fail its first `failures` attempts, take `delayMs` to answer, or `abort` the transaction after
  writing. It counts the calls it receives for each message id.
- `/actuator/metrics` exposed in the `local` profile.
- Outside the `local` profile, everything under `/local/` and `/actuator/metrics` answers 404
  `not_found`. None of it appears in the OpenAPI document.

**Database (`apps/api/src/main/resources/db/migration`)**

- `tenant.time_zone`, required, defaulting to `Asia/Colombo`, refusing a name that is not a time
  zone. The application learns it with the salon's id and status.
- `audit_entry` and `outbox_message`, each following "Adding a table" in full.
- Whatever platform-level bookkeeping the scheduler and the dispatcher need, reached by the
  application's role only through `SECURITY DEFINER` functions, as `tenant_by_slug` is.
- The local seed gives `alpha` and `beta` `Asia/Colombo` and `gamma` `Europe/London`.

## Out of scope

- Sending a real message or taking a payment: F08 and F11. The only provider in this stage is the
  probe's fake.
- Writing audit entries for sign-ins, refunds, role changes and the other sensitive actions: each
  feature that adds the action. Showing the audit log to an owner: the feature that adds the screen.
- Deleting audit entries after 24 months (NFR-10): a later release.
- Per-salon health for operators (NFR-16, PLT): F03.
- Where logs, traces and meters are shipped, dashboards and alerting: F01-S05.
- The web app's own logs and traces, and passing `traceparent` from the web app to the API:
  F01-S05, which decides how the web app is served outside the local machine.
- A capture of outgoing messages (one-time codes, confirmations): the first feature that sends
  them.
- Changing the time zone of a salon through an endpoint: F03 and F04.

## Acceptance criteria

All on the local environment, on the API's port. "A probe" is `POST /local/probes` on that salon's
hostname. Hostnames and ids are the synthetic salons' of `docs/qa/test-plan.md`.

- AC-1: Every error answer of the API (404, 400, 403, 405, 406, 415, 500, 503) is problem details
  with `code` and a `traceId` of 32 lowercase hex characters, equal to the `trace.id` of that
  request's log lines. A request carrying a valid `traceparent` gets that trace id back in the body.
  No body carries a stack trace, a class name or the input it refused. (NFR-16)
- AC-2: A probe whose body fails validation (`failures` negative, above 50 or not a number;
  `delayMs` negative or above 60000; a body that is not JSON) answers 400 `bad_request`, with
  `errors` naming each failing field where there is one, and writes nothing. (NFR-15)
- AC-3: The `code` enumeration of the OpenAPI document's problem details is exactly the set of codes
  the API can return, `salon_suspended` included, and `pnpm contracts:check` passes. (CR-001)
- AC-4: `GET /api/v1/salon` returns `timeZone`: `Asia/Colombo` for alpha and beta, `Europe/London`
  for gamma. With the API started under a machine time zone other than UTC (for example
  `TZ=Pacific/Chatham`), `GET /api/v1/meta`'s server time, a probe's `createdAt` and the values
  stored in the database are the same instants as under UTC. (BKG-15)
- AC-5: With the test clock set to `2026-03-29T00:30:00Z`, a probe on gamma shows `createdAt`
  `2026-03-29T00:30:00Z` and `createdAtLocal` `2026-03-29T00:30:00+00:00` (London, before the
  change). Set to `2026-03-29T01:30:00Z`, it shows `2026-03-29T02:30:00+01:00`. `createdAtLocal`
  always writes its offset as `+HH:MM`, never `Z`. On alpha at
  `2026-10-12T20:00:00Z` it shows `2026-10-13T01:30:00+05:30`, the next day. `DELETE /local/clock`
  returns the clock to real time. (BKG-15)
- AC-6: A probe with `failures: 0` is delivered: `GET /local/probes/{id}` reaches `sent` within 5
  seconds, with one attempt and one call to the provider. Its audit entry has the salon's
  `createdAt`, the action `probe.created`, the values after, and the request's trace id. (NFR-15,
  NFR-10)
- AC-7: A probe with `failures: 2` is attempted, fails, and waits: after each failure
  `nextAttemptAt` is later than the last by the configured backoff, measured on the test clock, and
  advancing the test clock past it causes the next attempt within 5 seconds. It is `sent` after its
  third attempt. A probe with `failures` above the attempt limit ends `dead` with its last error
  and stops being attempted, and the `dead` meter rises by one. (NFR-15)
- AC-8: A probe with `abort: true` answers 500 `internal_error`, and afterwards there is no outbox
  message and no audit entry for it in the database. A message exists only when its change was
  committed. (NFR-15, NFR-10)
- AC-9: A probe whose `delayMs` is longer than the provider time limit is counted as a failed
  attempt when the limit runs out, not when the provider would have answered. Meanwhile, with 20
  such probes in flight, `GET /api/v1/salon` on alpha, beta and gamma answers 200 within one second
  each. (NFR-15)
- AC-10: After the configured number of consecutive failures on the `probe` channel, its breaker
  opens. A further probe waits `pending` with zero attempts and no calls to the provider until the
  breaker's wait has passed on the test clock, and is then delivered. `GET /api/v1/salon` and
  `GET /actuator/health` answer normally throughout. (NFR-15)
- AC-11: A probe whose provider call is in progress when the API is stopped (`local-service.sh stop
  api`) is not lost. After `local-service.sh start api` and the lease's expiry on the test clock, it
  is attempted again with the same idempotency key and ends `sent`, with no manual repair. With
  PostgreSQL stopped and started again during delivery, the same holds. (NFR-15)
- AC-12: 100 probes with `failures: 0`, spread evenly over alpha, beta and gamma and sent together,
  are each `sent` with exactly one call to the provider. Each delivery's log lines carry the
  `tenant.id` of the salon whose probe it was. (NFR-15, NFR-01)
- AC-13: A probe made on alpha is answered 404 `not_found` by `GET /local/probes/{id}` on beta, with
  the same body as an id that does not exist. In the database, as the application's role: a
  transaction with no salon set sees no row of `audit_entry` or `outbox_message`; one set to alpha
  sees none of beta's; an `UPDATE` or `DELETE` of an `audit_entry` is refused; the catalogue check
  of F01-S02 holds for both new tables. (NFR-01, NFR-10)
- AC-14: Every API log line written while handling a request on a salon's hostname carries that
  salon's id as `tenant.id`, and a request on a hostname with no salon carries none. Each request
  writes one line with its method, its path without the query string, its status and its duration.
  No log line carries a request body, a query string, a `Cookie` or an `Authorization` header.
  (NFR-16, NFR-01)
- AC-15: A delivered message's age passes 30 days on the test clock, and the cleanup job's next run
  removes it, salon by salon: alpha's old messages are gone, beta's newer ones remain, and the job's
  log lines carry each salon's `tenant.id`. (NFR-01, NFR-16)
- AC-16: Started in a profile other than `local`, the API answers 404 `not_found` to every path
  under `/local/` and to `/actuator/metrics`, and the OpenAPI document mentions neither. (NFR-07)

## Design notes

- **Where things live.** All of this is in `shared`; `platform` keeps the tenant registry. No
  business module is touched except `branding`, for `timeZone` on `GET /api/v1/salon`. The local
  hooks are separate classes that only load in the `local` profile, and the architecture tests keep
  them out of everything else.
- **The dispatcher learns what is due, across salons, only through a `SECURITY DEFINER` function**
  that returns message ids and their salon ids and nothing else. It then handles each message in a
  transaction with that salon set, claiming it with a lease (`FOR UPDATE SKIP LOCKED` or an
  equivalent), commits, calls the provider with no transaction open, and records the outcome in a
  new transaction. Due times compare with the application's clock, passed in as a parameter.
- **The scheduler's bookkeeping** (which job ran when, which instance holds it) is a platform table
  the application's role reaches only through `SECURITY DEFINER` functions, in the same way. A job
  over salon data asks for the ids of active salons the same way.
- **Defaults, as settings under `salon.`:** dispatcher poll every second; a lease of 60 seconds;
  backoff from 30 seconds, doubling, up to 1 hour; 10 attempts in all; a provider time limit of 10
  seconds; 4 calls at once per channel; the breaker opens after 5 consecutive failures and waits 60
  seconds; delivered messages are kept 30 days; the cleanup job runs hourly. Tests may shorten them.
  Report the final values in the handoff.
- **Converting local time:** in the hour that clocks skip, a local time moves forward by the length
  of the gap; in the hour that repeats, the earlier of the two instants is used. Unit tests with a
  fixed clock for Colombo (a half-hour offset, no changes) and London across both changes of 2026.
- **The time zone column** is added in a backward-compatible way: the previous version of the
  application keeps working against the migrated database. If changing `tenant_by_slug` would break
  it, add a function instead.
- **Logs** use Spring Boot's structured logging; the developer tests keep their quiet configuration.
  Never log a request body, a query string, cookies or authorisation headers. The local environment
  writes the API's log to `.local/logs` as now, now as JSON.
- **Tracing** uses what Spring Boot 4 provides for OpenTelemetry. A new dependency is reported in
  the handoff, and the lead adds it to `docs/architecture/tech-stack.md`.
- **Graceful shutdown:** on stop, the dispatcher and the scheduler stop claiming new work and let
  calls in flight finish within the shutdown's limit. What they could not finish is recovered by the
  lease (AC-11).
- **Developer tests that ship with the stage:** unit tests for the backoff, the breaker, the time
  conversion and the secret-key filter (fixed clock); integration tests against PostgreSQL for the
  outbox's atomicity, two dispatchers racing for the same messages, the lease after an interrupted
  delivery, a job over two salons where one salon fails, and the two-tenant isolation cases of
  `docs/architecture/multi-tenancy.md` for both new tables; a test that every `ErrorCode` is in the
  contract and that each problem body has a `traceId`; a test that a log line written inside a salon's
  request carries its id.

## API and data changes

| Method and path | Change |
| --- | --- |
| Every error answer | Adds `traceId`; a validation failure adds `errors: [{ field, detail }]` |
| `GET /api/v1/salon` | Adds `timeZone`, an IANA name |
| `GET`, `PUT`, `DELETE /local/clock` | Local profile only, not in the contract. `PUT` takes `{ "instant" }`; each answers `{ "now", "fixed" }` |
| `POST /local/probes` | Local profile only, not in the contract. Takes `{ "failures", "delayMs", "abort" }`, all optional; answers 201 with the probe |
| `GET /local/probes/{id}` | Local profile only, not in the contract. `{ id, status, attempts, providerCalls, nextAttemptAt, lastError, createdAt, createdAtLocal, sentAt, audit: [{ action, occurredAt, actorKind, after, traceId }] }` |

| Table | Kind | Notes |
| --- | --- | --- |
| `tenant` | Platform, changed | Adds `time_zone`; the seed sets it per salon |
| `audit_entry` | Tenant | Insert and read only for the application's role; no update by any role |
| `outbox_message` | Tenant | Channel, payload, status (`pending`, `sending`, `sent`, `dead`), attempts, next attempt, lease, last error, trace context, times |
| Scheduler bookkeeping | Platform | Reached only through `SECURITY DEFINER` functions |

New migrations follow `docs/engineering/backend.md` and "Adding a table" in full.

## UI and UX

Not applicable for this stage.

## Risks and test focus

| Risk | Test type and why |
| --- | --- |
| A delivery or a job runs with no salon or the wrong salon, or one salon's job reads another's data | `tenant-isolation` (AC-12, AC-13, AC-15) |
| A message is lost, sent twice, or sent for a change that was rolled back | `resilience` and `data-integrity`: restart and database outage mid-delivery, abort after writing (AC-8, AC-11) |
| Two dispatchers deliver one message twice | `concurrency` (AC-12) |
| A slow or failing provider slows the whole API | `resilience` (AC-9, AC-10) |
| Times shift with the machine's time zone, or are wrong across a change of clocks or midnight | `data-integrity` (AC-4, AC-5) |
| Logs or errors reveal a body, a query, a header or another salon | `security` (AC-1, AC-14) |
| The test hooks reach an environment other than local | `security` and `build` (AC-16) |
| The contract drifts from the codes the API returns | `api` (AC-3) |

`functional`, `responsive`, `accessibility`, `branding`, `permissions`, `payment-integrity`,
`mobile` and `load` do not apply: there is no screen, no role and no response-time target.

## Verification commands

The developer gates. Each line is run in its own shell from the repository root. They are also in
`stage.yaml` `verification.commands`.

```bash
cd apps/api && ./mvnw -q verify
pnpm install --frozen-lockfile
pnpm -r lint
pnpm -r typecheck
pnpm -r test
pnpm --filter @salon/web build
pnpm contracts:check
qa/scripts/sdlc selftest
```

## Done criteria

- The developer has checked every acceptance criterion on the running product and recorded it in
  the handoff, with the settings' final values.
- The verification commands are green locally.
- Before closing, the lead has corrected `docs/engineering/backend.md` (time, outbox, jobs, logs,
  traces, the error body), `docs/architecture/multi-tenancy.md` (jobs and the new functions),
  `docs/architecture/tech-stack.md` (any new dependency), `docs/operations/environments-and-releases.md`
  (the local hooks, JSON logs) and `docs/qa/test-plan.md` (how to use the test clock and the probe).
