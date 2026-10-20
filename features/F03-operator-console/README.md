# F03 Operator console: salons, plans and billing

## What the customer gets

The operator can create a salon, assign a plan, manage its subscription and invoices,
suspend or reactivate it, and close it with an export and scheduled deletion. Support staff
can enter one salon's workspace through a session with a reason, an expiry and an audit
record visible to its owner.

## Requirements

PLT-01, PLT-04, PLT-05, PLT-06 and PLT-07. NFR-20 covers support access without sharing
salon passwords. Requirement text is in `docs/brd/requirements.yaml`.

## How it is built

API and operator web console in milestone M1-web, depending on the verified F02 merge.
Operator identity and platform records remain separate from salon identities and tenant
records. The console uses its reserved hostname; support access crosses into a salon only
through an explicitly authorised session. See `docs/architecture/ARCHITECTURE.md`,
`docs/architecture/multi-tenancy.md` and `docs/architecture/identity-and-access.md`.

The stages add usable API and web behavior together. Existing shared infrastructure,
identity, audit, outbox and scheduling foundations are reused where their contracts fit.
Local verification uses synthetic salons, fake delivery and simulated billing events.
Real charges, messages and deployments need separate owner requests.

## Stages

The order and status of the stages is in `features/README.md` (generated). This section explains
why the feature is divided the way it is.

| Stage | Delivers | Why it is its own stage |
| --- | --- | --- |
| F03-S01 Operator access, salon provisioning and plans | Operator sign-in and console; create and list salons with profile, unique subdomain, locale and assigned plan; define plan features and limits | Establishes the platform authority boundary and usable provisioning before subscriptions or support can use it |
| F03-S02 Entitlements and subscription billing | Backend enforcement and client display of entitlements; trials, subscriptions, invoices, recorded bank transfers, overdue reminders and grace-to-read-only suspension | Combines the purchased plan with its enforced limits and billing state; checks time boundaries and repeated events together |
| F03-S03 Salon lifecycle, export and retention | Suspend, reactivate and close a salon; deliver its full export to its owner and delete tenant data after the agreed retention period | Makes irreversible lifecycle transitions explicit and verifies access, export and deletion together |
| F03-S04 Audited support sessions | Start and end support access for one salon with a reason and expiry; show the session and its actions to the owner | Introduces the controlled cross-salon support boundary after lifecycle and access rules exist |

## Feature-level test focus

Independent QA runs once after all four stages close. It covers operator and tenant identity
separation, wrong-host and wrong-role rejection, concurrent subdomain allocation, entitlement
limits, duplicate billing events, trial and grace boundaries, read-only suspension, closure
export and retention, and support session expiry, revocation and owner-visible audit.

The main journey creates a salon, assigns and bills its plan, exercises its limits, suspends
and reactivates it, starts and ends support access, then closes it and verifies export and
retention. A second synthetic salon must remain isolated throughout. Local QA does not
certify a live payment provider, real message delivery or public TLS.

## Decisions and open questions

- The owner approved proceeding with the outlined delivery plan on 2026-10-15. Stage
  specifications follow the outline; implementation and independent feature QA follow their gates.
- The S01 technical policies are in `docs/architecture/operator-console.md`: separate operator
  authority, atomic first-Owner provisioning, immutable unpriced plans and safe creation retries.
- The supplied prototype has no operator-console screens. Stage planning must map the console
  to the existing design standards and staff shell conventions without adding customer web UI.
- BRD decision D-06 (commercial pricing and packaging) remains open. Published tiers or prices
  must not be inferred from the BRD's commercial hypothesis. Stage planning must distinguish
  configurable plan definitions and synthetic billing fixtures from approved commercial terms.
- On 2026-10-18 the owner set the operating terms (decision 049): 14-day trial, monthly invoices
  due 7 days after issue, reminders on the due date and 3 and 10 days after, read-only 14 days
  after the due date, deletion 30 days after closure, support sessions of 5–120 minutes (default
  30). The S02–S04 policies are in `docs/architecture/operator-console.md`; D-06 stays open.
