# Technology stack

This is the one place versions are written. Once the build files exist they are the truth; keep
this table in step when a major version changes.

## Stack

| Area | Choice |
| --- | --- |
| Language and runtime | Java 25 (LTS) |
| Backend framework | Spring Boot 4.1.x, virtual threads on (`spring.threads.virtual.enabled=true`) |
| Persistence | Spring Data JPA repositories by default, `JdbcClient` only for the named exceptions in `docs/engineering/backend.md`; Hibernate 7 with `@TenantId`; Flyway migrations |
| Module boundaries | Spring Modulith 2.1.2 (`spring-modulith-starter-core`, `spring-modulith-starter-test`, through its BOM) for module verification and module tests only; its documentation annotation processor is excluded and its event publication registry is not used (CR-021). ArchUnit, test scope only, for the rules Modulith does not cover |
| Code generation (API) | Lombok, in the version Spring Boot manages (1.18.46 with Boot 4.1.1), within the allow-list in `apps/api/lombok.config` (copied into the image build); MapStruct 1.6.3 with `lombok-mapstruct-binding` 0.2.0. Both are listed in `annotationProcessorPaths` (JDK 23 and later do not discover processors on the class path); MapStruct's policies are in the shared `ResponseMapperConfig`, not compiler arguments (decision 056) (CR-021) |
| Database | PostgreSQL 18 or later: row-level security, exclusion constraints |
| Build | Maven wrapper for the API; one pnpm workspace for the web app, the customer app and packages. `qa/` is a separate package with its own lock file, so QA can add test dependencies without touching the product's |
| Web | Next.js 16 (App Router; 16.4.1, the first version past GHSA-p3m5-59mp-jv5r, GHSA-2464-fhr4-w9xw and GHSA-673q-mjm6-r5xr), TypeScript, Tailwind CSS 4 and shadcn/ui (CR-016). The request proxy file is `proxy.ts` (formerly `middleware.ts`) |
| Customer app | Expo SDK 57, React Native 0.86, Expo Router; EAS Build, Submit and Update. Not built until milestone `M2-mobile` |
| API contract | An OpenAPI document generated from the API's controllers and committed, with every operation tagged by its module; a TypeScript client generated from it (`openapi-typescript` with `openapi-fetch`), exported per module |
| Web import boundaries | `eslint-plugin-boundaries` 7.4.0, and `server-only` 0.0.1 on server code (CR-021) |
| Formatting and dead-code checks | API: Spotless Maven plugin 3.10.4 with `palantir-java-format` 2.102.0 (its default; identical output under JDK 21 and 25), and `-Xlint:all,-processing -Werror`. Web: Prettier 3.9.10 with `prettier-plugin-tailwindcss` 0.8.1 (print width 120, single quotes, sorting classes in `cn` and `cva`), and `knip` 6.43.0 for unused files, exports and dependencies (CR-021). Prettier skips `src/styles/theme.css` (inlined byte for byte into the plain pages) and `src/components/ui` (shadcn/ui as generated); `knip` exempts only the generated contract and shadcn/ui's unused parts |
| Background work | Database-backed job scheduler and transactional outbox, which also carries versioned events between modules. No message broker |
| Files | Brand images (F04-S02) are stored in PostgreSQL, in `branding`'s tenant tables, re-encoded and size-limited, and served through the API (decision 086). S3-compatible object storage with one prefix per tenant arrives with the first feature whose files outgrow that (catalogue and staff photos); its provider is chosen with the owner then |
| Images and phone numbers (API) | TwelveMonkeys `imageio-webp` 3.15.3 for reading WebP; the JDK's ImageIO writes PNG and JPEG (F04-S02). libphonenumber 9.0.42; libphonenumber-js 1.13.15 in `@salon/format` (F04-S01, decision 084) |
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
| Web components | shadcn/ui, through its CLI, run as `pnpm dlx shadcn@4.22.0 add <component>` in `apps/web` (style `new-york`), with `radix-ui` primitives (the product's one primitive library; shadcn/ui's Base UI variants are not used), `input-otp` 1.5.0 for Input OTP (CR-017), `class-variance-authority`, `clsx`, `tailwind-merge` and `lucide-react` icons. Components are copied into `apps/web/src/components/ui/` and committed. The CLI's own `cn` package is not used (`docs/engineering/web.md`, "Styling") | Accessible primitives maintained upstream; one component set for every screen. Rules in `docs/engineering/web.md`, "Styling" |
| CSS as text | A Turbopack rule in `next.config.ts` imports `*.css?raw` as a string, with the same rule in `vitest.config.mts`; used only for the proxy's plain pages (decision 028), which inline their Tailwind-generated CSS (CR-017). A `?raw` import gives a style sheet's source before PostCSS, so that CSS is generated by Tailwind ahead of the build and committed | The plain pages carry the theme without a hand-written copy |
| Web lint and tests | ESLint with `eslint-config-next`; Vitest with jsdom and Testing Library | |
| Request validation | `spring-boot-starter-validation` (Jakarta Bean Validation) | Field errors on problem bodies |
| Identity cryptography | Spring-managed `spring-security-crypto`, `spring-security-oauth2-jose`; `java-otp` pinned in `apps/api/pom.xml` | Maintained password/JWT/TOTP implementations; policies in `architecture/identity-and-access.md` |
| Tracing and structured logs | `spring-boot-starter-opentelemetry` (Micrometer Tracing with the OpenTelemetry bridge, W3C propagation, OTLP export when configured); Spring Boot's structured logging in ECS format | Spring Boot 4's own support; no agent and no second logging library |
| Local web server | `apps/web/scripts/serve.mjs`, wrapping Next.js's request handler | Supports both loopback address families; the container uses Next.js standalone serving (decision 030) |
| Production images | Multi-stage API JRE image and Next.js standalone Node image, non-root; `infra/scripts/images-build.sh` | Both carry one version and source commit, with no environment files; base versions are pinned in the Dockerfiles. When a pinned base lags a published fix for a critical or high advisory, the runtime stage may add an explicit Alpine package floor (`apk add --no-cache --upgrade '<pkg>>=<fixed>'`, as for libexpat under BUG-F01-006), removed once the base tag moves past the fix |
| Production-like edge | Caddy, two instances of each app, provider-neutral Docker Compose | Bare and wildcard TLS, readiness checks and draining replacements, least-connections balancing to fixed instance addresses, a surge web instance during deploys, an access log without personal data; local and real-host settings in `.env.stack.example` (decision 030) |
| Telemetry collector | OpenTelemetry Collector Contrib, OTLP/HTTP, file exports | Local spans and meters in `.stack/telemetry`; image pinned in `infra/stack/compose.yaml` |
| Image and workflow gates | Trivy and actionlint from official containers | Fail on fixable HIGH/CRITICAL vulnerabilities or workflow problems; pins in `infra/scripts/lib/stack.sh`, no host installation required |
| TypeScript | The newest major that both `openapi-typescript` and `typescript-eslint` support | |
| Node.js and pnpm | Node.js pinned in `.nvmrc`; pnpm pinned by `packageManager` and run through Corepack | A pnpm of another version stops with an error (`pmOnFail: error`) rather than rewrite the lock file; the scripts refuse to run without Corepack. pnpm's minimum release age refuses a version published within the last day: take the previous version (as with `lucide-react` 1.58.0 in CR-016) and never add an exclusion to `pnpm-workspace.yaml` to get round it |

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
