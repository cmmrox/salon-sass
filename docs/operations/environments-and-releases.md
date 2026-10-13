# Environments and releases

The environments this product runs in, what a test may and may not touch, and the rules this
product imposes on a release. The release procedure itself (prepare, verify, cut) is in the
`salon-sdlc` skill.

## Environments

| Environment | State | Who may touch it |
| --- | --- | --- |
| Local | Created by stage F01-S01. The default for every build, test, QA run and bug fix | Any role |
| Staging | Not created yet (F01-S05). Will hold synthetic salons only | QA, when the owner's current message names staging |
| Production | Not created yet. Hosting provider and region are open decision D-08 in the BRD | The lead, when the owner's current message names production |

- An unqualified "run it", "test it", "QA it" or "fix it" means local.
- **Staging deployment is optional and manual.** A feature's QA pass, closure or GitHub merge does
  not require or trigger a VPS deployment. The owner may merge two or three features, then request
  a staging deployment of a chosen version containing them. Local QA and merge confirmation
  permit the next feature to start; staging-only cases remain explicitly deferred and not certified.
- Permission to touch staging or production is given per request. It never carries over from an
  earlier message, and "cut a release" is not permission to deploy.
- When an environment is created, the stage that creates it reports its addresses, its start and
  stop commands and its data rules, and the lead adds them to this file.

## The local environment

Created by stage F01-S01. Everything runs on the host except PostgreSQL, which runs in Docker.

| Script | Does |
| --- | --- |
| `infra/scripts/local-up.sh` | Builds and starts PostgreSQL, the API and the web app, and prints each address. Exits non-zero, naming what is missing, when a prerequisite is not met |
| `infra/scripts/local-down.sh` | Stops all three. The container is removed; the data volume is kept |
| `infra/scripts/local-reset.sh` | Deletes the database's data and starts from empty |
| `infra/scripts/local-service.sh <start\|stop> <postgres\|api\|web>` | Takes one service down or brings it back, for tests |
| `infra/scripts/local-salon.sh <suspend\|activate> <slug>` | For tests: suspends or reactivates one synthetic salon, as the owning role, changing its status and nothing else. Refuses a slug that is not a salon. Seen on the next request; `local-reset.sh` brings every salon back active |
| `infra/scripts/local-service.sh <pause\|resume> postgres` | For tests: freezes PostgreSQL so that it accepts connections and answers nothing, then thaws it (`start postgres` also thaws it) |

| Service | Default address | Variable |
| --- | --- | --- |
| Web | `http://localhost:3100` | `SALON_WEB_PORT` |
| API | `http://localhost:8090` | `SALON_API_PORT` |
| PostgreSQL | `localhost:5440`, database `salon` | `SALON_POSTGRES_PORT` |
| A salon | `http://<slug>.localhost:<port>`, for example `http://alpha.localhost:8090/api/v1/salon` | `SALON_PLATFORM_DOMAIN` |

- Every setting is in `.env.example`; a variable in the shell wins over `.env`. The ports avoid the
  usual 3000, 8080 and 5432 because other projects on a developer's machine tend to hold them.
- Docker names: Compose project `salon-local`, container `salon-postgres`, volume
  `salon-postgres-data`. Containers of other projects on the machine are never touched.
- Process ids are in `.local/run` and logs in `.local/logs`; neither is committed. A script trusts a
  process id file only while the process it names is still the one it started (checked by start
  time, read in UTC whatever `TZ` the starting shell had), so it never stops an unrelated process.
- **The API's log** (`.local/logs/api.log`) is one ECS JSON object per line, with `tenant.id`,
  `trace.id` and `span.id`.
- **Local test hooks** are served by the API in the `local` profile only: the test clock under
  `/local/clock`, the probe under `/local/probes`, and `/actuator/metrics`. Every other profile
  answers 404. How to use them is in `docs/qa/test-plan.md`.
- `TZ=<zone> infra/scripts/local-service.sh start api` starts the API under another machine time
  zone; the API runs in UTC whatever it is.
- The scripts take turns: a second run waits for the first to finish rather than racing it.
- **Two database roles**, both with local-only passwords in `.env.example`: the owning role
  (`SALON_DB_USER`, `salon`) runs the migrations and owns every table; the application role
  (`SALON_DB_APP_USER`, `salon_app`) is what the API connects as, and cannot get round row-level
  security. The scripts create the application role from `infra/postgres/application-role.sql`.
  Elsewhere the application role must exist before the first migration, and the owning role must
  not be a superuser (F01-S05).
- **The three synthetic salons** of `docs/qa/test-plan.md` are seeded in the `local` profile only,
  every time the API starts, without duplicating. No other profile creates a salon. Alpha and beta
  are in `Asia/Colombo`, gamma in `Europe/London`; a database seeded before F01-S04 gets gamma's zone
  on the next start.
- **Loopback only by default.** The API and the web app listen on `127.0.0.1` and `::1`.
  `SALON_LISTEN_ON_NETWORK=true` makes both listen on every interface, for testing from a phone on
  the same network; anyone else on that network can then reach them. The web app takes the salon
  from the hostname, so a phone asking for the bare network address sees "salon not found"; it
  needs a hostname that resolves to this machine, with `SALON_PLATFORM_DOMAIN` set to its parent.
- The local web app is started by `apps/web/scripts/serve.mjs`, a small custom server, because
  `next start` binds a single address.
- The Spring profile is `local` by default. `SPRING_PROFILES_ACTIVE=<name>` with
  `local-service.sh start api` starts the API as it would run elsewhere.
- **A QA run needs the product running.** Start it with `local-up.sh` before
  `qa/scripts/sdlc run`; the automation refuses to start otherwise.
- **Restart after rebuilding the runtime artifacts.** Maven `verify` repackages the jar used by
  the local API. Replacing it while that process is running can break later class loading even
  though health initially answers. Stop the API before repackaging; start it from the completed
  build before QA. Restart the web app after rebuilding its production output as well. Passing
  build gates does not refresh an already-running process.

## What a test or a developer may use

- **Payments:** the PayHere sandbox only. Never a live merchant account, never a real card.
- **Messages:** SMS and WhatsApp providers are switched to a local capture or a sandbox. Nothing run
  in development or QA may send a real message or cost money.
- **Salons:** the fixed synthetic salons in `docs/qa/test-plan.md`. Never real salon or customer data.
- **App stores:** nothing is submitted to Apple or Google by a build or a test run.
- **Load:** a load or stress test runs against the local machine only, unless the owner's current
  message names another target.

## Versions

The platform (API and web, deployed together) carries one version. Before the first public launch
it stays below `v1.0.0`, and a release with one or more new features gets one MINOR bump, including
a batch of several features. The customer app carries its
own store version and build number; a release manifest records which app version it was verified
with.

## Rules this product imposes on a release

- **Old app versions stay installed for months.** Database migrations and API changes are backward
  compatible; a breaking change needs a minimum-version bump (APP-11) and a MAJOR version.
- **Backend and web first, apps after.** A JavaScript-only app change can go over the air; a native
  change is a store release.
- **Each salon's dedicated app is its own listing** in the salon's own developer accounts. A release
  never publishes a salon's app from the operator's accounts (BRD section 7.4).
- **A rollback plan exists before a deployment starts:** the previous image or build, and a
  statement of whether the migrations can stay in place.
- **Milestones:** the web application is released before any work starts on the customer app. See
  `docs/ROADMAP.md`.
