# Architecture

A summary of how the platform is built. The reasoning, the alternatives considered and the sources
are in the BRD (`docs/brd/Salon-SaaS-BRD-v1.0.pdf`, section 7). The rules a developer must follow
are in `docs/engineering/` and `docs/architecture/multi-tenancy.md`, and are not repeated here.
`docs/README.md` is the index of every project document.

## Shape

```
   Customers                      Salon staff                 Operator
   web address or app             workspace                   console
        |                              |                          |
        +---------------+--------------+--------------------------+
                        |
            Next.js web app (one application, three surfaces by hostname)
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
| A subdomain of the platform per salon | That salon's booking site, and its workspace under `/workspace` |
| A domain the salon owns (later release) | The same, through a custom-hostname service at the edge that issues the certificate |
| A reserved console hostname | The operator console; never resolves to a tenant |

The tenant is resolved on the server from the hostname or the signed token. The web app's request
proxy routes by hostname but is not a security boundary.

## Booking integrity

A hold and a booking are one kind of record in different states. A PostgreSQL exclusion constraint
forbids two active records for the same salon and professional with overlapping times, so the
database refuses a double booking even under simultaneous requests. Confirming a payment, converting
the hold and scheduling notifications happen in one transaction; messages are sent afterwards from
the outbox.

## Customer app distribution

One Expo codebase. A shared container app in the operator's store accounts opens one salon by link,
QR code or short code. A salon that wants its own listing gets a dedicated app built from the same
code and published in the salon's own Apple and Google developer accounts, which is what the stores
require of white-label apps. Branding and content are delivered from the API at run time, so a
brand change needs no app release. The distribution model is open decision D-01.

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
