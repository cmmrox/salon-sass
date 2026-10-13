# F01-S01 Walking skeleton and local environment: test cases

## Test approach

| Test type | Why it applies to this stage |
| --- | --- |
| functional | AC-1 and AC-7 are paths a person takes: a clean start from the documented steps, and a status page with three states. Beyond the criteria: repeated starts and stops, a port that is taken, a process that died, other ports, an address the web app does not have |
| api | The stage adds its first endpoints (`meta`, health, info) and the shape of every error. Each is asserted against the committed contract, `docs/api/openapi.json`, not against a copy of it |
| resilience | Two dependencies can be down. PostgreSQL stopped and brought back (AC-2); the API stopped, hanging, answering wrongly, slow, and brought back, under the web app (AC-7); the API process killed |
| security | The stage returns errors to a caller: the unknown path (AC-4), a wrong method, malformed and oversized requests sent over a raw connection, what the actuator exposes, info outside the local profile (AC-5), the source of the unavailable page, and markup in what the API reports |
| responsive | A web screen is added. At 360 and 1280 wide only: the spec narrows the width matrix of `docs/design/ui-ux-standards.md` because the screen is scaffolding, and this pass follows the spec |
| accessibility | A web screen is added: the automated scan on each state, then each state by keyboard alone with a visible focus |
| data-integrity | The stage adds the first migration (AC-6): applied once to an empty database, nothing on a restart, and the data kept when the product is stopped and started |
| build | AC-8, AC-9 and AC-10 are about the build and the pipeline. Each check is proved on an altered copy outside the repository before it is trusted on the committed tree |

Not applied, and why:

- `tenant-isolation`: the stage has no salon data at all. Its database holds only the migration history, no endpoint stores, reads or returns a salon's data, and no salon exists. It starts in F01-S02.
- `permissions`: there is no protected endpoint, no account and no role. Every endpoint of this stage is public by design. It starts in F02.
- `mobile`: there is no customer app, and nothing under `apps/mobile`, until milestone `M2-mobile` (F15).
- `concurrency`: nothing in this stage can be acted on by two people at once. The nearest thing, two runs of the local scripts at the same moment, was BUG-F01-S01-001 and is now covered by TC-F01-S01-058 and TC-F01-S01-059 (shell checks, not k6).
- `load`: no endpoint has a response-time target and no booking goes through one. k6 is not needed by this stage and is not installed.
- `payment-integrity`, `branding`: no money and no salon brand exist yet (F01-S03 for branding).

Environment: local. The product must be running before a stage run (`infra/scripts/local-up.sh`); every automation entry refuses to start otherwise and says so. The run stops and starts the product's services and empties the local database through the developer's scripts, and leaves the product running.

Test data and accounts (from `docs/qa/test-plan.md`): none. The three synthetic salons arrive in F01-S02 and accounts in F02, so `qa/shared/` has no `tenants.ts` or `accounts.ts` yet. The cases rely only on what the handoff lists: the local addresses, the `meta` fields, the error codes, the test ids, `apps/web/messages/en.json` and `docs/api/openapi.json`.

Seen to fail: every automated test was watched going red once before it was trusted. The API tests were pointed at a stand-in API that answers wrongly (14 of 14 failed), the browser tests at a stand-in web app that is wrong in each way they look for (19 of 19 failed), and the checks of the scripts, the database and the profile were run against scripts that do nothing and with one expectation at a time made false (every case failed). The three build checks carry their own proof: each breaks a copy and must fail on it in every run.

Re-verification (2026-10-12): the five bugs of the first pass each got a case, TC-F01-S01-058 to TC-F01-S01-063. Each was seen to fail against the tree as it was before the fixes (`e04b595`, copied outside the repository and started on its own), for the reason its bug describes: overlapping starts both failing, "api: already running" with no API and an unrelated process killed, a health request with no answer in 60 s across a freeze, `pnpm-lock.yaml` growing by 871 bytes, and a README without curl or ps. TC-F01-S01-059 fails there too, but only because the old scripts ignore locks: it guards the fix's own new risk, a lock left by a killed script, rather than the original symptom.

## Expectations at the full F01 pass

The approved S03/S04 specifications supersede the skeleton's original response shape: `timeZone` is a public salon field (S04 AC-4), `traceId` identifies each error request (S04 AC-1), and equivalent safe errors exclude that trace when compared. Invalid bare network hosts return the neutral 404 page (S03 AC-3, decision 028). S04 adds audit/outbox/job migrations; migration tests prove the baseline once and every committed migration once, with history unchanged on restart. Recovery timing uses successful PostgreSQL resume return, with invocation and confirmation marks retained, as the QA timing rule requires.

## Cases

Every case id here is declared under `cases:` in `stage.yaml`, and the reverse.


### TC-F01-S01-001 From stopped, local-up.sh starts PostgreSQL, the API and the web app and prints the address of each

- **Type:** functional
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** Nothing of the product is running: no listener on its three ports, no process of the API or the web app, no `salon-postgres` container.
- **Steps:**
  1. Run `infra/scripts/local-up.sh`.
  2. Read what it printed.
  3. Request `/actuator/health` and `/api/v1/meta` at the API's printed address, `/` at the web app's, and run `select 1` in PostgreSQL.
- **Expected:** The script exits 0. Its output contains PostgreSQL's address (`localhost:<port>`), the API's address and the web app's address. As soon as it has returned, health and meta answer 200, the home page answers 200 and holds the status panel, and PostgreSQL answers the query. Nothing has to be started by hand afterwards.
- **Automation:** `qa/checks/F01/S01-local-environment.sh`

### TC-F01-S01-002 With Docker unreachable, local-up.sh exits non-zero, names Docker and starts nothing

- **Type:** build
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** The product is stopped. Docker is made unreachable for this one command with `DOCKER_HOST=unix:///nonexistent/docker.sock`, as the handoff describes; Docker itself is not stopped.
- **Steps:**
  1. Run `DOCKER_HOST=unix:///nonexistent/docker.sock infra/scripts/local-up.sh`.
- **Expected:** The exit code is not 0. The output names Docker. Afterwards nothing listens on the product's three ports, no process of the API or the web app exists and no `salon-postgres` container runs: the check happened before anything was started.
- **Automation:** `qa/checks/F01/S01-local-environment.sh`

### TC-F01-S01-003 With a Java that is not 25, local-up.sh exits non-zero, names the Java it needs and starts nothing

- **Type:** build
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** The product is stopped. A JDK of another version exists on the machine (the check finds one, or builds a stand-in that reports Java 21).
- **Steps:**
  1. Run `JAVA25_HOME=<the other JDK> infra/scripts/local-up.sh`.
  2. Run it again with Docker unreachable as well (`DOCKER_HOST=unix:///nonexistent/docker.sock`).
- **Expected:** Each time the exit code is not 0 and the output names JDK 25 (or Java 25) as what is needed. With both missing, the output names Docker and Java 25, so both can be fixed in one go. Nothing has been started either time.
- **Automation:** `qa/checks/F01/S01-local-environment.sh`

### TC-F01-S01-004 A second local-up.sh while the product is running changes nothing and exits zero

- **Type:** functional
- **Priority:** medium
- **Covers:** AC-1
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. Note the process listening on the API's port, the one on the web app's port, and when the PostgreSQL container started.
  2. Run `infra/scripts/local-up.sh` again.
- **Expected:** The exit code is 0 and the addresses are printed again. The API and the web app are the same processes as before and PostgreSQL has the same start time: nothing was restarted, rebuilt or duplicated. Health and the home page still answer 200.
- **Automation:** `qa/checks/F01/S01-local-environment.sh`

### TC-F01-S01-005 local-down.sh stops all three services, and a second local-down.sh exits zero

- **Type:** functional
- **Priority:** medium
- **Covers:** AC-1
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. Run `infra/scripts/local-down.sh`.
  2. Run it a second time.
- **Expected:** Both exit 0. After the first, nothing listens on the product's three ports, no process of the API or the web app is left behind, and no `salon-postgres` container runs. The second finds nothing to stop and does not fail.
- **Automation:** `qa/checks/F01/S01-local-environment.sh`

### TC-F01-S01-006 With its port taken by another program, local-up.sh exits non-zero, names the port and leaves that program alone

- **Type:** functional
- **Priority:** medium
- **Covers:** AC-1
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** The product is stopped. Another program (a throwaway `python3 -m http.server`) listens on the API's port on 127.0.0.1.
- **Steps:**
  1. Run `infra/scripts/local-up.sh`.
- **Expected:** The exit code is not 0. The output names the port number and the setting that moves it (`SALON_API_PORT`). The other program is still running and still owns the port; no API process was started beside it.
- **Automation:** `qa/checks/F01/S01-local-environment.sh`

### TC-F01-S01-007 After the API process dies, local-up.sh starts it again and leaves the rest alone

- **Type:** resilience
- **Priority:** medium
- **Covers:** AC-1
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. Kill the API's process outright (`kill -9`), as a crash would.
  2. Wait until its port is closed.
  3. Run `infra/scripts/local-up.sh`.
- **Expected:** The exit code is 0 and health answers 200 again from a new API process. The web app is the same process as before: only what had died was started.
- **Automation:** `qa/checks/F01/S01-local-environment.sh`

### TC-F01-S01-008 local-service.sh stops and starts one service, leaves the others running, and refuses anything else

- **Type:** functional
- **Priority:** medium
- **Covers:** AC-1
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. Run `infra/scripts/local-service.sh stop web`.
  2. Run `infra/scripts/local-service.sh start web`.
  3. Run it with `restart api`, with `stop redis`, with `start` alone, with nothing, with `pause api`, with `resume web` and with `pause` alone.
- **Expected:** `stop web` exits 0, closes the web app's port and leaves the API's process and its health untouched. `start web` exits 0 and the home page answers 200 again. Each of the seven other invocations exits non-zero, prints the usage naming `start|stop` and `postgres|api|web`, and its second line naming `pause|resume` and `postgres` (the test hook exists for PostgreSQL only), and changes nothing. (Changed at re-verification: the fix for BUG-F01-S01-003 added the `pause|resume postgres` hook and the second usage line.)
- **Automation:** `qa/checks/F01/S01-local-environment.sh`

### TC-F01-S01-009 The product runs on other ports when the documented settings name them, in the shell or in .env

- **Type:** functional
- **Priority:** low
- **Covers:** AC-1
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** The product is stopped. No `.env` exists at the repository root.
- **Steps:**
  1. Run `SALON_API_PORT=8091 SALON_WEB_PORT=3101 SALON_POSTGRES_PORT=5441 infra/scripts/local-up.sh` and open the web app at the printed address.
  2. Run `infra/scripts/local-down.sh` without those variables.
  3. Create a root `.env` holding `SALON_WEB_PORT=3102` and `SALON_API_PORT="8092"`, run `local-up.sh`, then restart the web app with `SALON_WEB_PORT=3103` in the shell.
  4. Stop everything and delete the `.env`.
- **Expected:** Each start exits 0 and prints the addresses on the ports that were set. The web app's status page shows the API's name and version, so it found the API on its new port without being told separately. `local-down.sh` stops everything even without the variables. A value in `.env` is honoured with or without double quotes, and a variable in the shell wins over `.env`.
- **Automation:** manual, because it needs a root `.env`, which a check must never write into the repository, and a full extra build-and-start cycle on every stage run to protect settings no acceptance criterion names; performed on 2026-10-12, result as expected

### TC-F01-S01-010 Health answers 200 with status UP and a database component that is UP

- **Type:** api
- **Priority:** high
- **Covers:** AC-2
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. `GET /actuator/health`.
- **Expected:** Status 200. The body's `status` is `UP`, it has a `components.db` entry, and that entry's `status` is `UP`.
- **Automation:** `qa/api/F01/S01-meta-health-info.spec.ts`

### TC-F01-S01-011 Meta answers 200 with service, version, commit and time, and matches the committed contract

- **Type:** api
- **Priority:** high
- **Covers:** AC-3
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`). `docs/api/openapi.json` describes `GET /api/v1/meta` and a `Meta` schema.
- **Steps:**
  1. `GET /api/v1/meta`.
  2. Validate the body against the `Meta` schema of `docs/api/openapi.json`.
- **Expected:** Status 200, content type `application/json`. `service`, `version`, `commit` and `time` are non-empty strings. The body validates against the schema, and carries no field the schema does not describe. `time` is an ISO-8601 instant in UTC: a date, a time and a `Z`, never an offset.
- **Automation:** `qa/api/F01/S01-meta-health-info.spec.ts`

### TC-F01-S01-012 Meta reports the server's time now, as a UTC instant, on every request

- **Type:** api
- **Priority:** medium
- **Covers:** AC-3
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. Note this machine's time.
  2. `GET /api/v1/meta` repeatedly until the reported time changes.
  3. Note this machine's time again.
- **Expected:** Every `time` is a UTC instant ending in `Z`. A later request reports a later time, so the answer is neither cached nor built in. Each reported time lies between the two readings of the machine's clock, within two seconds: a local time written with a `Z` would be hours out.
- **Automation:** `qa/api/F01/S01-meta-health-info.spec.ts`

### TC-F01-S01-013 Meta reports the commit the running build was made from

- **Type:** api
- **Priority:** medium
- **Covers:** AC-3
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`), started from a checkout of this repository.
- **Steps:**
  1. `GET /api/v1/meta`.
  2. Ask git what kind of object the reported `commit` is.
- **Expected:** `commit` is a full 40-character commit id, not a placeholder and not `unknown`, and git knows it as a commit of this repository: it came from the build, not from a hand-edited property.
- **Automation:** `qa/api/F01/S01-meta-health-info.spec.ts`

### TC-F01-S01-014 The contract the running API serves is the committed docs/api/openapi.json

- **Type:** api
- **Priority:** high
- **Covers:** AC-3
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`) with the local profile, which serves `/v3/api-docs`.
- **Steps:**
  1. `GET /v3/api-docs`.
  2. Compare it with `docs/api/openapi.json`, ignoring the order of keys.
- **Expected:** Status 200 and the served document describes at least one path. Its content equals the committed document exactly: the code that is running and the contract the clients were generated from have not drifted apart.
- **Automation:** `qa/api/F01/S01-meta-health-info.spec.ts`

### TC-F01-S01-015 A path under /api that does not exist answers 404 as problem details with code not_found

- **Type:** api
- **Priority:** high
- **Covers:** AC-4
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. `GET` each of: `/api/v1/does-not-exist`, `/api/v1/meta/extra`, `/api/v1/meta/`, `/api/v1/`, `/api/v1`, `/api/v2/meta`, `/api/`, `/api`, an unknown path with a query string, and one forty segments deep.
- **Expected:** Each answers 404 with content type `application/problem+json`. The body validates against the `Problem` schema of the contract, its `status` is 404 and its `code` is `not_found`.
- **Automation:** `qa/api/F01/S01-errors.spec.ts`

### TC-F01-S01-016 An unknown path answers 404 as problem details whatever format the caller asks for

- **Type:** api
- **Priority:** medium
- **Covers:** AC-4
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. `GET /api/v1/does-not-exist` with `Accept` set in turn to `text/html`, a browser's usual list, `application/xml`, `text/plain`, `image/png` and `application/json`.
- **Expected:** Every one answers 404 as `application/problem+json` with `code` `not_found`. A browser never gets the framework's HTML error page, and a caller asking for XML never gets an empty body or a different error.
- **Automation:** `qa/api/F01/S01-errors.spec.ts`

### TC-F01-S01-017 A 404 shows no stack trace, class name or framework version, in its body or its headers

- **Type:** security
- **Priority:** high
- **Covers:** AC-4
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. `GET` every unknown path of TC-F01-S01-015, and these outside `/api`: `/does-not-exist`, `/actuator/does-not-exist`, `/error`, `/favicon.ico`, `/index.html`, `/swagger-ui.html`, `/swagger-ui/index.html`, `/h2-console`, `/.env`, `/.git/config`, `/WEB-INF/web.xml`.
  2. `GET /api/v1/does-not-exist?trace=true&message=true&errors=true&exception=true`.
- **Expected:** Each answers 404 as problem details. Neither the body nor any header contains a stack frame, a Java package or class name, an exception name, the name of a framework or server, a version number or a file path; there is no `Server`, `X-Powered-By` or `X-Application-Context` header. Asking for a trace adds no field: the body holds `code`, `detail`, `instance`, `status` and `title` and nothing else.
- **Automation:** `qa/api/F01/S01-errors.spec.ts`

### TC-F01-S01-018 A method meta does not support answers 405 as problem details with code method_not_allowed

- **Type:** api
- **Priority:** medium
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. Send `POST`, `PUT`, `PATCH` and `DELETE` to `/api/v1/meta`, each with a small JSON body.
  2. `GET /api/v1/meta`.
- **Expected:** Each answers 405 as problem details with `code` `method_not_allowed` and an `Allow` header that names `GET`, and shows no internals. Nothing was changed: meta still answers 200.
- **Automation:** `qa/api/F01/S01-errors.spec.ts`

### TC-F01-S01-019 With the local profile, info reports Java 25 and that virtual threads are enabled

- **Type:** api
- **Priority:** high
- **Covers:** AC-5
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`) with the local profile (the default).
- **Steps:**
  1. `GET /actuator/info`.
- **Expected:** Status 200. `runtime.javaMajorVersion` is 25, the Java major version in `docs/architecture/tech-stack.md`; `runtime.virtualThreads` is `true`; and the reported Java version starts with 25.
- **Automation:** `qa/api/F01/S01-meta-health-info.spec.ts`

### TC-F01-S01-020 No actuator endpoint that exposes configuration, memory or control is served

- **Type:** security
- **Priority:** medium
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`) with the local profile, the most permissive one.
- **Steps:**
  1. `GET /actuator` and read its links.
  2. `GET` each of `/actuator/env`, `beans`, `configprops`, `heapdump`, `threaddump`, `mappings`, `loggers`, `metrics`, `shutdown`, `logfile`, `conditions`, `scheduledtasks`, `flyway`, `caches`, `sbom`, `startup`.
  3. `POST /actuator/shutdown`.
- **Expected:** The index links to health and info only. Every listed endpoint answers 404. The shutdown request is refused (404 or 405) and the API is still healthy afterwards.
- **Automation:** `qa/api/F01/S01-meta-health-info.spec.ts`

### TC-F01-S01-021 A response format meta cannot produce answers 406 as problem details with code not_acceptable

- **Type:** api
- **Priority:** low
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. `GET /api/v1/meta` with `Accept` set in turn to `application/xml`, `text/html` and `image/png`.
- **Expected:** Each answers 406 as problem details with `code` `not_acceptable`, and shows no internals.
- **Automation:** `qa/api/F01/S01-errors.spec.ts`

### TC-F01-S01-022 A malformed or oversized request is answered 4xx as problem details, shows no internals and never hangs

- **Type:** security
- **Priority:** high
- **Covers:** AC-4
- **Performed as:** Anything that can open a connection to the API's port, sending bytes no well-behaved client sends
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. Over a raw connection send, one at a time: a path with a broken percent-encoding; a path with an encoded NUL; a path that climbs out with encoded slashes, and with dots; a path with characters a URL may not contain; a path with a backslash; a URL of 100,000 characters; a header of 100,000 characters; 2,000 headers; a request with no `Host`; a negative `Content-Length`; a `Content-Length` that is not a number; a header line with no colon; a request line that is not HTTP; a method made of illegal characters; and binary that is not HTTP at all.
  2. `GET /actuator/health`.
- **Expected:** Every one gets an HTTP answer within ten seconds, with a 4xx status (the mistake is the caller's, never a 5xx), as `application/problem+json` that validates against the `Problem` schema with a `code` and a `status` equal to the HTTP status. No answer shows internals in its body or headers. The API is healthy afterwards.
- **Automation:** `qa/api/F01/S01-errors.spec.ts`

### TC-F01-S01-023 A large body sent to an endpoint that takes none is refused as problem details, and a flood of them leaves the API up

- **Type:** security
- **Priority:** medium
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** Anything that can open a connection to the API's port
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. `POST` a 1 MB body to `/api/v1/meta`.
  2. At the same moment, send twenty `POST` requests of 5 MB each, half to `/api/v1/meta` and half to an unknown path.
  3. `GET /actuator/health` and `GET /api/v1/meta`.
- **Expected:** The 1 MB request is answered 405 as problem details with `code` `method_not_allowed`, promptly and with no internals. Each of the twenty is refused: answered 4xx as problem details, or the connection is closed before the body is read; never a success, never a 5xx, never a hang. Afterwards health and meta answer 200.
- **Automation:** `qa/api/F01/S01-errors.spec.ts`

### TC-F01-S01-024 With PostgreSQL stopped, health answers 503 within 5 seconds with the database component DOWN

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-2
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`). PostgreSQL is then stopped with `infra/scripts/local-service.sh stop postgres`, and the check confirms its port is closed.
- **Steps:**
  1. `GET /actuator/health` at once, again, and a third time three seconds later, timing each.
- **Expected:** Each answers 503 in less than 5 seconds. The body's `status` is `DOWN` and `components.db.status` is `DOWN`.
- **Automation:** `qa/checks/F01/S01-database-down.sh`

### TC-F01-S01-025 With PostgreSQL stopped, fifty health requests at once each answer 503 within 5 seconds

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-2
- **Performed as:** Fifty callers at the same moment
- **Preconditions:** As TC-F01-S01-024: PostgreSQL is stopped.
- **Steps:**
  1. Send fifty `GET /actuator/health` requests together, timing each.
- **Expected:** All fifty get an answer; every answer is 503; none takes 5 seconds or longer. Waiting callers do not queue behind one another until something times out.
- **Automation:** `qa/checks/F01/S01-database-down.sh`

### TC-F01-S01-026 With PostgreSQL stopped, no answer shows how the product is built, and the rest of the API still answers

- **Type:** security
- **Priority:** medium
- **Covers:** AC-2
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** As TC-F01-S01-024: PostgreSQL is stopped.
- **Steps:**
  1. Read the body of the 503 from health.
  2. `GET /api/v1/does-not-exist`.
  3. `GET /api/v1/meta`.
- **Expected:** The 503 body names no exception, class, driver, pool, address or version. The unknown path still answers 404 as problem details with `code` `not_found`, quickly. Meta, which needs no database, answers within 5 seconds: 200, or 503 as problem details; never a 500 and never a hang.
- **Automation:** `qa/checks/F01/S01-database-down.sh`

### TC-F01-S01-027 When PostgreSQL comes back, health returns to 200 UP without the API being restarted

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-2
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** As TC-F01-S01-024: PostgreSQL is stopped, and the API's process id was noted before the stop.
- **Steps:**
  1. Run `infra/scripts/local-service.sh start postgres`.
  2. Poll `GET /actuator/health` until it answers 200, for at most a minute.
- **Expected:** Health answers 200 again with `status` `UP` and `components.db.status` `UP`. The API is the same process as before the outage: recovery needed no restart and no repair by hand.
- **Automation:** `qa/checks/F01/S01-database-down.sh`

### TC-F01-S01-028 Started with a profile other than local, info answers 404 while the API itself is up

- **Type:** security
- **Priority:** high
- **Covers:** AC-5
- **Performed as:** Any caller of the API; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`) with the local profile, and `GET /actuator/info` answers 200 there.
- **Steps:**
  1. For each of the profiles `staging`, `production` and `default`: stop the API and start it with `SPRING_PROFILES_ACTIVE=<profile> infra/scripts/local-service.sh start api`, as the handoff describes.
  2. `GET /actuator/health`, `GET /api/v1/meta`, `GET /actuator/info` and `GET /actuator`.
  3. Start the API with the local profile again and `GET /actuator/info`.
- **Expected:** In each of the three profiles the API starts, and health and meta answer 200, so it is really up. `GET /actuator/info` answers 404 as problem details with `code` `not_found`; the body says nothing of Java, versions or threads; and the actuator index does not link to info. Back on the local profile info answers 200 again, which shows the 404 was the profile's doing.
- **Automation:** `qa/checks/F01/S01-other-profile.sh`

### TC-F01-S01-029 On an empty database the API applies the baseline migration and records it once in flyway_schema_history

- **Type:** data-integrity
- **Priority:** high
- **Covers:** AC-6
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`). A table written by QA exists in the database, so that emptying it can be seen.
- **Steps:**
  1. Run `infra/scripts/local-reset.sh`.
  2. Read `flyway_schema_history` and the list of tables with `psql` in the `salon-postgres` container.
- **Expected:** The reset exits 0 and health answers 200. The database was really emptied: QA's table is gone, `flyway_schema_history` is the only table and its history is a new one. It holds exactly one row: version `1`, the baseline migration, type `SQL`, `success` true.
- **Automation:** `qa/checks/F01/S01-migrations.sh`

### TC-F01-S01-030 Starting the API a second time applies nothing and does not fail

- **Type:** data-integrity
- **Priority:** high
- **Covers:** AC-6
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** As TC-F01-S01-029: the database holds the baseline migration, applied once.
- **Steps:**
  1. Stop the API and start it again with `infra/scripts/local-service.sh`; do this twice.
  2. After each start read `flyway_schema_history` and the list of tables.
- **Expected:** Each start exits 0 and health answers 200. `flyway_schema_history` still holds exactly one row, identical to before down to its timestamp, and no table has appeared.
- **Automation:** `qa/checks/F01/S01-migrations.sh`

### TC-F01-S01-031 local-down.sh followed by local-up.sh keeps the database's data

- **Type:** data-integrity
- **Priority:** medium
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. Create a table with one row in the local database.
  2. Run `infra/scripts/local-down.sh`, then `infra/scripts/local-up.sh`.
  3. Read the row and the migration history.
- **Expected:** Both scripts exit 0 and the container was really removed in between. The row is still there and the migration history is unchanged: stopping the product does not lose its data.
- **Automation:** `qa/checks/F01/S01-migrations.sh`

### TC-F01-S01-032 The home page shows the service name and version the API reports

- **Type:** functional
- **Priority:** high
- **Covers:** AC-7
- **Performed as:** Anyone who opens the web app in a browser; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. `GET /api/v1/meta` and note `service` and `version`.
  2. Open the web app's home page.
- **Expected:** The element `status-api-service` shows exactly the API's `service` and `status-api-version` exactly its `version`, each beside the word that says what it is. The loaded state is the only state on the page: neither the loading skeleton nor the unavailable state is there with it.
- **Automation:** `qa/web/F01/S01-status-page.spec.ts`

### TC-F01-S01-033 The status page shows the product's placeholder name and says what the page is

- **Type:** functional
- **Priority:** medium
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** Anyone who opens the web app in a browser; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. Open the home page.
- **Expected:** The placeholder name from the translation file is shown at the top and is the page's title. There is exactly one level-1 heading, the status page's; one `main` landmark; and the page declares its language.
- **Automation:** `qa/web/F01/S01-status-page.spec.ts`

### TC-F01-S01-034 An address the web app does not have answers 404 with a not-found page that leads home

- **Type:** functional
- **Priority:** medium
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** Anyone who opens the web app in a browser; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. Open `/qa-no-such-page`.
  2. Press the link the page offers.
- **Expected:** The response status is 404. The page shows the product's name, a heading and a sentence saying nothing is there, in the translation file's words, and one link. The link leads to the home page, which shows the service's status.
- **Automation:** `qa/web/F01/S01-status-page.spec.ts`

### TC-F01-S01-035 At 360 and 1280 wide the status page and the not-found page fit without scrolling sideways

- **Type:** responsive
- **Priority:** high
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** A customer on a small Android phone (360 wide), and someone at a desktop (1280 wide)
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. At each width open the home page, then `/qa-no-such-page`.
  2. Measure the page and every element that carries a test id.
- **Expected:** At both widths, on both pages: the page does not scroll sideways, no element reaches outside the viewport, and none holds more text than it shows. (The spec asks for these two widths only, because the screen is scaffolding.)
- **Automation:** `qa/web/F01/S01-status-page.spec.ts`

### TC-F01-S01-036 An automated accessibility scan of the status page and the not-found page finds nothing serious

- **Type:** accessibility
- **Priority:** high
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** Someone using a screen reader, or who needs strong contrast
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. Open the home page and run an axe scan against WCAG 2.2 level AA.
  2. Do the same on `/qa-no-such-page`.
- **Expected:** The scan runs (it reports rules that passed) and finds no violation of serious or critical impact on either page.
- **Automation:** `qa/web/F01/S01-status-page.spec.ts`

### TC-F01-S01-037 The not-found page can be used with the keyboard alone, and shows where the focus is

- **Type:** accessibility
- **Priority:** medium
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** Someone who does not use a pointer
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. Open `/qa-no-such-page`.
  2. Press Tab once.
  3. Press Enter.
- **Expected:** The first Tab puts the focus on the link home, and the focus is visible: the link has an outline at least 2 pixels wide. Enter follows the link to the home page.
- **Automation:** `qa/web/F01/S01-status-page.spec.ts`

### TC-F01-S01-038 Every string shown on the status page and the not-found page comes from the translation file

- **Type:** functional
- **Priority:** high
- **Covers:** AC-9
- **Performed as:** Anyone who opens the web app in a browser; no account exists in this stage
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. Open the home page and collect every string a person sees or hears: each rendered text node (including text hidden from sight for screen readers), the title, and spoken attributes.
  2. Do the same on `/qa-no-such-page`.
- **Expected:** At least four strings are found on each page. Every one is a value of `apps/web/messages/en.json`, except the service name and version on the home page, which are what the API reported.
- **Automation:** `qa/web/F01/S01-status-page.spec.ts`

### TC-F01-S01-039 Every element a test reads or presses on the status page and the not-found page carries a test id

- **Type:** functional
- **Priority:** high
- **Covers:** AC-9
- **Performed as:** QA, automating the page
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`)
- **Steps:**
  1. Open the home page and list its test ids and everything on it that can be pressed.
  2. Do the same on `/qa-no-such-page`.
- **Expected:** The home page has `app-name`, `status-panel`, `status-heading`, `status-loaded`, `status-summary`, `status-api-service` and `status-api-version`; the not-found page has `app-name`, `not-found`, `not-found-title`, `not-found-body` and `not-found-home`. Every test id is kebab-case `<area>-<thing>` and used once per page. Nothing that can be pressed is without one, and the not-found page does have something to press.
- **Automation:** `qa/web/F01/S01-status-page.spec.ts`

### TC-F01-S01-040 The three states of the status page look right to a person at 360 and 1280 wide

- **Type:** functional
- **Priority:** medium
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** A person looking at the screen, on a phone-sized and a desktop-sized browser
- **Preconditions:** The product is running (`infra/scripts/local-up.sh`); for the unavailable state the API is stopped.
- **Steps:**
  1. Open the home page at 360 and at 1280 wide and look at the loaded state.
  2. Reload and watch the loading state give way to the result, at real speed and on a throttled connection.
  3. Stop the API, reload and look at the unavailable state; press Tab and look at the focus.
  4. Repeat the unavailable state at 320 wide with text at 200%.
- **Expected:** The loading state is a skeleton shaped like the content, not a blank page or a spinner. The loaded and unavailable states are readable, aligned and inside their card, with nothing overlapping. The focus ring on the action is plainly visible. Nothing opens, closes and reopens while the page settles.
- **Automation:** manual, because whether a screen looks right is a person's judgement that no assertion replaces; performed on 2026-10-12, result as expected at 360 and 1280 (the skeleton shows for about 0.3 s on a fast connection and about 0.65 s at 50 KB/s; at 320 wide with 200% text the word "unavailable" runs past its box, noted in review.md)

### TC-F01-S01-041 With the API stopped the home page shows a plain service-unavailable state: no overlay, no stack trace, not blank

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-7
- **Performed as:** Anyone who opens the web app in a browser; no account exists in this stage
- **Preconditions:** The web app is running and the API has been stopped with `infra/scripts/local-service.sh stop api`; the test confirms the API does not answer.
- **Steps:**
  1. Open the home page.
- **Expected:** The unavailable state is shown: its heading, a sentence in plain words and a "try again" action, all in the translation file's words. The page's frame is still there (the product's name and the page's heading), so it is not blank. There is no service name or version, no loading skeleton, no error overlay of the framework, and nothing in the page's words that shows internals. The browser reports no error, and the page itself is served rather than failed.
- **Automation:** `qa/web/F01/S01-status-page-api-down.spec.ts`

### TC-F01-S01-042 With the API stopped nothing the browser receives shows how the product is built or where the API is

- **Type:** security
- **Priority:** medium
- **Covers:** AC-7
- **Performed as:** Anyone who views the page's source
- **Preconditions:** The web app is running and the API has been stopped with `infra/scripts/local-service.sh stop api`; the test confirms the API does not answer.
- **Steps:**
  1. Open the home page.
  2. Read the whole document as received, scripts and data included, and the response headers.
- **Expected:** The document does not contain the API's address, the name of the setting that holds it, a connection error code, the name of the failure, a stack frame or a path on the server. There is no `X-Powered-By` header.
- **Automation:** `qa/web/F01/S01-status-page-api-down.spec.ts`

### TC-F01-S01-043 When the API accepts a connection and never answers, the page shows the unavailable state instead of hanging

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-7
- **Performed as:** Anyone who opens the web app in a browser; no account exists in this stage
- **Preconditions:** The web app is running and the API has been stopped with `infra/scripts/local-service.sh stop api`; the test confirms the API does not answer. Something stands in for the API on its port: it accepts each request and never answers.
- **Steps:**
  1. Open the home page.
- **Expected:** The page's heading is on screen within 3 seconds, so the person is never looking at a blank page. The unavailable state replaces the loading state within 10 seconds (QA's own bound on "does not hang"; the spec gives no number). The stand-in did receive the web app's request.
- **Automation:** `qa/web/F01/S01-status-page-api-down.spec.ts`

### TC-F01-S01-044 When the API answers with an error or with something that is not meta, the page shows the unavailable state

- **Type:** resilience
- **Priority:** medium
- **Covers:** AC-7
- **Performed as:** Anyone who opens the web app in a browser; no account exists in this stage
- **Preconditions:** The web app is running and the API has been stopped with `infra/scripts/local-service.sh stop api`; the test confirms the API does not answer. A stand-in answers on the API's port, differently each time.
- **Steps:**
  1. Open the home page with the stand-in answering, in turn: a 500 with an HTML error page holding a stack trace; a 503 and a 404 as problem details; a 200 that is an HTML page; a 200 that says JSON and is cut off; a 200 with an empty object, with nulls, with numbers, with a list, and with no body; a connection closed before any answer; and a redirect elsewhere.
- **Expected:** Every time the page shows the unavailable state, never a loaded state with empty, `undefined` or borrowed values, and never anything from the stand-in's error page. The browser reports no error.
- **Automation:** `qa/web/F01/S01-status-page-api-down.spec.ts`

### TC-F01-S01-045 While the API is slow the page shows the loading state, and then the result

- **Type:** functional
- **Priority:** medium
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** Anyone who opens the web app in a browser; no account exists in this stage
- **Preconditions:** The web app is running and the API has been stopped with `infra/scripts/local-service.sh stop api`; the test confirms the API does not answer. A stand-in answers meta correctly after 1.5 seconds.
- **Steps:**
  1. Open the home page and watch it.
- **Expected:** While the stand-in has not answered, the loading state is on screen and says, for a screen reader, that the service is being checked. When the answer arrives the result replaces it without a reload: the stand-in's service name and version are shown, and the loading state is gone.
- **Automation:** `qa/web/F01/S01-status-page-api-down.spec.ts`

### TC-F01-S01-046 Markup in what the API reports is shown as text and never runs

- **Type:** security
- **Priority:** medium
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** Anyone who opens the web app in a browser; no account exists in this stage
- **Preconditions:** The web app is running and the API has been stopped with `infra/scripts/local-service.sh stop api`; the test confirms the API does not answer. A stand-in answers meta with a service name and a version that contain an image tag with an error handler, bold markup, a script tag, quotes and an entity.
- **Steps:**
  1. Open the home page.
- **Expected:** Both values are shown exactly as the characters that were sent. No image, bold or script element is created from them, no script from them runs and no dialog opens.
- **Automation:** `qa/web/F01/S01-status-page-api-down.spec.ts`

### TC-F01-S01-047 At 360 and 1280 wide the unavailable state fits, its action can be reached, and a very long value still fits

- **Type:** responsive
- **Priority:** high
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** A customer on a small Android phone (360 wide), and someone at a desktop (1280 wide)
- **Preconditions:** The web app is running and the API has been stopped with `infra/scripts/local-service.sh stop api`; the test confirms the API does not answer.
- **Steps:**
  1. At each width open the home page and measure the page, its elements and the "try again" action.
  2. With a stand-in reporting a service name of 300 characters with no place to break, open the home page at 360 wide.
- **Expected:** At both widths the page does not scroll sideways and nothing reaches outside the viewport or is clipped. The action is entirely within the viewport and at least 44 by 44 pixels, the touch target in `docs/design/ui-ux-standards.md`. The very long value wraps and the page still does not scroll sideways.
- **Automation:** `qa/web/F01/S01-status-page-api-down.spec.ts`

### TC-F01-S01-048 An automated accessibility scan of the unavailable state finds nothing serious, and the state is announced

- **Type:** accessibility
- **Priority:** high
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** Someone using a screen reader, or who needs strong contrast
- **Preconditions:** The web app is running and the API has been stopped with `infra/scripts/local-service.sh stop api`; the test confirms the API does not answer.
- **Steps:**
  1. Open the home page and run an axe scan against WCAG 2.2 level AA.
  2. Look for the alert in the status panel.
- **Expected:** The scan finds no violation of serious or critical impact. The unavailable state is an alert that holds its heading, so a screen reader announces it without the person looking for it.
- **Automation:** `qa/web/F01/S01-status-page-api-down.spec.ts`

### TC-F01-S01-049 The unavailable state can be used with the keyboard alone: Tab reaches "try again", the focus shows, Enter retries

- **Type:** accessibility
- **Priority:** high
- **Covers:** no acceptance criterion; added by QA
- **Performed as:** Someone who does not use a pointer
- **Preconditions:** The web app is running and the API has been stopped with `infra/scripts/local-service.sh stop api`; the test confirms the API does not answer.
- **Steps:**
  1. Open the home page.
  2. Press Tab once.
  3. Let a stand-in start answering meta correctly, then press Enter.
- **Expected:** The first Tab puts the focus on "try again", with a visible outline at least 2 pixels wide. Enter asks the API again, and the page shows the name the stand-in now reports instead of the unavailable state.
- **Automation:** `qa/web/F01/S01-status-page-api-down.spec.ts`

### TC-F01-S01-050 In the unavailable state every string comes from the translation file and every element a test reads or presses carries a test id

- **Type:** functional
- **Priority:** high
- **Covers:** AC-9
- **Performed as:** QA, automating the page
- **Preconditions:** The web app is running and the API has been stopped with `infra/scripts/local-service.sh stop api`; the test confirms the API does not answer.
- **Steps:**
  1. Open the home page.
  2. Collect every string a person sees or hears, the test ids, and everything that can be pressed.
- **Expected:** At least five strings are found and every one is a value of `apps/web/messages/en.json`. The page has `app-name`, `status-panel`, `status-heading`, `status-unavailable`, `status-unavailable-title`, `status-unavailable-body` and `status-retry`; every test id is kebab-case and used once; and the one thing that can be pressed has a test id.
- **Automation:** `qa/web/F01/S01-status-page-api-down.spec.ts`

### TC-F01-S01-051 When the API comes back, "try again" shows its name and version without the web app being restarted

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-7
- **Performed as:** Anyone who opens the web app in a browser; no account exists in this stage
- **Preconditions:** The web app is running and the API has been stopped with `infra/scripts/local-service.sh stop api`; the test confirms the API does not answer. The home page is open and shows the unavailable state.
- **Steps:**
  1. Start the API with `infra/scripts/local-service.sh start api`; do nothing to the web app.
  2. Press "try again".
- **Expected:** The page shows the service name and version the real API reports, and the unavailable state is gone. The web app recovered by itself.
- **Automation:** `qa/web/F01/S01-status-page-api-down.spec.ts`

### TC-F01-S01-052 pnpm contracts:check exits zero on the committed tree

- **Type:** build
- **Priority:** high
- **Covers:** AC-8
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** JDK 25 and Docker are available. Nothing under `apps`, `packages` or `docs/api` is uncommitted.
- **Steps:**
  1. Run `pnpm contracts:check` at the repository root.
  2. Copy the committed tree (`git archive HEAD`) to a temporary folder, run `pnpm install --frozen-lockfile` there, and run `pnpm contracts:check` in the copy.
- **Expected:** Both runs exit 0. The run in the repository changes no file. The run in the unaltered copy passing is what makes the failures of the next two cases the alteration's doing.
- **Automation:** `qa/checks/F01/S01-contract-drift.sh`

### TC-F01-S01-053 With docs/api/openapi.json altered, pnpm contracts:check exits non-zero and names it

- **Type:** build
- **Priority:** high
- **Covers:** AC-8
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** The copy of TC-F01-S01-052. The repository itself is never altered.
- **Steps:**
  1. In the copy rename a field in `docs/api/openapi.json` and run `pnpm contracts:check`.
  2. Restore it; add an endpoint to the document by hand; run the check.
  3. Restore it; delete the document; run the check.
- **Expected:** Each run exits non-zero and its output names `docs/api/openapi.json`. The check reports and does not repair: the altered file is as it was left.
- **Automation:** `qa/checks/F01/S01-contract-drift.sh`

### TC-F01-S01-054 With a generated client file altered, pnpm contracts:check exits non-zero and names it

- **Type:** build
- **Priority:** high
- **Covers:** AC-8
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites
- **Preconditions:** The copy of TC-F01-S01-052, restored. The repository itself is never altered.
- **Steps:**
  1. In the copy change a type in `packages/api-contracts/src/generated/schema.ts` and run `pnpm contracts:check`.
  2. Restore it; append a comment to it; run the check.
  3. Restore it; delete it; run the check.
  4. Restore it; add a hand-written file beside it; run the check.
- **Expected:** Each run exits non-zero and its output names the file that is stale: `schema.ts` the first three times, the hand-written file the fourth. The check does not rewrite what it reports. Afterwards the repository has no change.
- **Automation:** `qa/checks/F01/S01-contract-drift.sh`

### TC-F01-S01-055 In the web shell's source every user-visible string comes from a translation file and every pressable element has a data-testid

- **Type:** build
- **Priority:** high
- **Covers:** AC-9
- **Performed as:** QA, reading the build
- **Preconditions:** QA's scan, `qa/shared/scan-web-source.mjs`, which reads every component with the TypeScript parser and every translation file.
- **Steps:**
  1. Run the scan on an unaltered copy of the committed web app.
  2. Run it on ten copies with one thing broken in each: a sentence typed into a component; one written as an expression; one chosen by a condition; a spoken attribute typed in; a link with its test id removed, and with an empty one; a button added with none; the not-found page's link with its test id removed; a page title typed into the layout; an empty string in the translation file.
  3. Run it on the committed web app.
- **Expected:** The scan exits 0 on the unaltered copy and 1 on each broken copy, naming the file. On the committed web app it exits 0, having read every component (it says how many files, elements, pressable elements and strings it read, and refuses to pass if it read none).
- **Automation:** `qa/checks/F01/S01-strings-and-test-ids.sh`

### TC-F01-S01-056 The CI workflow runs every verification command on a pull request, and none of them is allowed to fail quietly

- **Type:** build
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** QA, reading the build
- **Preconditions:** QA's reader, `qa/shared/check-ci-workflow.mjs`, which reads `.github/workflows/ci.yml` and the stage's `verification.commands`. It runs nothing on GitHub.
- **Steps:**
  1. Run the reader on an unaltered copy of the committed workflow.
  2. Run it on eight copies with one thing broken in each: a command removed; a command followed by `|| true`; a command with `continue-on-error`; a command behind an `if`; no `pull_request` trigger; pull requests narrowed by path; two commands swapped; the whole job allowed to fail.
  3. Run it on the committed workflow.
- **Expected:** The reader exits 0 on the unaltered copy and 1 on each broken copy, saying what is wrong. On the committed workflow it exits 0: the workflow runs on every pull request, one job runs each of the eight verification commands with the stage's exact text and in its order, and no step or job is conditional or allowed to fail. This is what the file says; that GitHub does it is TC-F01-S01-057.
- **Automation:** `qa/checks/F01/S01-ci-workflow.sh`

### TC-F01-S01-057 A pull request on GitHub runs every verification command, and a failing command marks the pull request as failed

- **Type:** build
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** A developer opening a pull request on GitHub
- **Preconditions:** The repository has a GitHub remote, and a pull request can be opened against `main`.
- **Steps:**
  1. Open a pull request from a branch whose verification commands all pass, and read the checks it shows.
  2. Push a commit that makes one verification command fail (for example a string literal in a component, which fails `pnpm -r lint`), and read the checks again.
  3. Remove the commit.
- **Expected:** On the first pull request the workflow runs, each of the eight verification commands appears as a step, and the check is green. With the failing commit the step for that command fails, the later steps do not report success in its place, and the pull request's check is marked failed.
- **Automation:** deferred, because the repository has no GitHub remote, so no pull request can exist and the workflow has never run (the spec's "Done criteria" say so); unblocked by the owner creates the GitHub remote

### TC-F01-S01-058 Starts and stops that overlap leave the product running once, and local-down.sh still stops everything

- **Type:** functional
- **Priority:** medium
- **Covers:** AC-1
- **Performed as:** Two or three developers (or a person and an agent) at terminals on one checkout
- **Preconditions:** The product is stopped, with nothing of it left running. Found as BUG-F01-S01-001.
- **Steps:**
  1. Run `infra/scripts/local-up.sh`; 5 seconds later, while the first is building the API, run it again in a second terminal. Wait for both.
  2. Stop everything; run `infra/scripts/local-up.sh` three times at the same instant. Wait for all three.
  3. Stop everything; run `infra/scripts/local-up.sh`, and 5 seconds later `infra/scripts/local-down.sh`. Wait for both.
- **Expected:** In steps 1 and 2 every run exits 0 and prints the API's address. Afterwards health and the home page answer 200, there is exactly one API process, the processes the scripts recorded are the ones listening on the API's and the web app's ports, and no lock is left behind. In step 1 the second run says it is waiting for the first. In step 3 the stop exits 0 and afterwards nothing listens on the three ports and no API or web process is left.
- **Automation:** `qa/checks/F01/S01-overlapping-starts.sh`

### TC-F01-S01-059 A lock left by a script that was killed does not stop the next run, and is removed

- **Type:** resilience
- **Priority:** medium
- **Covers:** AC-1
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites, after a script was killed part-way (or the machine lost power)
- **Preconditions:** The product is stopped. `.local/run/lock` is a leftover: it names a process that does not exist, or a live process that did not start at the time it records (the fix for BUG-F01-S01-001 introduced the lock; this case guards its own new risk).
- **Steps:**
  1. Place a lock naming process `999999` with a 1970 start time; run `infra/scripts/local-up.sh`.
  2. Stop everything; place a lock naming process `1` (alive, started at another time); run `infra/scripts/local-up.sh`.
- **Expected:** Each run finishes within 4 minutes (it does not wait out the scripts' 15-minute limit on a lock nobody holds), exits 0 and leaves the product running once, as in TC-F01-S01-058, with the leftover lock gone.
- **Automation:** `qa/checks/F01/S01-overlapping-starts.sh`

### TC-F01-S01-060 A process-id file left by an earlier run never makes a stopped service look running, and its process is never stopped

- **Type:** functional
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** A developer at a terminal, on a machine with the documented prerequisites, after the machine restarted without `local-down.sh`
- **Preconditions:** The product is stopped. Two unrelated long-lived processes exist. Found as BUG-F01-S01-002.
- **Steps:**
  1. Write the first unrelated process's id alone into `.local/run/api.pid` (as the scripts used to write it), and the second's id with a start time that is not its own into `.local/run/web.pid`. Run `infra/scripts/local-up.sh`.
  2. Run `infra/scripts/local-down.sh`, then look for the two unrelated processes.
  3. Write the id of a process that has ended into `.local/run/api.pid` and run `infra/scripts/local-up.sh`.
  4. Stop the web app with `local-service.sh stop web`; write a third unrelated process's id into `.local/run/web.pid`; run `local-service.sh stop web` again.
- **Expected:** Step 1 exits 0, says `api: started` and `web: started` and never `already running`, notes that `api.pid` was left by an earlier run, and health and meta answer 200 from a process that is not the unrelated one. Step 2 exits 0, both unrelated processes are still alive, and no API or web process is left. Step 3 exits 0 and starts the API. Step 4 exits 0, says `web: not running`, leaves the unrelated process alive and does not touch the API.
- **Automation:** `qa/checks/F01/S01-stale-pid-file.sh`

### TC-F01-S01-061 With PostgreSQL frozen, health answers 503 within 5 seconds every time, and returns to 200 by itself when PostgreSQL answers again

- **Type:** resilience
- **Priority:** high
- **Covers:** AC-2
- **Performed as:** Any caller of the API (in practice a load balancer's health probe)
- **Preconditions:** The product is running and health answers 200. Found as BUG-F01-S01-003. PostgreSQL is frozen with the developer's test hook, `infra/scripts/local-service.sh pause postgres`.
- **Steps:**
  1. Ask for health every tenth of a second for 12 seconds; 3 seconds in, run `local-service.sh pause postgres`. (The hang came from a request handed a connection used an instant before, so requests must be in flight at the moment of the freeze.)
  2. Run `pause postgres` a second time; ask for health once more.
  3. Send fifty health requests at once.
  4. Ask for health about 10, 20 and 35 seconds into the freeze (past the driver's 30-second socket limit), and ask for meta.
  5. Run `local-service.sh resume postgres`, and poll health. Run `resume postgres` again.
  6. Stop PostgreSQL and run `pause postgres`; then start it again.
- **Expected:** `pause` exits 0, the container is paused and its port still accepts connections. At least 60 requests are sent across the freeze, some answer 503 (the freeze was seen), every one answers 200 or 503, and none takes 5 seconds or longer: no request hangs. A second `pause` exits 0. Every later health request, the fifty at once included, answers 503 in under 5 seconds with the database component `DOWN` and no internals in the body. Meta answers 200. After `resume`, health answers 200 within 30 seconds from the same API process; a second `resume` exits 0. `pause` on a stopped PostgreSQL exits non-zero.
- **Automation:** `qa/checks/F01/S01-database-frozen.sh`

### TC-F01-S01-062 A pnpm that is not Corepack's never rewrites pnpm-lock.yaml: install stops and says why, and local-up.sh asks for Corepack

- **Type:** build
- **Priority:** medium
- **Covers:** AC-1
- **Performed as:** A developer whose pnpm was installed on its own, with no `corepack` on the PATH
- **Preconditions:** A copy of the committed tree outside the repository. A pnpm that is not the pinned version: QA's own dependency `pnpm` 11.6.0 (`qa/node_modules/pnpm`), so the case does not depend on what the machine has. A PATH holding it and Node.js and no `corepack`. Found as BUG-F01-S01-004.
- **Steps:**
  1. In the copy run `pnpm install --frozen-lockfile` with that PATH.
  2. In the copy run `infra/scripts/local-up.sh` with that PATH.
  3. In the copy run `corepack pnpm install --frozen-lockfile` with the normal PATH.
- **Expected:** Step 1 exits non-zero and names the pinned version. Step 2 exits non-zero, names Corepack, and leaves no process-id file. Step 3 exits 0. After each step `pnpm-lock.yaml` is byte for byte what was committed.
- **Automation:** `qa/checks/F01/S01-pinned-pnpm.sh`

### TC-F01-S01-063 Every prerequisite local-up.sh refuses to start without is a row of README.md's prerequisites

- **Type:** build
- **Priority:** medium
- **Covers:** AC-1
- **Performed as:** A developer setting up a machine from `README.md`
- **Preconditions:** The product is running (so that taking `ps` away from scripts that did not check for it changes nothing). Found as BUG-F01-S01-005.
- **Steps:**
  1. Read the table under "Prerequisites, on the machine" in a copy of `README.md`, and in a copy with the curl row removed, and say which of JDK, Node.js, Docker, curl and ps no row names.
  2. Do the same for the committed `README.md`, and check that the table says pnpm comes through Corepack.
  3. Run `infra/scripts/local-up.sh` with a PATH that has everything but curl, then everything but ps.
- **Expected:** The unaltered copy and the committed README name all five; the copy without the curl row lacks exactly curl, which proves the reader. The table mentions Corepack. Without curl, and without ps, `local-up.sh` exits non-zero, names the missing tool and points at `README.md`, and the README's table names that tool.
- **Automation:** `qa/checks/F01/S01-prerequisites-documented.sh`
