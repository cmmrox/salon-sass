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
| Web | Next.js 16 (App Router), TypeScript. The request proxy file is `proxy.ts` (formerly `middleware.ts`) |
| Customer app | Expo SDK 57, React Native 0.86, Expo Router; EAS Build, Submit and Update. Not built until milestone `M2-mobile` |
| API contract | An OpenAPI document generated from the API's controllers and committed; a TypeScript client generated from it |
| Background work | Database-backed job scheduler and transactional outbox. No message broker |
| Files | S3-compatible object storage, one prefix per tenant |
| Developer tests | JUnit 5 and Testcontainers (API), Vitest (web), Jest (customer app) |
| QA tools | Playwright (browser and API), Maestro (customer app), k6 (load and concurrency) |
| Source hosting | GitHub. `main` is the trunk |
| Continuous integration | GitHub Actions, added by stage F01-S01 |

Next.js 16, Spring Boot 4 and Hibernate 7 differ from older versions in ways your memory may not
cover. Read the installed version's documentation before using an API you have not used in this
repository.

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
| Building and running the product | JDK 25, Node.js with pnpm through Corepack, Docker |
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
