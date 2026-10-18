# Automation conventions

How this product's QA automation is laid out and written, tool by tool. The rules that make any
automated test trustworthy (one case per test, no retries, see it fail first) are in the `salon-qa`
skill, `references/automation-principles.md`; this file is what is particular to this product and
its tools.

## Contents

- Playwright: layout and projects
- Playwright: salons and sign-in
- Playwright: API tests
- Playwright: browser tests
- Shell checks
- k6: concurrency
- k6: load
- Maestro: the customer app
- Running
- Preparing a feature run

## Preparing a feature run

Before a long feature run, confirm the committed source and actual running build, API/web health,
the registered current contract, synthetic fixtures, private keys and provider configuration.
Keep credentials private. Check selectors on visible rendered states while developing automation;
a streamed hidden element can have matching text and zero layout size. Measure sticky CSS offsets
separately from an element's normal-flow rectangle.

Clock-driven F01 checks may leave future scheduler occurrences. Before F02 clock cases after
F01, use the existing local-reset/local-up workflow on the synthetic local database as F02's
cases describe; preserve private keys and all historical stack volumes. Record a new fixture
identity after reset. Required F02 nonlocal keys and current migrations must be present before
positive profile tests; negative missing-secret cases deliberately omit them.

Develop and exercise changed automation before committing its final version. A diagnostic file
run establishes only that file's behavior. The independent feature pass still runs every
registered automation entry through `qa/scripts/sdlc run`, with the recorded context described in
`docs/operations/verification-evidence.md`. Never edit certified source during a run.

## Playwright: layout and projects

One Playwright project under `qa/` covers both browser and API tests, so they share fixtures,
sign-in and data builders. QA creates it in stage F01-S01.

```
qa/
  package.json            QA's own package, with its own lock file (qa/pnpm-lock.yaml)
  playwright.config.ts    projects: api, web
  tsconfig.json           path alias to packages/api-contracts
  web/<feature>/*.spec.ts
  api/<feature>/*.spec.ts
  shared/                 fixtures and helpers; tenants.ts and accounts.ts from F01-S02, builders as needed
  .env.example            the keys qa/.env.local must provide
```

- `qa/` is not part of the product's pnpm workspace, so adding a test dependency never changes the
  product's lock file. Install with `pnpm --dir qa install --ignore-workspace`.
- Contract types come from `packages/api-contracts` through the path alias. Read them; never copy them.
- Project `api` runs `qa/api/**`, with no browser. Project `web` runs `qa/web/**`.
- Traces are off by default and never recorded around sign-in: a trace stores what was typed.
- Screenshots on failure only. `qa/scripts/sdlc run` sets `QA_REPORT_DIR`; because Playwright
  empties its output folder when it starts, each invocation writes to its own folder,
  `QA_REPORT_DIR/playwright/<invocation>/`.
- The product must already be running (`infra/scripts/local-up.sh`). The automation checks this
  first and stops with a line starting `ENVIRONMENT:` when it is not, so that a stopped product is
  never reported as a failing one.
- Start a test's title with its case id:

  ```ts
  test('TC-F07-S02-004 a hold by another customer hides the slot', async ({ api }) => { ... });
  ```

## Playwright: salons and sign-in

- Get hostnames and ids from `qa/shared/tenants.ts` (`alpha`, `beta`, `gamma`). Never write a
  hostname, a port or a tenant id into a test.
- Sign in through the shared fixture, which takes a salon and a role and returns an authenticated
  context. Credentials are read from `qa/.env.local`.
- Arrange data through the API with the builders in `qa/shared/builders/`.

## Playwright: API tests

Use Playwright's request context through `qa/shared/api-client.ts`.

- Assert the status, the error `code`, and the body against the contract types. Do not assert on
  human-readable messages.
- Isolation tests follow one shape: create the record as `beta`, assert `beta` can read it, then
  assert `alpha` cannot read, list, update or delete it, and gets "not found" each time. The first
  assertion is what stops the test passing when nothing was created at all.
- Permission tests loop over every role with an explicit expected result per role. No role is left
  out because it is "obviously" allowed.
- For an action that carries an `Idempotency-Key`, send the same request twice and assert one effect.

## Playwright: browser tests

- Select by role and accessible name first, by `data-testid` second. Never by CSS class, by text
  that a translation will change, or by position.
- Check the result where the user would see it and, for anything that changes data, once more
  through the API.
- Responsive cases set the viewport explicitly to each width in the matrix in
  `docs/design/ui-ux-standards.md` and assert no horizontal overflow as well as the presence of
  each control.
- Accessibility cases run the automated scan and fail on serious or critical violations.
- Branding cases run the same steps for all three salons and compare each with that salon's own
  brand data from the API.
- Playwright ships a planner, a generator and a healer agent (`npx playwright init-agents`). They
  are optional helpers for exploring a screen and drafting tests. The cases still come from the
  acceptance criteria, and the healer may repair a locator that drifted but must never change an
  assertion or skip a test.
- Shared helpers read theme values by their shadcn/ui variable names (`--background`,
  `--foreground`, `--primary`, `--ring`, `--destructive` and so on, `docs/engineering/web.md`,
  "Styling"), never by the retired pre-CR-016 token names.
- The source scan treats a `src/components/ui/` primitive that spreads its `props` onto the element
  as forwarding the caller's `data-testid`; the test id is then required where the primitive is
  used, not inside it.
- A modal (the shadcn/ui Sheet or Dialog) hides the page behind it from assistive technology while
  it is open, so a capture or assertion taken during it waits for the dialog, not for the page's
  `main` landmark.
- **Comparing with the accepted build** (for a change meant to be invisible, such as CR-016): build
  the last accepted revision in a scratch worktree, serve it on a second port (for example `:3101`)
  against the same API and the same browser sessions as the product on `:3100`, and compare
  screenshot pairs for every screen and state at each width, plus the test-id and text inventories.
  Record it as a manual case with the pairs and their pixel differences, remove any secret shown on
  screen from the evidence, and remove the worktree and the second server afterwards.

## Shell checks

`qa/checks/<feature>/*.sh`, registered with `tool: script`. Use them to stop and start a service
through the scripts the developer provides in `infra/scripts/` (`local-service.sh stop postgres`;
`local-service.sh pause postgres` for a database that accepts connections and answers nothing, then
`resume postgres`), to read the database, or to scan the build. A check that a request "does not
hang" needs requests in flight at the moment the dependency goes quiet: a request that arrives later
is handed a connection the pool has already tested. If the control a check needs does not exist, that is a
testability bug for the developer; QA does not write under `infra/`. A check exits non-zero on
failure, prints what it found, and leaves the environment as it found it (a stopped service is
started again, even when the check fails).

## k6: concurrency

Scripts live in `qa/load/<feature>/`, registered with `tool: k6`. For anything that must happen
exactly once (a slot, a hold, a booking, a refund, a waitlist offer, a payment notification):

1. Arrange one contested thing: one professional, one free slot, in salon `alpha`.
2. Fire together: many virtual users, one attempt each, released at the same moment.

   ```js
   export const options = {
     scenarios: { rush: { executor: 'per-vu-iterations', vus: 50, iterations: 1, maxDuration: '30s' } },
     thresholds: {
       bookings_created: ['count==1'],     // the invariant
       slot_taken: ['count==49'],          // every loser was told why
       checks: ['rate==1'],
     },
   };
   ```

3. Count outcomes with custom counters and put the invariant in `thresholds`, so that k6 exits
   non-zero when it is broken. A `check` on its own never fails a run.
4. In `teardown`, read the slot back through the API and assert one booking, no orphaned hold and
   no orphaned payment.
5. Repeat with the variations that matter here: overlapping but not identical times; a hold expiring
   as another customer books; a payment notification arriving twice at once; two staff dragging
   bookings onto the same time.

## k6: load

- State the scenario in the file's header: users, ramp, duration, and the data volume it ran on.
- Seed realistic volume first. Availability search over an empty calendar is always fast.
- Spread the load across many salons, as real traffic would be, and include one busy salon.
- Put the target in `thresholds` (`http_req_duration{name:availability}: ['p(95)<500']`) and
  `http_req_failed: ['rate<0.01']`. The targets are in `docs/qa/test-plan.md`.
- Watch the database connection pool. With virtual threads it is the first limit reached.
- A local result is a baseline, not a certification of NFR-03. Record the machine and the data
  volume beside the numbers. Certifying a target needs a staging environment of known size.
- If `k6` is not installed, the run reports the tool as missing. Installing it is system software:
  ask the owner (`docs/architecture/tech-stack.md`).

## Maestro: the customer app

From milestone `M2-mobile`. Flows live in `qa/mobile/<feature>/`, registered with `tool: maestro`;
shared subflows (open a salon, sign in with a one-time code) live in `qa/shared/flows/` and are
reused with `runFlow`.

- Name the cases a flow proves in a comment at the top of the file.
- Select by `id:` (the element's `testID`). Text changes with language and with the salon's wording.
- Open the salon under test the way a customer would: through its link or short code.
- Take the one-time code from the local capture through the shared subflow.
- For native behaviour (push permission, calendar, deep links), include the system dialog steps and
  make them conditional where the dialog appears only on first run.
- Turn recording off during sign-in.

Preflight, in order, before any run: one emulator or device is connected and unlocked; the installed
app is the build for this code and points at the local API; Maestro can reach the device; the API is
healthy and the three synthetic salons exist; the data the flow needs exists and is fresh (a free
slot today, an account with no bookings).

## Running

```bash
qa/scripts/sdlc run --feature F07              # the feature's QA pass: every stage's automation, then the journeys
qa/scripts/sdlc run --feature F07 --dry-run    # show what would run
qa/scripts/sdlc run --stage F07-S02            # one stage's automation, for a stage pass the owner asked for
```

While writing a test, running that one file directly with its tool is fine.
