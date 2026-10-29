# F23 Modular, extractable architecture

## What the customer gets

Nothing new on screen, by design. Salons keep everything F01 to F03 deliver, unchanged. What changes
is how the platform is built: each business area offers its services through stable interfaces and
events, owns its own data and API paths, and follows one convention for storing data and removing
repetitive code, and the web app is organised by the same areas. Every later feature is built on
this, faster, and any area can later run as a separate service without the web or mobile app
changing. Approved by the owner in `docs/changes/CR-021-modular-extractable-architecture.md`.

## Requirements

Non-functional only, under `nfr_focus`: NFR-22 (extractable modules), NFR-23 (backend code
conventions), NFR-24 (client code structure), proved while keeping NFR-01 (tenant isolation) and
NFR-18 (deployable without downtime) intact. NFR-04 (growth without re-architecture) is the reason
for the feature.

## How it is built

API and web, no new screen. The rules are in `docs/engineering/backend.md` ("Modules", "Inside a
module", "Persistence and migrations", "Code generation", "Events between modules"),
`docs/engineering/web.md` ("Structure", "Where things live") and `docs/architecture/multi-tenancy.md`.
Every stage is a restructuring that keeps behaviour: the F01 to F03 automation and every existing
developer test must pass unchanged at each stage's handoff. A test may move with the code it tests;
its assertion may not change.

Started before F04 on the owner's instruction (`started_early`); F04 depends on F23.

## Stages

The order and status of the stages is in `features/README.md` (generated). This section explains
why the feature is divided the way it is.

| Stage | Delivers | Why it is its own stage |
| --- | --- | --- |
| F23-S01 | Lombok, MapStruct and Spring Modulith in the build; `lombok.config` refusing the denied annotations; Modulith's `verify()` replacing the hand-written module rules, with the fixture that proves each rule fails moved across; Spotless with `palantir-java-format` and compiler warnings as errors, applied once to the whole API in its own commit | Every later stage depends on the tooling and on the boundary check being the new one. Riskiest for the build: annotation processors on JDK 25 |
| F23-S02 | Each module's root package holds only interfaces, records and events; the five provided service classes become interfaces with implementations in `application/`; `IdentityFacade.Principal` split from its response shape; `api/` renamed `web/`; named interfaces and allowed dependencies declared; transaction-bound contracts marked in-process only; oversized platform use-case classes split along their capabilities; the rule that every MapStruct mapper names the shared configuration | The boundary every later module is built against. Touches every module, so it is done once, alone |
| F23-S03 | Identity and branding entities in the encapsulated, Lombok-restricted style with MapStruct response mappers; the remaining direct SQL limited to the named exceptions, each with an explicit tenant predicate; a Hibernate-alone isolation test per repository; a check that no entity can set its id, tenant or version; `StaffAccess` (894 lines) split into one class per capability | Tenant isolation risk is concentrated here: changing entities and SQL is where a filter gap would appear |
| F23-S04 | A declared owner for every table and every `/api/v1` path segment, checked by tests; OpenAPI operations tagged by module, a document per module, and the generated client exported per module; settings under `salon.<module>.*` | Ownership must be explicit before events (S05) and the web restructure (S06) depend on it. Changes the generated client's shape |
| F23-S05 | Versioned events between modules through the outbox: an `event` kind, subscribers per module, a processed-message record making consumers idempotent, and at least one existing cross-module call moved onto it | A new runtime mechanism with retry and tenant context; proven on its own before any feature relies on it |
| F23-S06 | `apps/web/src` reorganised into `features/<module>` and `surfaces/`, import boundaries enforced by lint, data access through the per-module client, `packages/format` extracted; Prettier and `knip` in the gates with every unused file, export and dependency removed; the stale `(booking)` lint surface removed | Web-only, and it depends on the per-module client from S04 |

Every stage also leaves the code it touches meeting the "Clean code" sections of
`docs/engineering/backend.md` and `docs/engineering/web.md` (the owner's addition to CR-021).

## Feature-level test focus

That nothing a salon, a staff member or the operator can do has changed: the feature pass runs F23's
cases, and the owner decides whether to ask for an F01 to F03 regression run, which this feature
touches throughout. Tenant isolation (NFR-01) through every repository and the new event path, with
Hibernate's filter and row-level security each tested alone. That the build refuses a boundary
violation, a denied Lombok annotation, an unmapped field, an undeclared table or path owner, and a
cross-feature web import (NFR-22 to NFR-24). That an event delivered twice is handled once.

## Decisions and open questions

- CR-021 and decision 055. Caddy stays at the edge; salon-owned domains are designed in
  `docs/architecture/multi-tenancy.md` and built later, on the owner's request.
- Existing tables keep their names; new tables carry their module's prefix.
