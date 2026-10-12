# F01-S01 Walking skeleton and local environment: product review

Reviewed as: the operator working out what went wrong; a developer (and QA) setting the product up
on a new machine; a client developer meeting the API for the first time; and, for the one screen,
a customer on a small Android phone on mobile data. No salon, account or booking exists in this
stage, so the receptionist, the professional and the salon owner have nothing to use yet.

Compared with: what the chosen frameworks do when nothing is built on top of them, since that is
what this stage replaces. Fresha, Booksy and Vagaro have no counterpart to anything in this stage
(there is no booking flow), and their public pages were not looked at; the first stage they can be
compared with is the branded booking shell, F01-S03.

## Verdict

Good enough to build on. One command starts the product in about 15 seconds and says exactly what
is missing when it cannot; every error the API returns has the same shape and gives nothing away;
and the status page keeps its head when the API is stopped, slow, hanging or wrong. The one thing
that would improve it most is to make a dependency that has stopped answering as safe as one that
has stopped: today health waits for a frozen database for as long as it stays frozen
(BUG-F01-S01-003), and from F01-S02 every request that reads the database will wait the same way.

## What works well

- **One shape for every error, including the ones the server rejects itself.** An unknown path, a
  wrong method, a broken percent-encoding, a 100,000-character header and bytes that are not HTTP
  all come back as problem details with a `code`, whatever `Accept` asked for, with no server
  header and nothing of the stack. Keep the custom handling of server-rejected requests.
- **The start script reports every missing prerequisite at once, with the fix.** Docker and Java
  missing together produce both lines, each saying what to do, and nothing is started. It is safe
  to run again, and it recovers a crashed API or web app without touching what still runs.
- **The unavailable state is honest and calm.** "We could not reach the service. Nothing is wrong
  on your side." with one action, announced to a screen reader, reachable with one Tab, 44 pixels
  tall, and it recovers by itself when the API returns. Use this wording and pattern for every
  "slow or offline" state that follows.
- **The page never waits on the API to show something.** The frame and a skeleton shaped like the
  content arrive at once; the web app gives the API three seconds and then says so.
- **The result still appears when the script bundles fail to load.** The swap from skeleton to
  result is done by a script inside the page itself, so a phone that could not fetch the bundles
  still sees the status.
- **The drift check names the stale file and repairs nothing.** A renamed field, a deleted
  document, a hand-edited client and a hand-written file beside it are each named for what they are.
- **`info` and the OpenAPI document are closed outside the local profile**, and the actuator
  exposes nothing but health in any profile.

## Suggestions

Ranked, most valuable first. A finding that breaks a requirement is a bug, not a suggestion: file it.

| Id | Class | Suggestion | Disposition |
| --- | --- | --- | --- |
| SUG-F01-S01-01 | should | List the error codes in the contract, so a client can learn them from `docs/api/openapi.json` | CR-001 |
| SUG-F01-S01-02 | should | In the local environment, have the API and the web app listen on this machine only | CR-002 |
| SUG-F01-S01-03 | should | Say in the next spec that "down" includes "accepts connections and does not answer", and give QA a way to freeze PostgreSQL through `infra/scripts` | CR-003 |
| SUG-F01-S01-04 | idea | Tell a browser that runs no scripts something other than "checking the service" for ever | backlog |
| SUG-F01-S01-05 | idea | Do not call a request the server refuses to understand (501, 505) an `internal_error` | backlog |
| SUG-F01-S01-06 | idea | Set a page-weight budget and the security headers when the branded shell replaces this page | backlog |
| SUG-F01-S01-07 | idea | Let long words in headings wrap | backlog |
| SUG-F01-S01-08 | idea | Put the time on the web app's log lines, and say what was left running when a start fails half way | backlog |

### SUG-F01-S01-01 List the error codes in the contract

- **Observed:** `docs/api/openapi.json` describes `Problem.code` as "The stable reason. Branch on
  this." with type `string` and no list of values. The eight codes that exist (`not_found`,
  `method_not_allowed`, `bad_request`, `not_acceptable`, `unsupported_media_type`,
  `service_unavailable`, `request_rejected`, `internal_error`) are written only in the Java enum
  and in this stage's handoff.
- **Why it matters:** for the client developer, and for the customer app later. The rule in
  `docs/engineering/backend.md` is that clients and tests branch on `code`, never on the message;
  a client generated from this contract has no way to know which codes to branch on, and the
  generated TypeScript type is `string`, so a misspelt code compiles. Every later stage adds codes
  (`slot_taken`, `hold_expired`), so the gap grows with each one.
- **Instead:** generate the list into the contract from the enum (an `enum` on `Problem.code`, or a
  named schema of codes), so the generated client carries a union type and the drift check catches
  a code that was renamed. Decide the convention now, before the second module adds its own.
- **Evidence:** `docs/api/openapi.json`, `components.schemas.Problem.properties.code`;
  `packages/api-contracts/src/generated/schema.ts` types it as `string`.

### SUG-F01-S01-02 Have the local API and web app listen on this machine only

- **Observed:** after `local-up.sh`, `lsof` shows `java *:8090` and `node *:3100`: both listen on
  every network interface. PostgreSQL is bound to 127.0.0.1 only. The handoff says the same.
- **Why it matters:** for a developer on shared Wi-Fi. The local profile is the one that serves
  `/actuator/info` (Java version, vendor, build and commit) and the OpenAPI document, and from
  F01-S02 the local API will hold the three synthetic salons and, in F02, working staff accounts
  with known local passwords. None of that is secret, but nothing is gained by offering it to the
  network, and the compose file already made the careful choice for the database.
- **Instead:** in the local profile bind the API to 127.0.0.1 (`server.address`) and start the web
  app with `--hostname 127.0.0.1`, with a documented setting to open it up for testing from a real
  phone on the same network, which QA will want from F01-S03.
- **Evidence:** `lsof -nP -iTCP -sTCP:LISTEN` on 2026-10-12: `node *:3100`, `java *:8090`,
  `com.docker 127.0.0.1:5440`.

### SUG-F01-S01-03 Say that "down" includes "not answering", and give QA a way to freeze PostgreSQL

- **Observed:** AC-2 covers "PostgreSQL stopped", and the product meets it (503 in 2.0 s, fifty at
  once in 2.0 s). With PostgreSQL frozen (`docker pause salon-postgres`), the health request that
  holds a pooled connection waits for as long as the freeze lasts: 240 s and counting when QA gave
  up. That is BUG-F01-S01-003. There is no way to produce this state through
  `infra/scripts/local-service.sh`, which only stops and starts; QA used `docker pause` by hand on
  this project's own container.
- **Why it matters:** for the operator, and soon for every customer. A database that is overloaded
  or cut off by the network does not refuse connections, it goes quiet. From F01-S02 every request
  that reads a salon's data holds a connection in the same way, so a quiet database would leave
  customers looking at a page that never finishes instead of the "service unavailable" state this
  stage built. A criterion that only says "stopped" lets that through again.
- **Instead:** in the F01-S02 spec write the criterion as "stopped, or accepting connections and
  not answering", with a time limit for both; and add `local-service.sh pause|resume postgres` so
  that QA can automate it (`docs/qa/automation.md` says a missing control is the developer's to
  provide).
- **Evidence:** BUG-F01-S01-003; `.local/logs/api.log`: "Health contributor ... (db) took 242131ms
  to respond".

### SUG-F01-S01-04 Tell a browser that runs no scripts something other than "checking the service"

- **Observed:** with JavaScript turned off, the home page shows the loading skeleton for ever, both
  when the API is up and when it is stopped. The result is in the response, in a hidden part of
  the page that only a script reveals. With JavaScript on but the script bundles blocked, the
  result does appear, so this affects only browsers that run no script at all.
- **Why it matters:** hardly at all for this throwaway page. It matters as a decision for the
  booking site, which is built on the same streaming model: a customer whose browser runs no
  script (a data-saving mode, an old in-app browser) would see skeletons and never a service list.
- **Instead:** decide the policy before F01-S03: either a `noscript` line in the shell saying the
  site needs JavaScript, or first content that does not sit behind a boundary a script must reveal.
- **Evidence:** screenshot at 360 wide with JavaScript off, 1.5 s after load: skeleton only, in
  both states. The handoff lists this as not checked.

### SUG-F01-S01-05 Do not call a request the server refuses to understand an `internal_error`

- **Observed:** `GET /api/v1/meta HTTP/3.0` over a raw connection answers 505 with
  `"code":"internal_error","detail":"Something went wrong on our side."` The code is chosen by
  status class: anything 5xx that is not 503 is `internal_error`.
- **Why it matters:** for the operator. Nothing went wrong on our side; a scanner sent nonsense.
  When error-rate alerting arrives (NFR-16), counting these as internal errors makes noise that
  hides real ones.
- **Instead:** give 501 and 505 the code `request_rejected` (or their own), and keep
  `internal_error` for failures of the product.
- **Evidence:** response body above, 2026-10-12.

### SUG-F01-S01-06 Set a page-weight budget and the security headers with the branded shell

- **Observed:** the status page, one heading and three lines, loads about 180 KB of compressed
  JavaScript in nine files. On a connection throttled to 50 KB/s with 300 ms latency the skeleton
  appeared after 0.37 s and the result after 1.0 s, which is good, because the result does not
  wait for the bundles. The web app's responses carry no `Content-Security-Policy`,
  `X-Content-Type-Options` or `Referrer-Policy`.
- **Why it matters:** for the customer on a small Android phone on patchy mobile data. This is the
  framework's baseline before a single component of the booking site exists; anything interactive
  waits for those bundles.
- **Instead:** in the F01-S03 spec state a budget for the booking pages' first load and add the
  headers there, with a case for each, rather than discovering the weight in F07.
- **Evidence:** sizes of the nine `/_next/static/chunks/*.js` files requested with gzip, summed:
  179,997 bytes including two style sheets of 1.3 KB.

### SUG-F01-S01-07 Let long words in headings wrap

- **Observed:** at 320 wide with text at 200%, the heading "Service unavailable" does not wrap
  inside its box: "unavailable" runs past the right edge of the coloured panel and of the card.
  The page still does not scroll sideways. At 360 and 1280, the widths the spec asks for, and at
  360 with 200% text, everything fits. Values (`status-api-service`) already wrap; headings do not.
- **Why it matters:** `docs/design/ui-ux-standards.md` says to leave room because Sinhala and Tamil
  text is often longer. The first long translated heading will do at 360 what this one does at 320.
- **Instead:** give headings the same `overflow-wrap` the values have, in the shared styles the
  branded shell inherits.
- **Evidence:** screenshot at 320 wide, 200% text, unavailable state, 2026-10-12.

### SUG-F01-S01-08 Put the time on the web app's log lines, and say what was left running

- **Observed:** `.local/logs/web.log` holds lines such as `[api] meta is unavailable: TypeError
  (ECONNREFUSED)` with no time, while every line of `api.log` has one. Separately, when
  `local-up.sh` fails at the web app (QA made it fail with a second, incompatible Node.js on the
  `PATH`), PostgreSQL and the API are left running and the script's last words are only about the
  web app.
- **Why it matters:** for the operator and the developer working out what went wrong: the two logs
  cannot be lined up, and a failed start looks like nothing is running when two thirds of it is.
- **Instead:** prefix the web app's server log lines with an ISO time; when a start fails after
  something was started, end with one line saying what is running and that `local-down.sh` stops it.
- **Evidence:** `.local/logs/web.log`; `lsof` after the failed start showed `java *:8090` and
  PostgreSQL on 5440.

## Compared with other products

| Aspect of this flow | Ours | Others | Better for our users |
| --- | --- | --- | --- |
| An address that does not exist, asked for by a browser | 404 as problem details with `code: not_found`, the same for every `Accept` | Spring Boot with nothing added: an HTML error page for a browser, a different JSON body for other callers | Ours |
| A request the server itself rejects (broken encoding, oversized header) | 400 as problem details with a `code`, no server header | The embedded server with nothing added: its own HTML error page | Ours |
| The page when the service behind it is down | A translated "Service unavailable" state inside the normal frame, with "Try again", served as a normal page | Next.js with nothing added: the framework's generic error page for a failed request while rendering | Ours |
| A start with a prerequisite missing | Every missing one named, with the fix, before anything starts | A typical compose-and-run script: fails at the first missing tool, part-way through | Ours |
| Anything a customer, receptionist or owner does | Nothing exists yet | Fresha, Booksy, Vagaro: not compared, no counterpart in this stage | Not applicable until F01-S03 |

## Documents that differ from what was found

For the lead to correct; none of these is a product defect.

- `qa/README.md` and `docs/qa/automation.md` say QA creates `shared/` with "the three synthetic
  salons, accounts" (`tenants.ts`, `accounts.ts`, `builders/`) in F01-S01. `docs/qa/test-plan.md`
  says the salons exist "From stage F01-S02 onward" and that the constants "must match the seed",
  which does not exist yet. QA created `fixtures.ts`, `api-client.ts` and the helpers this stage
  needs, and left `tenants.ts`, `accounts.ts` and `builders/` to the stages that bring the data.
  `qa/README.md` now says so.
- `docs/qa/automation.md` says of Playwright: "Output goes to the folder in `QA_REPORT_DIR`, which
  `qa/scripts/sdlc run` sets." A stage run invokes Playwright once per automation entry with the
  same `QA_REPORT_DIR`, and Playwright empties its output folder when it starts, so pointing it at
  that folder would delete the run's own logs. `qa/playwright.config.ts` writes to
  `QA_REPORT_DIR/playwright/<invocation>/` instead.
- `docs/qa/automation.md` and `docs/qa/test-plan.md` do not say that the product must already be
  running before `qa/scripts/sdlc run`, or who starts it. QA chose: it must be running, every
  entry checks and refuses with a message that begins `ENVIRONMENT:` otherwise, and the run leaves
  it running. `qa/README.md` now says so.
- `qa/scripts/sdlc run` records any non-zero exit other than 127 as `fail`. The skill
  (`references/automation-principles.md` in `salon-qa`) says an environment fault is "not a pass
  and not a product failure"; a check has no way to tell the runner which it was. QA's checks exit
  3 and print `ENVIRONMENT:`; the summary still says `fail`. A third outcome in the runner would
  remove the ambiguity.
- `docs/architecture/tech-stack.md`, "Machine prerequisites", lists "JDK 25, Node.js with pnpm
  through Corepack, Docker". The scripts also need curl, and Node.js 24 or later (the developer
  reported this in the handoff; BUG-F01-S01-005 is the same gap in `README.md`).
- The spec's table "Requirements covered" names NFR-17 and NFR-18, but `stage.yaml` has
  `requirements: []` and can have nothing else: the validator refuses a non-functional requirement
  there and a stage has no `nfr_focus`. So `qa/coverage.md` shows "-" for this stage's
  requirements, and the cases that stand behind NFR-17 and NFR-18 are not traceable to them.
- The handoff says the README's "Quick start" lists the prerequisites including curl; it does not
  (BUG-F01-S01-005). It says `local-up.sh` takes about 15 seconds; QA measured 14 to 25.
- Checked on one machine only, as the handoff says: macOS on Apple silicon. Linux, where the CI
  workflow will run, was not available to QA; TC-F01-S01-057 will be the first run there.

## Bugs filed from this review

- BUG-F01-S01-001 (minor): two runs of `local-up.sh` that overlap both fail, leave the product
  stopped, and can leave an API process the scripts no longer stop.
- BUG-F01-S01-002 (minor): a process-id file left behind makes `local-up.sh` report the API as
  running when it is not, and `local-down.sh` then kills an unrelated process.
- BUG-F01-S01-003 (minor): health hangs for as long as PostgreSQL accepts connections but does not
  answer.
- BUG-F01-S01-004 (minor): with a pnpm that is not Corepack's, `local-up.sh` and
  `pnpm install --frozen-lockfile` rewrite the committed `pnpm-lock.yaml`.
- BUG-F01-S01-005 (minor): `README.md` does not list curl, which `local-up.sh` requires and says is
  listed there.

All five were found by exploring, not by a case. Each gets a case when its fix is re-verified.
