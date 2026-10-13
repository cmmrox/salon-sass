# F01 Platform foundation and tenant isolation

## What the customer gets

Nothing a salon's customer can see yet, and everything they will rely on: one deployment that many
salons share without ever seeing each other's data, each salon reached at its own web address, and
the common services (errors, time, audit trail, background work, monitoring) that every later
feature builds on. This is the one feature whose users are the team and the operator.

## Requirements

Functional: BRD-03 (each salon has its own platform subdomain with a secure certificate) and BKG-15
(times stored in a universal form and shown in the salon's time zone).

Non-functional, and the real substance of this feature: NFR-01 tenant isolation, NFR-15 resilience,
NFR-07 least exposure (local only, through CR-002), NFR-16 observability, NFR-17 no per-salon code, NFR-18 releasable without downtime.

## How it is built

See `docs/architecture/ARCHITECTURE.md` for the design, `docs/architecture/tech-stack.md` for the
stack and `docs/architecture/repository-structure.md` for what lives where. The rules the build must
follow are `docs/architecture/multi-tenancy.md` (the ten isolation rules), `docs/engineering/backend.md`
(modules and API conventions) and `docs/engineering/web.md`. The customer app is not part of this
feature: it starts in F15, after the web milestone is released (`docs/ROADMAP.md`).

## Stages

The order and status of the stages is in `features/README.md` (generated). This section explains
why the feature is divided the way it is.

| Stage | Delivers | Why it is its own stage |
| --- | --- | --- |
| F01-S01 | The repository builds, the API and the web app start as shells that talk to each other through the generated contract, one command runs everything locally, and QA's harness can test it | Everything else needs somewhere to live. It also proves the newest pieces of the stack work together before anything depends on them |
| F01-S02 | Salons exist as tenants; the API resolves a salon from its hostname; every tenant table is protected by Hibernate and by row-level security; three synthetic salons are seeded; a cross-tenant attempt is refused; the first tenant table (a salon's published name and brand colour) proves it | Isolation is the highest risk in the product. It is proved on its own, early, against real PostgreSQL, before any business table exists |
| F01-S03 | A salon's hostname resolves to that salon on the web; the public shell shows that salon's name and brand; an unknown or suspended salon gets a safe page | Depends on tenants existing. It is where routing mistakes that leak one salon into another would appear, so QA tests it with three salons |
| F01-S04 | One error format, time handling in the salon's zone, the audit trail, the outbox and job scheduler with retry, and logs and traces tagged by tenant | Cross-cutting services that later stages assume. Testable through the API with provider fakes set to fail |
| F01-S05 | A production-like local stack with synthetic salons, secure certificates for salon subdomains, and a pipeline that deploys backward-compatible changes | Deployment and rollback can be proved locally without selecting a host. The optional first real staging deployment waits for D-08 (hosting), D-02 (domain) and access, as decision 018 requires |

## Feature-level test focus

Once every stage is done: a journey that creates data as each of the three salons through every
endpoint that exists and proves none can see another's, on the web, through the API and from a
background job; a restart in the middle of an outbox delivery; and a deployment of a new version
while requests are in flight.

## Decisions and open questions

- Names are placeholders until open decision D-02 (product name and platform domain): Java base
  package `com.salonplatform`, npm scope `@salon`, app identifiers under `com.salonplatform`.
  Recorded as decision 007.
- F01-S05 is built ready to deploy and provider-neutral; the first real deployment waits for D-08
  (hosting) and D-02 (domain). Decision 018. It also decides how the web app is served in a
  production image (decision 022) and provisions the two database roles outside the local machine.
- Settled when planning F01-S02: a request names its salon by hostname from F01-S02 onward, on the
  API (`<slug>.localhost` locally, rules in `docs/architecture/multi-tenancy.md`); F01-S03 adds the
  same on the web. The three synthetic salons' fixed ids are in `docs/qa/test-plan.md`.
- Approved change requests: CR-002 and CR-003 are built in F01-S02, CR-001 in F01-S04.
