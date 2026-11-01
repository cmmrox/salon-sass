# Architecture

A summary of how the platform is built. The reasoning, the alternatives considered and the sources
are in the BRD (`docs/brd/Salon-SaaS-BRD-v1.0.pdf`, section 7). The rules a developer must follow
are in `docs/engineering/` and `docs/architecture/multi-tenancy.md`, and are not repeated here.
`docs/README.md` is the index of every project document.

## Shape

```
   Customers                      Salon staff                 Operator
   React Native app (after M1 release)             workspace                   console
        |                              |                          |
        +---------------+--------------+--------------------------+
                        |
            Next.js staff workspace and operator console (no customer website)
            Expo customer app (one codebase: container app and dedicated apps)
                        |
                  REST API, /api/v1   (contract generated, shared by web and app)
                        |
          One API: a modular monolith, one deployable
          modules by business area (listed in docs/engineering/backend.md)
                        |
        PostgreSQL (one database, one schema)      Object storage (per-tenant prefix)
        row-level security on every tenant table   Outbox and job scheduler in the database
                        |
        External: PayHere (the salon's own account), WhatsApp, SMS, push, email
```

## Tenancy

Every salon is a tenant. All salons share one application and one database; every tenant-owned row
carries a `tenant_id`. Isolation is enforced twice: by Hibernate's tenant filter in the application,
and by row-level security in PostgreSQL, set per transaction, with the application connected as a
role that cannot bypass it. The ten rules are in `docs/architecture/multi-tenancy.md`.

Schema-per-tenant and database-per-tenant were considered and rejected for the planned scale
(thousands of small salons): they multiply migrations and connections without adding isolation that
row-level security does not already give.

## Web addresses

| Address | Serves |
| --- | --- |
| A subdomain of the platform per salon | That salon's staff workspace under `/workspace`; root enters it |
| A domain the salon owns (later release) | The same, resolved by the API from a verified `tenant_domain` row; Caddy issues its certificate on demand. Design in `docs/architecture/multi-tenancy.md`, "Domains a salon owns" |
| A reserved console hostname | The operator console; never resolves to a tenant |

The tenant is resolved on the server from the hostname or the signed token. The web app's request
proxy routes by hostname but is not a security boundary.

## Modules and how they could be separated

The API is a modular monolith (CR-021). Each business module offers its services to other modules
through provided interfaces of records, tells them about changes through versioned events written
to the tenant-aware outbox, and owns its tables and its `/api/v1` paths. Spring Modulith and
architecture tests hold these boundaries in the build. The web app is organised by the same modules.

This keeps a later split cheap without paying for it now: a module could run as its own service
behind the same edge, with Caddy routing its paths and the clients unchanged, its provided
interfaces implemented by an HTTP client adapter, and its events carried by the same outbox. Nothing
is split until there is a measured reason (failure isolation, a different scaling profile, a separate
team). The first step would be the same jar run as a separate worker for the outbox and jobs; the
first candidates are `notification`, then `reporting`. Rules: `docs/engineering/backend.md`.

## Booking integrity

A hold and a booking are one kind of record in different states. A PostgreSQL exclusion constraint
forbids two active records for the same salon and professional with overlapping times, so the
database refuses a double booking even under simultaneous requests. Confirming a payment and
converting the hold happen in one transaction, which also writes a `BookingConfirmed` event to the
outbox; notifications are scheduled when that event is consumed, and messages are sent afterwards
from the outbox.

Service time is stored as ordered segments (active, processing, buffer). The overlap constraint
and the availability engine work on the segments, so processing time can free a professional
without a new engine (decision 074). A booking's state changes only through the booking module's
state machine (decision 075).

## Salon operations model (CR-023)

- **Branch scope from the start.** From F04, salon-operations data carries a branch scope. Each
  salon has one default branch in R1, and F18 adds more (decision 073). Branches are owned by
  `branding`, which also holds each branch's public contact details; other modules keep the branch
  as an id and check it through `branding`'s `SalonBranches` (decision 083).
- **One payment record.** A booking or sale has one append-only record of charges, payments by
  tender, refunds and deposits held. Checkout, the day-end close, reports, occasion balances (F19)
  and gift cards (F21) all use it (decision 076).
- **Modules are entitlements.** A salon's modules are its plan's features plus add-ons the operator
  activates, enforced by the API. Navigation only reflects them (decision 077).
- **Metered messaging and platform reference data.** Messages are metered per salon, with a warning
  before the allowance runs out. The holiday and Poya calendar is platform data published each year
  (decision 078).

## Customer app distribution

One Expo codebase. A shared container app in the operator's store accounts opens one salon by link,
QR code or short code. A salon that wants its own listing gets a dedicated app built from the same
code and published in the salon's own Apple and Google developer accounts, which is what the stores
require of white-label apps. Branding and content are delivered from the API at run time, so a
brand change needs no app release. D-01 is settled (decision 071): the shared app is the standard
route for every salon (M2-mobile, F15), and the dedicated app is a plan feature (F16, M3-growth).

## Integrations

Each external service sits behind an interface with a real adapter and a local fake. Payments use
the salon's own PayHere merchant account, and a booking is confirmed only by the gateway's verified
server notification. Messages try push first, then WhatsApp, then SMS, and every send is logged with
its cost.

## Environments

Local is created by F01-S01. F01-S05 adds a production-like local stack with synthetic salons and
a provider-neutral delivery pipeline. The first real staging deployment is optional and waits for
hosting, domain and access decisions; production is deployed separately when requested. Feature QA
and GitHub merge do not require either deployment. See `docs/operations/environments-and-releases.md`.

## Where to read more

| Question | Where |
| --- | --- |
| Why any of this was chosen | BRD section 7 |
| What must be built | `docs/brd/requirements.yaml` |
| The rules for building it | `docs/engineering/`, `docs/architecture/multi-tenancy.md`, `docs/design/ui-ux-standards.md` |
| How it is tested | `docs/qa/` |
| The order it is delivered in | `docs/ROADMAP.md` |
| What has been decided since | `docs/decisions/DECISION_LOG.md` |
