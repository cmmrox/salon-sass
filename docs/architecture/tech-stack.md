# Technology stack

This is the one place versions are written. Once the build files exist they are the truth; keep
this table in step when a major version changes.

## Stack

| Area | Choice |
| --- | --- |
| Language and runtime | Java 25 (LTS) |
| Backend framework | Spring Boot 4.1.x, virtual threads on (`spring.threads.virtual.enabled=true`) |
| Persistence | Spring Data JPA, Hibernate 7 with `@TenantId`, Flyway migrations |
| Database | PostgreSQL 18 or later: row-level security, exclusion constraints |
| Build | Maven wrapper for the API; one pnpm workspace for the web app, the customer app and packages. `qa/` is a separate package with its own lock file, so QA can add test dependencies without touching the product's |
| Web | Next.js 16 (App Router), TypeScript, Tailwind CSS 4 and shadcn/ui (CR-016). The request proxy file is `proxy.ts` (formerly `middleware.ts`) |
| Customer app | Expo SDK 57, React Native 0.86, Expo Router; EAS Build, Submit and Update. Not built until milestone `M2-mobile` |
| API contract | An OpenAPI document generated from the API's controllers and committed; a TypeScript client generated from it |
| Background work | Database-backed job scheduler and transactional outbox. No message broker |
| Files | S3-compatible object storage, one prefix per tenant |
| Developer tests | JUnit and Testcontainers, in the versions Spring Boot manages (API); Vitest with Testing Library (web); Jest (customer app) |
| QA tools | Playwright (browser and API), Maestro (customer app), k6 (load and concurrency) |
| Source hosting | GitHub. `main` is the trunk |
| Continuous integration | GitHub Actions, added by stage F01-S01 |

Next.js 16, Spring Boot 4 and Hibernate 7 differ from older versions in ways your memory may not
cover. Read the installed version's documentation before using an API you have not used in this
repository.

## Tooling choices

Set by stage F01-S01. Exact versions are in the build files (`apps/api/pom.xml`, `package.json`,
`pnpm-lock.yaml`).

| For | Choice | Why |
| --- | --- | --- |
| Selecting the JDK | `maven-toolchains-plugin`, and `JAVA25_HOME` as the override the scripts and the plugin both read; `.sdkmanrc` pins the JDK | The build works from a shell whose default Java is another version |
| OpenAPI document | `springdoc-openapi` (no UI); the test `OpenApiDocumentTest` writes `docs/api/openapi.json` with sorted keys | No second application start, and a stable diff |
| TypeScript client | `openapi-typescript` for types and `openapi-fetch` for the client, in `packages/api-contracts` (TypeScript source, no build step) | Fetch-based, so the customer app can use it unchanged |
| Architecture tests | ArchUnit | Module boundaries fail the build |
| Integration database | Testcontainers with the same PostgreSQL image as the local environment | Row-level security and exclusion constraints exist only in PostgreSQL |
| Web translation | `next-intl`, files in `apps/web/messages/<locale>.json`, no locale in the URL | |
| Web styling | Tailwind CSS 4 (`tailwindcss`, `@tailwindcss/postcss`, `tw-animate-css`), configured in CSS with no `tailwind.config.*`; the theme's CSS variables in `apps/web/src/styles/theme.css` (decision 046, CR-016; replaces CSS Modules) | The owner's standard for all Next.js work. The salon's brand is still applied by changing CSS variables at run time |
| Web components | shadcn/ui, through its CLI (`shadcn` 4.x, run as `pnpm dlx shadcn@4 add <component>` in `apps/web`), with `radix-ui` primitives, `class-variance-authority`, `clsx`, `tailwind-merge` and `lucide-react` icons. Components are copied into `apps/web/src/components/ui/` and committed | Accessible primitives maintained upstream; one component set for every screen. Rules in `docs/engineering/web.md`, "Styling" |
| CSS as text | A Turbopack rule in `next.config.ts` imports `*.css?raw` as a string, with the same rule in `vitest.config.mts`; used only for the proxy's plain pages (decision 028), which inline `theme.css` and `plain-page.css`. It gives a style sheet's source before PostCSS, so it cannot carry Tailwind output | The plain pages inline the one source of the theme instead of a copy |
| Web lint and tests | ESLint with `eslint-config-next`; Vitest with jsdom and Testing Library | |
| Request validation | `spring-boot-starter-validation` (Jakarta Bean Validation) | Field errors on problem bodies |
| Identity cryptography | Spring-managed `spring-security-crypto`, `spring-security-oauth2-jose`; `java-otp` pinned in `apps/api/pom.xml` | Maintained password/JWT/TOTP implementations; policies in `architecture/identity-and-access.md` |
| Tracing and structured logs | `spring-boot-starter-opentelemetry` (Micrometer Tracing with the OpenTelemetry bridge, W3C propagation, OTLP export when configured); Spring Boot's structured logging in ECS format | Spring Boot 4's own support; no agent and no second logging library |
| Local web server | `apps/web/scripts/serve.mjs`, wrapping Next.js's request handler | Supports both loopback address families; the container uses Next.js standalone serving (decision 030) |
| Production images | Multi-stage API JRE image and Next.js standalone Node image, non-root; `infra/scripts/images-build.sh` | Both carry one version and source commit, with no environment files; base versions are pinned in the Dockerfiles |
| Production-like edge | Caddy, two instances of each app, provider-neutral Docker Compose | Bare and wildcard TLS, readiness checks and draining replacements; local and real-host settings in `.env.stack.example` (decision 030) |
| Telemetry collector | OpenTelemetry Collector Contrib, OTLP/HTTP, file exports | Local spans and meters in `.stack/telemetry`; image pinned in `infra/stack/compose.yaml` |
| Image and workflow gates | Trivy and actionlint from official containers | Fail on fixable HIGH/CRITICAL vulnerabilities or workflow problems; pins in `infra/scripts/lib/stack.sh`, no host installation required |
| TypeScript | The newest major that both `openapi-typescript` and `typescript-eslint` support | |
| Node.js and pnpm | Node.js pinned in `.nvmrc`; pnpm pinned by `packageManager` and run through Corepack | A pnpm of another version stops with an error (`pmOnFail: error`) rather than rewrite the lock file; the scripts refuse to run without Corepack |

## External services

| Service | Used for | Rule |
| --- | --- | --- |
| PayHere | Online payment, through the salon's own merchant account | A booking is confirmed by the gateway's verified server notification, never by the device |
| WhatsApp Business Platform, SMS | One-time codes, confirmations, reminders | Charged per message. Every send is logged with its cost and attributed to the salon |
| Push notifications | Free channel, tried first once the customer app exists | |
| Email | Receipts and staff notices | |

Every one of them sits behind an interface with a local fake, so that development and QA never send
a real message or take a real payment.

## Machine prerequisites

| For | Needs |
| --- | --- |
| Building and running the product | JDK 25 (it need not be the machine's default Java), Node.js 24 or later with pnpm through Corepack, Docker, curl, a `ps` that reports start times (procps on Linux) |
| The lifecycle tooling (`qa/scripts/sdlc`) | Python 3.10 or later; it creates its own virtual environment |
| QA | k6 from stage F01-S02 onward; Maestro and an Android emulator from milestone `M2-mobile` |

Ask the owner before installing system software. A missing prerequisite is reported, not worked
around.

## Placeholder names

The product has no name yet (open decision D-02). Until it does:

| Thing | Placeholder |
| --- | --- |
| Java base package | `com.salonplatform` |
| npm scope | `@salon` |
| App identifiers | under `com.salonplatform` |
| Skill prefix | `salon-` |

Keep each in one place in the build so the rename is mechanical.

## Not decided yet

The BRD lists open decisions D-01 to D-13 (the mobile distribution model, the product name, hosting
region, pricing and others). When a stage depends on one, stop and ask. Do not pick a value and
build on it.
