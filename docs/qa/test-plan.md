# Test plan

CR-010 platform scope: current web QA covers salon staff and operator interfaces only.
Customer browser sign-in, account and booking expectations are superseded. Retain direct
customer API coverage as mobile foundations; native UI QA begins only after M1-web release.


How this product is tested: the tools, where the automation lives, the data it runs on, the targets
it must meet, and what its severities look like here. QA tests a feature once, when all its stages
are closed; a stage is closed on the developer's own gates. The method (who tests what, what a pass
means, how to triage a failure) is in the `salon-qa` skill; this file is what is particular to this
product.

## Contents

- Tools
- Layout of `qa/`
- The synthetic salons
- Accounts
- Time, messages and payments
- Targets from the requirements
- Platforms
- Suites
- Severity examples

## Tools

| Tool | Tests | From |
| --- | --- | --- |
| Playwright | The web app in a browser, and the API | F01-S01 |
| k6 | Load, stress and concurrency | F01-S02 |
| Shell checks | What a browser or API test cannot do | F01-S01 |
| Maestro | The customer app on an emulator or device | Milestone `M2-mobile` |

Conventions for each are in `docs/qa/automation.md`.

## Layout of `qa/`

```
qa/
  web/<feature>/       Playwright browser tests            S02-slot-search.spec.ts
  api/<feature>/       Playwright API tests                S02-holds.spec.ts
  mobile/<feature>/    Maestro flows                       S03-book-in-app.yaml
  load/<feature>/      k6 load, stress and concurrency     S02-one-slot-many-customers.js
  checks/<feature>/    shell checks (tool: script)         S01-database-down.sh
  shared/              fixtures, salons, accounts, helpers, shared Maestro subflows
  package.json         QA's own package and lock file, separate from the product's workspace
  releases/<version>/  release manifests and results
  reports/             run output; never committed
  scripts/             the lifecycle tooling; the lead's, not QA's
```

- `<feature>` is the feature id: `qa/api/F07/`, not the feature's folder name.
- A file is named for its stage and what it proves. Feature journeys are named `journey-<name>`.
- `checks/` holds what a browser or API test cannot do: stopping and starting a service through
  the developer's scripts in `infra/scripts/`, reading the database, scanning the build for something a criterion requires
  (every string in a translation file, a contract check that fails on a stale file). A check never
  edits the repository; when it has to alter a file to prove a point, it works on a copy outside it.

## The synthetic salons

From stage F01-S02 onward, three synthetic salons exist in every local environment, with fixed ids
and hostnames. They are created by the product's own local seed, which the developer writes;
`qa/shared/` holds their names, ids and hostnames as constants that must match the seed. (F01-S01
has no salons; its cases say so.)

| Salon | Id | Local hostname | Use |
| --- | --- | --- | --- |
| `alpha` | `a1a1a1a1-0000-4000-8000-000000000001` | `alpha.localhost` | The salon under test. Name "Alpha Studio", the prototype's default brand colour `#D6086A` |
| `beta` | `b2b2b2b2-0000-4000-8000-000000000002` | `beta.localhost` | The other salon, for every isolation test. A dark brand colour (`#1F2937`) and a long name: "Beta Hair, Beauty & Wellness Lounge of Colombo Seven" |
| `gamma` | `c3c3c3c3-0000-4000-8000-000000000003` | `gamma.localhost` | An edge salon: name "Gamma", a light brand colour (`#FDE68A`); from F01-S04 the time zone `Europe/London` (alpha and beta are `Asia/Colombo`); from later stages no cover image and most options off |

The ids and slugs are fixed and the same in every local environment; the names and colours may be
changed by later stages that own branding, and `qa/shared/tenants.ts` follows them. The hostnames use
the local platform domain `localhost` and the API's or web app's port.

Any test that touches a salon's data needs at least two salons. A suite that passes because two
salons never happened to collide has proved nothing about isolation.

## Accounts

One account per role per salon (Owner, Manager, Receptionist, Professional, Accountant), plus
customers and an operator user. Credentials live in `qa/.env.local`, which is never committed;
`qa/.env.example` lists the keys. Use the least-privileged role a case needs. Sign in through the
shared helper, never by typing a password into a test.

## Time, messages and payments

- **Hooks:** the test clock, the capture of outgoing messages and the payment sandbox are provided
  by the developer, in local and test configurations only, by the stage that first needs each one.
  That stage's spec defines the hook, and the lead adds how to use it here.
- **The test clock** (F01-S04), on the API's port, local profile only: `GET /local/clock` reads it;
  `PUT /local/clock {"instant": "2026-03-29T01:30:00Z"}` sets it, and it runs on from there;
  `DELETE /local/clock` returns to real time. It is the application's one clock, for every salon,
  so a test that sets it must put it back, and tests that set it cannot run in parallel. Instants a
  probe shows are truncated to the second.
- **The probe** (F01-S04), on a salon's hostname: `POST /local/probes {"failures", "delayMs",
  "abort"}`, all optional, writes one outbox message on the channel `probe` and one audit entry
  (`probe.created`) in one transaction, and answers 201 with the probe. Its fake provider fails the
  first `failures` attempts (0 to 50), takes `delayMs` to answer (0 to 60000; above the 10 s time
  limit it counts as a failure), and `abort: true` aborts the transaction after writing (500).
  `GET /local/probes/{id}` shows the message's state, attempts, provider calls, times in UTC and in
  the salon's zone, and its audit entries. To interrupt a delivery with a graceful stop of the API,
  use a `delayMs` between the shutdown grace (5 s) and the time limit (10 s); a shorter call simply
  finishes. Waits (backoff, breaker, lease, retention) are crossed by moving the test clock.
- **Meters**, local profile only: `GET /actuator/metrics/salon.outbox.dead` and the others listed in
  `docs/engineering/backend.md`.
- **Time:** bookings depend on the clock. Use the test clock hook to fix "now"; never write a test
  that only passes on a weekday or before noon. Check behaviour across midnight and for a salon in
  another time zone.
- **Messages:** read one-time codes and outgoing messages from the local capture, never from a phone.
- **Staff identity fixtures** (F02-S01): on a fixed synthetic salon's hostname,
  `POST /local/identity/seed` takes `{accounts:[{email,mobile,password,role,totpSecret?,professionalGrant?}]}`
  and returns only `{accounts:number}`. Roles use uppercase names. Seeding is idempotent and
  preserves existing accounts; `DELETE /local/identity` resets that salon's synthetic identity
  records/rate state while preserving immutable audit and other product data. Both hooks are
  loopback-only, `local`-only and absent from OpenAPI; nonfixed salons are refused. Supplied fixture
  credentials/TOTP seeds stay in ignored environment or `.local` files with mode `0600`. Current
  developer fixtures are `.local/f02-staff-fixtures.json`; never print or commit their contents.
- **Brand colour** (CR-013): `infra/scripts/local-salon.sh colour <slug> <#RRGGBB>` sets a local
  salon's stored colour; check the sign-in and the workspace with a dark and a light colour, then
  put the seeded colour back (alpha `#D6086A`; read the others from `/api/v1/salon` first). A
  salon without a valid colour cannot be stored, so the `#D6086A` default is covered by unit tests.
- **Payments:** the PayHere sandbox only.
- **Customer code capture** (F02-S02), on the issuing salon Host: poll
  `GET /local/identity/captures/{challengeId}` until 200; 404 means it has not captured a live code.
  The capture contains `{challengeId,channel,code,attempt,deliveredAt}`, no phone. Read
  `GET /local/identity/deliveries/{challengeId}` for safe outbox status/attempt/error/timing metadata;
  states are `PENDING`, `SENDING`, `SENT`, `CANCELLED`, `DEAD`.
  `PUT /local/identity/providers/{sms|whatsapp}` takes `{failures:0..50,delayMs:0..60000}` and returns
  204, controlling subsequent attempts for that salon/channel. `DELETE /local/identity/customers`
  resets mutable customer/challenge/session/history/rate fixtures and captures/provider controls,
  preserving staff and immutable audit. All hooks are local-only, loopback-only, tenant-bound and
  contract-excluded; resets accept only alpha/beta/gamma. Audit is not empty after reset, so isolate
  a run/day or assert against a baseline. Code limits may be explicitly configured for a focused
  local daily-ceiling test; restore 3/10/20/1000 defaults afterwards and report overrides as such.
- What a test may never touch is in `docs/operations/environments-and-releases.md`.

## Targets from the requirements

| Requirement | Target |
| --- | --- |
| NFR-01 | No request can read or change another salon's data, through any endpoint, export, file or cache |
| NFR-05 | Simultaneous attempts on one slot produce exactly one booking |
| NFR-03 | Availability search under 500 ms at the 95th percentile; other reads under 300 ms |
| NFR-04 | The design holds at 2,000 salons, 20 staff each at the top end, 5 million bookings a year |
| NFR-12 | WCAG 2.2 level AA on customer surfaces and the workspace |

F02's local read baseline uses the three synthetic salons, five staff roles, 50 synthetic customers
and at least 100 audit entries per salon, with ten concurrent users. Record the actual audit totals;
immutable entries from earlier checks remain and are never deleted to manufacture this baseline.
Synthetic preparation may temporarily use an address ceiling of 200 to register the 50 customers
through real code sign-in, with distinct server-issued devices. Restore and verify the default
3/10/20/1000 ceilings with retained keys before the measured read phase; report preparation
overrides separately and clean mutable fixtures afterwards. One scenario exercises authenticated
current-principal, own-device, permitted staff/audit and own-profile reads, recording p95 separately
for each read against NFR-03's 300 ms target. Password hashing, code delivery and other mutations
are outside that read target. This bounded baseline is local feature evidence, not certification
of NFR-04's production scale. QA creates synthetic fixtures through local hooks or public APIs.

## Platforms

- **Web (`M1-web`):** salon owner/admin/staff workspace and operator console only. There is no
  customer booking site. Test the full width matrix in `docs/design/ui-ux-standards.md`, including
  staff access on phones and the workspace at 768.
- **Customer app (from `M2-mobile`):** Android is the primary platform for the launch market and
  every feature with a mobile surface passes there. iOS is run when a feature touches something that differs by
  platform, and before a release.

## Suites

- `smoke` for this product means: sign in, see the services, book, pay in the sandbox, see the
  booking on the salon's calendar. A few minutes in total.
- `regression` always includes every tenant-isolation case and every concurrency case on bookings.

## Severity examples

The definitions are in the `salon-qa` skill. In this product:

| Severity | Examples |
| --- | --- |
| `blocker` | One salon's data is visible to another; a customer is charged twice or the wrong amount; a booking is lost or duplicated; two bookings in one slot; the booking flow cannot be completed |
| `major` | Reschedule ignores the policy window; a Receptionist can refund; a reminder goes to the wrong customer; a hold does not expire |
| `minor` | The wrong message for an expired hold; the layout breaks at 360 wide on one screen; a report total is right but a label is wrong |
| `trivial` | Spacing, a typo |
