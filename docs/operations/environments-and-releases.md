# Environments and releases

The environments this product runs in, what a test may and may not touch, and the rules this
product imposes on a release. The release procedure itself (prepare, verify, cut) is in the
`salon-sdlc` skill.

## Environments

| Environment | State | Who may touch it |
| --- | --- | --- |
| Local | Created by stage F01-S01. The default for every build, test, QA run and bug fix | Any role |
| Local production-like stack | F01-S05's container images, HTTP edge and two instances of each app, on this machine | Any role |
| Staging | Real host not created yet; uses F01-S05's stack files. Holds synthetic salons only | QA, when the owner's current message names staging |
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
| `infra/scripts/local-salon.sh colour <slug> <#RRGGBB>` | For tests (CR-013): sets one synthetic salon's stored brand colour, as the owning role, changing nothing else. Refuses an unknown slug, a colour that is not `#RRGGBB` and a wrong argument count. Seen on the next request; `local-reset.sh` restores the seeded colours |
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

## The local production-like stack

F02 identity startup uses three independent 32-byte secrets, each encoded as 64 hexadecimal
characters: `SALON_IDENTITY_SIGNING_KEY`, `SALON_IDENTITY_CHALLENGE_KEY` and
`SALON_IDENTITY_ENCRYPTION_KEY`. Outside `local`, startup requires them explicitly; the stack
passes them from its ignored environment file. Local startup retains random keys in ignored
`.local/identity-keys.properties` with mode `0600`; `SALON_IDENTITY_LOCAL_KEY_FILE` overrides the
path, and the startup scripts set the default to an absolute path. Preserve this file when
restarting the API so signatures, refresh credentials and abuse-counter hashes remain valid.
Do not print or commit keys or synthetic account fixture files.
Customer code limits use `SALON_IDENTITY_CODE_NUMBER_LIMIT`, `SALON_IDENTITY_CODE_DEVICE_LIMIT`,
`SALON_IDENTITY_CODE_ADDRESS_LIMIT` and `SALON_IDENTITY_CODE_DAILY_LIMIT`, defaulting to 3, 10, 20
and 1000. Focused local tests may override them explicitly, then must restore the defaults. Local
SMS/WhatsApp adapters capture encrypted outbox delivery only; a nonlocal deployment has no real
sender configured and returns `provider_unavailable` rather than claiming that a code was sent.
The region phone numbers are checked against is `SALON_BRANDING_PHONE_REGION`, `LK` by default
(decision 084); it is not a secret and every environment may leave it unset.

F03 operator console (F03-S01). The console is `http://console.localhost:3100/console` locally
(the stack serves `console.stack.localhost`). The API connects for platform work as a separate
non-owner role without `BYPASSRLS`: `SALON_DB_PLATFORM_USER` and `SALON_DB_PLATFORM_PASSWORD`,
with `SALON_DB_PLATFORM_POOL_SIZE` defaulting to 4; startup refuses a missing, shared, table-owning
or RLS-bypassing role. `infra/scripts/local-up.sh` and `infra/stack/provision-roles.sql` provision
`salon_app` and `salon_platform` without resetting data. Operator authority uses five independent
keys in the same format as the identity keys: `SALON_OPERATOR_SIGNING_KEY`,
`SALON_OPERATOR_CHALLENGE_KEY`, `SALON_OPERATOR_ENCRYPTION_KEY`, `SALON_OPERATOR_PROXY_KEY` and
`SALON_OPERATOR_FINGERPRINT_KEY` (idempotency receipts). Locally they are retained in ignored
`.local/operator-keys.properties` (mode `0600`, override `SALON_OPERATOR_LOCAL_KEY_FILE`); the
web app needs the proxy key, which the local launcher reads from that file. Outside `local` all
five are required. An existing ignored `.env.stack` written before F03 lacks the platform role and
operator keys, and the stack refuses to start until they are added from `.env.stack.example`.

No start creates an operator. Outside `local`, create one with the offline command
`infra/scripts/operator-bootstrap.sh <file>`, where the file (mode `0600`) holds email,
displayName, role and password; it runs the API jar with the `operator-bootstrap` profile.
Locally, loopback-only hooks outside OpenAPI seed and reset synthetic operators and arm a controlled
provisioning failure: `POST /local/operator/seed`, `DELETE /local/operator/fixtures` and
`PUT|GET /local/operator/bootstrap-failure` (`{step: owner|brand|receipt|null}`). Keep fixture
credentials in ignored files and never print them. The web build fails if an environment file, a
key file or a known key value appears in its output (`apps/web/scripts/scan-artifacts.mjs`).

F03 billing (F03-S02). `SALON_BILLING_PROVIDER_KEY` (64 hexadecimal characters) is optional:
without it there is no billing provider and the provider-event intake answers 404, so outside
`local` billing emails and charges wait in the outbox until a provider exists (D-06). Locally the
key is retained in ignored `.local/billing-keys.properties` (mode `0600`, override
`SALON_BILLING_LOCAL_KEY_FILE`); `SALON_BILLING_LINK_ORIGIN` sets the origin of email links
(local `http://%s:3100`). The billing job runs every minute. Local hooks, loopback-only and outside
OpenAPI: `PUT|GET /local/operator/billing/provider-outcomes/{salonId}`,
`POST /local/operator/billing/provider-events/signature`, `GET|DELETE /local/operator/emails`
(in memory until cleared or the API restarts), `POST /local/operator/billing/run`,
`PUT|GET /local/operator/billing/interrupt`, `POST /local/operator/unbilled-salons` and
`PUT /local/operator/operators/active`. F02's identity hooks work on every local salon.

F03 lifecycle (F03-S03). Export archives are encrypted with `SALON_EXPORT_KEY` (required outside
`local`; locally retained in ignored `.local/export-keys.properties`), and `SALON_EXPORT_LINK_ORIGIN`
sets the origin of the Owner's download link. The stack's compose file and `.env.stack.example`
require the export key; an older `.env.stack` must have it added before the stack starts. The
export and deletion jobs run every minute. Local hooks for running them now, interrupting them,
reading a salon's background state and resetting to the Alpha/Beta/Gamma baseline are under
`/local/operator` (paths in the F03-S03 handoff); the reset removes every post-baseline salon.
All three web BFFs read request bodies through one byte-counting reader (`lib/http/bounded-json.ts`).

F03 support sessions (F03-S04). The support credential is signed with `SALON_SUPPORT_SIGNING_KEY`
(required outside `local`, retained locally beside the other keys) and the console's entry link
uses `SALON_SUPPORT_LINK_ORIGIN`; both are in `.env.example`, `.env.stack.example` and the stack
compose file, so an older `.env.stack` needs them added. Expired sessions are ended by a job, with a
loopback hook to run it now (path in the F03-S04 handoff).

In the Codex execution environment, a detached child can be reaped after the launching command
ends. Keep a foreground shell session alive while running the existing local startup scripts,
and verify HTTP readiness from another call before relying on the process.

F01-S05 provides `infra/stack/compose.yaml`, independent of `salon-local`. The local stack serves
HTTP only; it does not create certificates, redirect to HTTPS or test TLS. Staging keeps public TLS
in its staging edge configuration. Copy
`.env.stack.example` to the ignored `.env.stack` and fill in its required secrets. Stack scripts
read that file, rather than shell overrides; missing settings or images cause a named refusal
before services start. The `staging` Spring profile here describes container behaviour; running
it locally does not create or certify a real staging environment.

| Script | Does |
| --- | --- |
| `infra/scripts/images-build.sh [version]` | Builds non-root API and web images, tagged with the version and source commit; verifies labels and absence of environment files |
| `infra/scripts/images-scan.sh [image ...]` | Scans the two current-version images by default. Exit 0 when clean; 1 on a HIGH or CRITICAL vulnerability with a fix, naming it; 2 when a scan could not complete, naming the image and Trivy's message (BUG-F01-005) |
| `infra/scripts/stack-up.sh` | Provisions the two database roles, runs migrations once, then starts the collector, two API and two web instances and the HTTP edge; waits for readiness |
| `infra/scripts/stack-seed.sh` | Explicitly creates alpha, beta and gamma without duplicates; no salon is seeded by stack startup |
| `infra/scripts/stack-seed.sh add <slug> [name] [colour]` | Adds a synthetic salon while the stack runs |
| `infra/scripts/stack-deploy.sh <version>` | Checks both images first, migrates once, then drains and replaces one instance at a time, waiting for each to be ready and then 15 s for it to warm up before the next. A third web instance, `web-surge` (Compose profile `surge`), serves on the new version while `web-1` and `web-2` are replaced, so two web instances always serve; it is removed when the deployment ends, also when it fails, and one left by an interrupted deployment is removed first. A deployment takes about 5 minutes. Deploying the preceding version rolls back application images while retaining newer migrations (BUG-F01-009) |
| `infra/scripts/stack-down.sh` | Stops the `salon-stack` containers and networks, retaining data; `--delete-data` explicitly removes this stack's volumes and telemetry |
| `infra/scripts/lint-workflows.sh` | Checks Actions workflows with actionlint and rejects third-party actions without a full commit pin |

Default addresses are `http://stack.localhost:8089/` for status,
`http://alpha.stack.localhost:8089/` (also beta and gamma) for salons, and
`http://alpha.stack.localhost:8089/api/v1/salon` for the API. Only the edge publishes a host port,
on `127.0.0.1` by default; the database, apps and collector stay on Docker networks. On the edge
network the edge and every instance (`api-1`, `api-2`, `web-1`, `web-2`, `web-surge`) have fixed
addresses at the top of `SALON_STACK_EDGE_SUBNET` (a /16 to /26 network address; Docker allocates
other containers from its lower half only), derived by `infra/scripts/lib/stack.sh`. The edge
proxies to those addresses and the web app calls the edge at its address, never by a name, so an
instance that is being replaced can never be resolved outside the stack. The edge balances to the
instance with the fewest requests in flight, takes an instance out after 3 failed health checks of
5 s each, and writes one JSON access-log line per request to `docker logs salon-stack-edge`
without headers, client address, query string or export-link token. Moving a running stack to
these addresses recreates its edge network once: `stack-down.sh`, then `stack-up.sh`. Local
development and QA make no TLS, certificate, HTTPS redirect or HSTS assertion. The staging edge
configuration uses public DNS-challenge certificates; certificate issuance, HTTPS, redirects and
HSTS are tested only after the owner requests deployment to the VPS staging environment.

Container and network names carry `salon-stack`; its persistent data volume is
`salon-stack-postgres-data` by default. The optional `SALON_STACK_DB_VOLUME` in `.env.stack` may
name an isolated local QA volume; an absent or empty value preserves the default. This setting
changes only the PostgreSQL volume, never the edge volumes or the local development database.
The full stack, deployment and rollback scripts all use the explicit setting. The earlier QA
volumes with historical probe migrations were deleted on 2026-10-30 at the owner's request; a QA run
starts from a new, empty volume. This stack never shares the local
database. `infra/scripts/test-stack-db-volume.sh` verifies defaults and custom isolation.
An administrator provisions a non-superuser owning role and an application role that owns no
table and cannot bypass RLS. Only the migration container receives the owning role's password;
API instances receive the application role's credentials. The stack profile exposes no local
hooks, metrics endpoint or OpenAPI endpoint and starts with no salons in a fresh database.

Ignored `.stack/deployed.env` records instance versions, `.stack/logs` holds script logs and
`.stack/telemetry/{traces,metrics}.jsonl` receives OTLP exports. Docker logs hold structured app
request logs. Stack state and `.env.stack` belong to the checkout that started it; before deleting
a worktree, stop its stack or restart it from the retained checkout so no bind mount depends on
the removed directory, and preserve local settings and required evidence.

`Delivery` publishes images to GHCR only after successful push CI on `main`, building the exact
CI revision. Deployment is a separate manual workflow input targeting the GitHub `staging`
environment. It requires `STAGING_SSH_HOST`, `STAGING_SSH_USER`, `STAGING_SSH_KEY` and
`STAGING_SSH_KNOWN_HOSTS` and refuses with their names when missing. Before using it, the host
administrator supplies `.env.stack`, registry access and an initial stack. Feature QA and a PR
merge do not run this deployment. The first real host deployment, public TLS and external
operational checks remain deferred until hosting, domain, access and an owner request exist. The
deferred TLS case is `TC-F01-002` in the F01 feature case register.

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
