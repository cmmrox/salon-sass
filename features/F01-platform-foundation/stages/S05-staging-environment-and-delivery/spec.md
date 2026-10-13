# F01-S05 Staging environment and delivery pipeline

## Objective

After this stage the platform can be deployed to any host that runs containers, without a minute
of downtime. Production images exist for the API and the web app. In staging, an edge serves every
salon subdomain over HTTPS using a wildcard certificate. A deployment replaces each instance while
requests keep being answered, and can be rolled back the same way. Local development and QA use an
HTTP-only edge with no local certificate setup or TLS assertions (CR-005). Deployment and public
TLS verification on the real staging host remain optional and require an explicit owner request.

## Read first

- `docs/decisions/DECISION_LOG.md`: decisions 018, 022, 028, 030 and 034
- `docs/changes/CR-005-local-http-staging-tls.md`: local HTTP-only scope and deferred staging TLS
- `docs/operations/environments-and-releases.md`: environments, what may be touched, the rules for
  a release (backward-compatible migrations, a rollback plan)
- `docs/architecture/ARCHITECTURE.md`: web addresses, environments
- `docs/architecture/multi-tenancy.md`: "Salons and their hostnames", rule T-3 (the two database
  roles) and T-5, logs (T-8)
- `docs/engineering/backend.md`: health and service metadata, the database roles the API refuses to
  start with, logs and traces (as corrected after F01-S04)
- `docs/engineering/web.md`: tenant routing, local startup and the serving method this stage chooses
- `docs/architecture/tech-stack.md`: the stack, the rule for a new tool, the CI system
- QA, at the feature pass: `docs/qa/test-types.md` (`build`, `resilience`, `security`),
  `docs/qa/automation.md` (k6)

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| BRD-03 | Every salon receives a platform subdomain with a secure certificate at onboarding | In part: a wildcard certificate covers every salon's subdomain, so a salon created while the platform runs is served over HTTPS at once. Creating the salon at onboarding is F03; a certificate from a public authority on a real domain is the deferred deployment |
| NFR-18 | Backend and web are deployable at any time without downtime; older app versions keep working | In full for the platform's deployment; older app versions are the mobile milestone's |
| NFR-07 | Encryption in transit and at rest; secrets encrypted with managed keys; least-privilege access; dependency and image scanning | In part: HTTPS at the edge, only the edge reachable, non-root images, secrets outside the repository and the images, image scanning. Encryption at rest and managed keys come with the hosting provider (D-08) |
| NFR-16 | Every log line, trace and error carries the tenant | In part: the web app's own logs, the trace shared by the web app and the API, and logs, traces and meters collected in the stack. Alert rules and dashboards need the monitoring service chosen with D-08 |

## Scope

**Images (`apps/api`, `apps/web`, `infra/`)**

- A production image for the API and one for the web app, built by `infra/scripts/images-build.sh`
  and tagged with the version and the commit. They run as a user other than root, carry no secret
  and no `.env` file, and report their version and commit (`GET /api/v1/meta`).
- The web app's production image is served by Next.js's standalone output (decision 030). The local
  environment keeps `serve.mjs`.
- `infra/scripts/images-scan.sh` scans both images and fails on a critical or high vulnerability
  that has a fix.

**The stack (`infra/stack/`)**

- One Compose project, `salon-stack`, separate from `salon-local` in every name, port and volume:
  an edge, two API instances, two web app instances, PostgreSQL and an OpenTelemetry collector.
  Only the edge publishes ports. The local HTTP edge and staging TLS edge use environment-specific
  Caddy configuration; app images and tenant routing are shared.
- The edge is Caddy (decision 030). Locally it serves the platform domain and
  `*.<platform domain>` over HTTP and passes the request's `Host` to the apps unchanged. Staging
  configuration serves these names over HTTPS with a public wildcard certificate, redirects HTTP
  to HTTPS and sends HSTS using DNS challenge credentials. No local certificate authority, trust
  setup or TLS configuration is required.
- The local platform domain is `stack.localhost`, served on port 8089 over HTTP.
- The two database roles are provisioned by `infra/postgres/provision-roles.sql`: the owning role,
  which is not a superuser, and the application's role, as T-3 requires. It can be run again
  without harm, and is what a real host's administrator runs once.
- The apps trust forwarded headers from the edge only, and still take the salon from `Host` alone.
- The stack's Spring profile is `staging`: no local hook, no automatic seed, and no start without
  every required secret. `infra/scripts/stack-seed.sh` creates the three synthetic salons of
  `docs/qa/test-plan.md` and can add one more for a test, as the owning role.
- Scripts: `stack-up.sh`, `stack-down.sh`, `stack-seed.sh`, and `stack-deploy.sh <version>`.
  `.env.stack.example` lists every setting; the real file is never committed.

**Deployment without downtime**

- `stack-deploy.sh <version>` runs the new version's migrations while the old version keeps
  serving, then replaces the instances one at a time. Each instance is taken out of the edge's
  rotation (its readiness fails), drains, stops, and is replaced; the next is replaced only once the
  new one is ready. Deploying the previous version the same way is the rollback. The script refuses
  a version whose images do not exist and leaves the stack as it was.
- Readiness and liveness are separate health groups. The edge sends traffic only to an instance
  that is ready, and retries an idempotent request on the other instance if its first one fails to
  connect.

**Observability (NFR-16)**

- The web app logs one JSON object per line on the server, one line per request with its method,
  its path without the query string, its status, its duration, the salon's slug as `tenant.slug`
  when the hostname names a salon, and `trace.id`. It sends a W3C `traceparent` with each call to
  the API, so the web app's line and the API's lines of one page view share a trace id.
- The collector receives the API's traces and meters over OTLP and writes them where a test can
  read them (its debug or file output). Container logs are read through Docker.

**Pipeline (`.github/workflows/`)**

- A delivery workflow that, after CI passes on `main`, builds the images with the same scripts,
  scans them, and publishes them to the repository's container registry. Its deployment job is
  started by hand, targets the GitHub environment `staging`, runs `stack-deploy.sh` against the
  host named in that environment's secrets, and stops with a clear message when they are missing.
  Nothing runs until the owner creates the remote and the environment.
- `infra/scripts/lint-workflows.sh` checks every workflow file with actionlint.
- CI's steps follow `verification.commands` as the comment in `ci.yml` says.

Every tool this stage uses that is not installed (Caddy, the OpenTelemetry collector, Trivy,
actionlint) runs from its official container image. Nothing is installed on the machine.

## Out of scope

- Deploying to a real host, a real domain and DNS, and a certificate from a public authority: the
  deferred deployment, unblocked by D-08, D-02 and access to the host (decision 018).
- Encryption at rest, managed keys, backups and point-in-time recovery: they depend on the hosting
  provider (D-08), and are planned with the deferred deployment.
- Alert rules and dashboards: the monitoring service is chosen with D-08. The meters exist after
  F01-S04 and are collected here.
- A domain the salon owns: a later release.
- Production itself: the same files serve it, after the first release.
- Creating a salon through the product: F03.

## Acceptance criteria

The local stack is started by `stack-up.sh` and seeded by `stack-seed.sh`. Its hostnames are
`<slug>.stack.localhost` on the HTTP edge. Staging-only certificate issuance and TLS behavior are
outside local acceptance and remain deferred in `TC-F01-002`.

- AC-1: `images-build.sh` builds an API image and a web image tagged with the version and the
  commit. A container from each runs as a user other than root, contains no `.env` file, and
  `GET /api/v1/meta` through the edge reports that version and commit. (NFR-07, NFR-18)
- AC-2: `images-scan.sh` exits zero on the images built from the stage's tree, and exits non-zero,
  naming the vulnerability, when given an image that has a critical or high vulnerability with a
  fix. (NFR-07)
- AC-3: In the local stack, `http://alpha.stack.localhost:8089/` shows alpha's shell and
  `http://alpha.stack.localhost:8089/api/v1/salon` returns alpha's brand. The same holds for beta
  and gamma, the bare platform domain shows the status page, and an unknown slug and `console` get
  the salon-not-found page of F01-S03. No local HTTPS, certificate, redirect or HSTS assertion is
  made. Staging-only TLS behavior is deferred in `TC-F01-002`. (BRD-03, NFR-07)
- AC-4: A salon added by `stack-seed.sh` while the local stack runs is served over HTTP on its first
  request, with no restart. Certificate issuance is staging-only and is not checked locally.
  (BRD-03)
- AC-5: Only the edge's ports are published on the machine: the API, the web app, PostgreSQL and
  the collector cannot be reached from the host except through the edge. A request through the edge
  on alpha's hostname carrying beta's slug in `X-Forwarded-Host`, `X-Tenant-Id` or a query parameter
  shows alpha, on the web and on the API. (NFR-07, NFR-01)
- AC-6: In the stack's database, the owning role is not a superuser; the application's role is not
  a superuser, has no `BYPASSRLS`, owns no table and is refused a `SELECT` on `tenant`. Running
  `provision-roles.sql` a second time changes nothing and does not fail. (NFR-07, NFR-01)
- AC-7: The stack refuses to start, naming the setting, when a required secret is missing. In the
  stack every path under `/local/` and `/actuator/metrics` answers 404, and no salon exists before
  `stack-seed.sh` runs. No file in the repository holds a value from the real environment file.
  (NFR-07)
- AC-8: With k6 sending at least 50 virtual users' requests continuously to `/` and
  `/api/v1/salon` over the three salons' hostnames, `stack-deploy.sh` from version N to version N+1
  completes with zero failed requests, zero connection errors and zero responses showing another
  salon. `GET /api/v1/meta` reports N before, both during, and only N+1 after. Deploying N again
  (the rollback) under the same traffic gives the same result, and the database is left as N+1's
  migrations made it. (NFR-18, NFR-01)
- AC-9: Under the same traffic, stopping one API instance and one web instance (`docker stop`)
  causes no failed `GET`, and starting them again returns them to the rotation once ready, with no
  action at the edge. (NFR-18)
- AC-10: `stack-deploy.sh` given a version whose images do not exist exits non-zero before stopping
  anything, and the stack keeps serving the version it had. (NFR-18)
- AC-11: A page view on alpha's hostname produces one JSON log line from the web app with
  `tenant.slug` `alpha` and a `trace.id`, and the API's log lines for the call it made carry the same
  `trace.id` and alpha's `tenant.id`. The collector's output holds that trace's API spans with
  alpha's `tenant.id`, and the meters of F01-S04. No web log line carries a query string, a cookie
  or an authorisation header. (NFR-16, NFR-01)
- AC-12: `lint-workflows.sh` passes on every workflow file. The delivery workflow builds and scans
  with `images-build.sh` and `images-scan.sh`, publishes only after CI succeeds on `main`, and its
  deployment job runs only when started by hand, against the environment `staging`, and fails with
  a message naming the missing secret when that environment has none. (NFR-18, NFR-07)

## Design notes

- **Shared app stack, environment-specific edge.** Local QA uses an HTTP Caddy configuration. The
  real staging host uses a separate TLS Caddy configuration with public DNS-challenge certificates.
  Both preserve the incoming `Host`; no local certificate is created or trusted.
- **Both versions answer at once during a deployment.** That is why migrations must be backward
  compatible (`docs/engineering/backend.md`). Flyway runs once per deployment, from the new
  version, before its first instance starts serving; an instance starting while another migrates
  waits for it.
- **The web app is served by Next.js's standalone output in the image** and listens on every
  interface inside the container. Check that the status codes of decision 028, the caching rules of
  F01-S03 and the forwarding of the hostname to the API behave the same in the image as locally.
  Report any difference in the handoff.
- **The edge passes `Host` unchanged.** The apps trust `X-Forwarded-Proto` and `X-Forwarded-For`
  from the edge's network only, and still ignore `X-Forwarded-Host` (T-5).
- **Graceful drain.** An instance stops answering ready before it stops taking requests, and keeps
  answering what it accepted. Use the shutdown grace period of the API (F01-S04's dispatcher and
  scheduler stop claiming work), and give the web app the same.
- **Report in the handoff:** the image sizes, the base images and their versions, the ports, the
  settings in `.env.stack.example`, the time a deployment takes, and each tool's image and version.
  The lead adds them to `docs/architecture/tech-stack.md` and
  `docs/operations/environments-and-releases.md`.
- **Developer tests that ship with the stage:** a script check, run by the gates, that builds the
  images and asserts the non-root user and the absence of `.env`; the web app's log line and
  `traceparent` forwarding as unit tests; the API's readiness group as an integration test. The
  traffic-under-deployment checks of AC-8 and AC-9 are run by the developer on the running stack
  before handing over and recorded in the handoff; QA automates them at the feature pass.

## API and data changes

No endpoint changes. Health gains a readiness group and a liveness group
(`/actuator/health/readiness`, `/actuator/health/liveness`), showing status only. No table changes.

## UI and UX

Not applicable for this stage. The pages are F01-S03's, served through the edge.

## Risks and test focus

| Risk | Test type and why |
| --- | --- |
| A deployment drops requests, or old and new versions disagree mid-deployment | `resilience` and `concurrency`: k6 during deploy, rollback and instance loss (AC-8, AC-9, AC-10) |
| A salon resolves to the wrong tenant on the local edge | `tenant-isolation` and `security` (AC-3, AC-5); staging TLS is deferred to TC-F01-002 |
| Something other than the edge is reachable, or a forwarded header picks the salon | `security` and `tenant-isolation` (AC-5) |
| The production-like database runs with a role that can bypass isolation | `tenant-isolation` (AC-6) |
| A secret or a local hook reaches an image or a non-local profile | `security` and `build` (AC-1, AC-7) |
| The web image behaves differently from the local web app | `functional` through the edge (AC-3) |
| Logs and traces lose the salon across the web app and the API | `security` (AC-11) |
| The pipeline cannot run, or deploys without being asked | `build` (AC-12) |

`responsive`, `accessibility`, `branding`, `permissions`, `payment-integrity`, `mobile` and `load`
(no response-time target) do not apply beyond what F01-S03 already covers.

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
infra/scripts/images-build.sh
infra/scripts/lint-workflows.sh
qa/scripts/sdlc selftest
```

## Done criteria

- The developer has checked every acceptance criterion on the running stack and recorded it in the
  handoff, with the measurements AC-8 and AC-9 asked for.
- The verification commands are green locally.
- Before closing, the lead has added the stack (addresses, start, stop, deploy and seed commands,
  data rules) to `docs/operations/environments-and-releases.md`, the images and tools to
  `docs/architecture/tech-stack.md`, the serving method to `docs/engineering/web.md`, and the
  readiness groups and forwarded headers to `docs/engineering/backend.md`; and the first
  deployment to a real host is recorded as a deferred item of F01 with what unblocks it.
