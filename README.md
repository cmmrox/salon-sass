# Salon SaaS platform

A white-label, multi-tenant salon management platform: each salon gets its own web address, its own
branding, a staff workspace and a customer app, all served from one deployment. The product name is
not decided yet; `salon` is a placeholder.

## Quick start

Prerequisites, on the machine:

| Needs | Notes |
| --- | --- |
| JDK 25 | It does not have to be the default Java. With SDKMAN: `sdk install java 25.0.1.0+8-amzn` (the version in `.sdkmanrc`), answering "no" to making it the default. The scripts and `./mvnw` find it themselves; set `JAVA25_HOME` only if it is installed somewhere unusual |
| Node.js 24 or later | With Corepack, which ships with Node.js 24. Run `corepack enable` once so that `pnpm` is on your `PATH`; the pnpm version is pinned in `package.json`. A pnpm installed any other way is not used by the scripts, and stops with an error unless it is exactly that version: it would otherwise rewrite `pnpm-lock.yaml` |
| Docker, running | For the local PostgreSQL, and for the API's integration tests |
| curl | The scripts under `infra/scripts` use it to wait until the API and the web app answer. macOS has it; a minimal Linux image may not |
| `ps` that reports start times | The scripts ask it when a process started (`ps -p <id> -o lstart=`), so that they only ever stop a process they started themselves. macOS has it; on Linux it is in the procps package |
| Python 3.10 or later | For the lifecycle tooling only (`qa/scripts/sdlc`) |

```bash
infra/scripts/local-up.sh       # PostgreSQL, the API and the web app; prints each address
infra/scripts/local-down.sh     # stop everything; the database's data is kept
infra/scripts/local-reset.sh    # delete the local database's data and start again from nothing
infra/scripts/local-service.sh stop api      # one service: <start|stop> <postgres|api|web>
infra/scripts/local-service.sh pause postgres   # for tests: PostgreSQL stays up and answers nothing; `resume postgres` ends it
```

| Service | Address | Change it with |
| --- | --- | --- |
| Web app | http://localhost:3100 | `SALON_WEB_PORT` |
| API | http://localhost:8090 (`/api/v1/meta`, `/actuator/health`) | `SALON_API_PORT` |
| PostgreSQL | `localhost:5440`, database `salon` | `SALON_POSTGRES_PORT` |

`local-up.sh` checks the prerequisites first and stops, naming what is missing, before it starts
anything. Every setting and its default is in `.env.example`; copy it to `.env` to change one.
Process ids and logs are under `.local/`. The web app is served from a production build; for hot
reload start it with `SALON_WEB_MODE=dev infra/scripts/local-service.sh start web`.

The developer gates, each run from the repository root:

```bash
cd apps/api && ./mvnw -q verify        # unit, integration and architecture tests (needs Docker)
pnpm install --frozen-lockfile
pnpm -r lint
pnpm -r typecheck
pnpm -r test
pnpm --filter @salon/web build
pnpm contracts:check                   # the OpenAPI document and the generated client are current
qa/scripts/sdlc selftest
```

The API's contract is generated, never written by hand: after changing a controller run
`pnpm contracts:generate` and commit `docs/api/openapi.json` and `packages/api-contracts/src/generated/`
with the change. If Maven fails with `Cannot find matching toolchain definitions ... version=[25,26)`,
it found no JDK 25: see the prerequisites above.

## Where things are

| Path | Holds |
| --- | --- |
| `apps/api` | The API: Spring Boot, built with the Maven wrapper |
| `apps/web` | The web app: Next.js |
| `packages/api-contracts` | The TypeScript client generated from `docs/api/openapi.json` |
| `infra/` | Docker Compose for the local environment, and its scripts |
| `docs/brd/` | The business requirements document and `requirements.yaml`, the living requirement register |
| `docs/ROADMAP.md` | The delivery order: the web application first, then the customer mobile app |
| `docs/architecture/ARCHITECTURE.md` | How the platform is built |
| `docs/README.md` | The index of every project document: architecture, engineering rules, design, test plan, environments |
| `docs/design/prototype/` | The HTML design prototype |
| `features/README.md` | The feature portfolio and what is next (generated) |
| `features/<feature>/stages/<stage>/` | A stage's specification, state, test cases, bugs and review |
| `qa/` | QA automation and the lifecycle tooling |
| `.claude/skills/` | The three skills that run the process: `salon-sdlc`, `salon-dev`, `salon-qa`. They hold how work is done; everything about this product is under `docs/` |
| `AGENTS.md` | Guidance for Claude Code and Codex |

## How work moves

Feature -> stage -> release. The lead plans a stage, the developer builds it, QA verifies it as a
separate agent, and the lead closes it only when QA has passed it.

```bash
qa/scripts/sdlc status      # whose turn it is and which command comes next
qa/scripts/sdlc validate    # the gate over requirements, features, stages, tests and skills
```

In Claude Code: `/salon-sdlc stage-build F01-S01`. In Codex: `$salon-sdlc stage-build F01-S01`.

## Prerequisites for the tooling

Python 3.10 or later. The first run of `qa/scripts/sdlc` creates its own virtual environment and
installs PyYAML. The prerequisites for the product itself are under "Quick start" above and in
`docs/architecture/tech-stack.md`; `infra/scripts/local-up.sh` checks them.
