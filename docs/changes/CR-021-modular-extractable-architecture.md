---
id: CR-021
title: Modular, extractable architecture and code conventions
status: approved
requested_by: owner
requested_on: 2026-10-29
decided_on: 2026-10-29
requirements: [NFR-22, NFR-23, NFR-24]
features: [F04]
stages: []
---

# CR-021 Modular, extractable architecture and code conventions

## Request

In the owner's words (2026-10-29, condensed): the API should be a modular monolith whose modules
can later be converted into microservices easily. When one module needs a service of another, that
service should be offered through a separate interface, so that on extraction it can become a REST
controller for the other module. Each salon is a tenant and should be reachable on its own domain,
with nginx in front attaching the tenant to the request. Use Spring Data JPA repositories instead of
JDBC, and use Lombok and MapStruct to remove boilerplate so developers work on business logic. Keep
the Next.js web application and the future React Native app equally clean, with a well-organised,
maintainable file and package structure. Research how the industry builds this kind of application
first, and revamp the application before the next stage.

Added the same day, while the change was being applied: keep the code clean, human-readable and
following best practices; remove unwanted files and commented-out functions; keep the code clean and
maintainable.

## Why

Five research passes (2026-10-29) compared the current code with current practice: Spring Modulith
2.x and its documentation; Richardson, Newman, Fowler, Jovanović, Grzybek and Shopify on modular
monoliths and extraction; Vlad Mihalcea, Thorben Janssen and the Spring team on JPA and multitenancy;
the Lombok and MapStruct release notes for JDK 25; Vercel Platforms, Shopify, Cloudflare for SaaS,
Hashnode and the Caddy documentation on per-tenant domains; Next.js, bulletproof-react, FSD, Expo and
Turborepo on client structure.

Findings about the current code:

- It is already a modular monolith with boundaries enforced by ArchUnit, but a module's provided
  services are concrete `@Service` classes (`IdentityFacade`, `EntitlementsFacade`,
  `IdentityLifecycle`, `IdentityProvisioning`, `BrandProvisioning`), not interfaces. Three of them
  require the caller's transaction and `EntitlementsFacade` holds a share lock until it ends, so
  they could never become remote calls as written.
- `IdentityFacade.Principal` is both the cross-module type and the REST response shape.
- The outbox delivers provider messages only. There is no durable event between modules, no
  subscriber and no record of processed messages.
- Table ownership and API path ownership are implicit; OpenAPI tags name resources, not modules.
- Persistence is mixed: identity and branding use JPA; platform, outbox, jobs and audit use
  `JdbcClient`. Most of that SQL is SQL for a reason (the platform pool that is deliberately not a
  `DataSource` bean, `SECURITY DEFINER` functions, `FOR UPDATE SKIP LOCKED` leasing, set-based
  erasure). There is no Lombok and no MapStruct.
- The web app is organised by layer (`components/<area>`, `lib/<area>`), not by business module.

## Decisions

1. **Backend shape.** One deployable and one Maven module. Spring Modulith 2.1 (`core` and `test`
   only) replaces the hand-written module rules; the ArchUnit rules it does not cover stay. Its event
   publication registry is not used: its table has no `tenant_id` and no row-level security.
2. **Provided interfaces.** A module's root package holds only interfaces, records and versioned
   event records. Implementations live in `application/`. A provided interface takes and returns
   records with ids, never entities. An interface that must join the caller's transaction is marked
   in-process only and is never extracted as a remote call; on extraction it becomes an event.
   `api/` inside a module is renamed `web/`, because "API" now means the provided interface.
3. **Durable events between modules** use the existing tenant-aware outbox: a message kind `event`,
   subscribers registered per module, and idempotent consumers backed by a processed-message table.
4. **Ownership.** Every table and every `/api/v1/<segment>` belongs to exactly one module, declared
   and checked by tests. No foreign key crosses modules except `tenant_id`. New tables carry their
   module's prefix; existing tables keep their names (a rename would break the rule that a migration
   works while the previous version runs). Schema per module is deferred; `ALTER TABLE ... SET
   SCHEMA` makes it a later one-line migration.
5. **Persistence.** Spring Data JPA repositories for every tenant aggregate. `JdbcClient` only for
   the named exceptions written into `docs/engineering/backend.md`, each with an explicit
   `tenant_id` predicate and a test with row-level security as the only guard.
6. **Code generation.** Lombok within an allow-list enforced by `lombok.config` (`@Getter`,
   protected no-argument constructors, `@RequiredArgsConstructor`, `@Slf4j`, field-level `@Setter`
   on plain attributes). Denied: `@Data`, `@Value`, `@EqualsAndHashCode`, `@ToString`, `@Builder`,
   `@SneakyThrows`, and any setter on an id, tenant id, version or creation time. Records stay the
   form of every request, response and cross-module type. MapStruct 1.6.3 maps one way, from an
   entity or provided record to a response record, with unmapped targets failing the build.
7. **Per-tenant domains.** The owner's goal is kept: each salon on its own address. The method is
   adjusted: Caddy stays at the edge and passes `Host` unchanged; the API resolves the tenant from
   the hostname. nginx injecting a tenant id was not adopted: a header that an edge sets is a tenant
   id the client can forge on any hop that forgets to strip it (rule T-5), and an nginx map needs a
   reload for every new salon, while Caddy issues certificates for new domains on demand. The design
   for salon-owned domains (a `tenant_domain` table, an `ask` endpoint gating on-demand TLS, DNS
   verification) is written into `docs/architecture/multi-tenancy.md` now and built later, when the
   owner asks; there is no customer website yet, so a custom domain would only rename the staff
   workspace.
8. **Clients.** The web app is organised by the same modules as the API (`features/<module>`),
   composed by `surfaces/`, with import boundaries enforced by lint. The generated client is
   exported per module. Only non-UI code is shared with the future mobile app (client, formatting,
   messages, tokens); shadcn/ui stays web-only (CR-016, CR-017). Turborepo waits for the mobile app.
9. **Clean code** is a written, checked standard (`docs/engineering/backend.md` and
   `docs/engineering/web.md`, "Clean code"): business names, small units, nothing dead or commented
   out, comments that say why, explicit errors, immutability, one formatter per language (Spotless
   with `palantir-java-format`; Prettier) and dead-code detection on the web (`knip`), with compiler
   and lint warnings as errors. A scan on 2026-10-29 found no commented-out code and no open `TODO`,
   no formatter in either application, no unused-code check, a stale `(booking)` surface in the web
   lint configuration, and several oversized classes (`StaffAccess` at 894 lines; four platform
   use-case classes of 450 to 560 lines). Each F23 stage brings the code it touches to the standard
   and removes what is unused; the formatters are introduced in S01 (API) and S06 (web).
10. **Deferred on purpose:** a message broker, an API gateway, Pact or Spring Cloud Contract, a
   database or role per module, multi-module Maven, a separate service. The first step towards
   isolation, when it is needed, is the same jar run as a separate worker; the first extraction
   candidate is `notification`, then `reporting`.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | Added NFR-22 (extractable modules), NFR-23 (backend code conventions and clean code), NFR-24 (client structure and clean code). None changed or retired |
| Features and stages | New feature F23, six stages, delivered before F04 on the owner's instruction (`started_early`). F04 now depends on F23. F01 to F03 are `complete` and merged; they are not reopened, because their behaviour must not change: F23 restructures their code and must keep their automation passing |
| Test cases and automation | No existing case changes expectation. F23's feature pass runs F23's own cases; the owner decides whether to ask for an F01 to F03 regression run, which the restructuring touches |
| Data, API and released apps | No released app. New migrations for the processed-message table and outbox kind only; no table renamed. OpenAPI tags change to module names, so the generated client changes shape; no path or field changes |
| Size and delay | Six stages of refactoring with no new customer-visible behaviour. F04 to F14 move back by the time F23 takes |

## Recommendation

Approve, delivered as F23 before F04. Every later module (catalogue, staff, booking, payment,
notification) is still empty, so this is the cheapest moment to set the conventions they will be
built with.

## Decision

> **Decision 066 (2026-10-30):** the owner ruled that architecture work is not a feature. What this
> request approved was delivered as one refactor branch (planned as "F23" and its stages, a
> numbering that survives only in commit messages), recorded here and in the decision log.

Approved by the owner on 2026-10-29 ("ok proceed"), with the lead's recommended answers to the three
open questions: a separate feature before F04; custom domains designed now and built later; module
table prefixes with an ownership test now, schemas per module later.
