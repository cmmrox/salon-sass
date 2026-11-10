# Roadmap

The order in which the product is delivered. It was set by the owner: the salon administration web application and
each salon's customer booking page first, released, and only then the React Native customer mobile app. The booking
page was restored by CR-028 (decision 117); there is still no customer account area, marketing website or marketplace.
Only the owner changes this order.

The live status of every feature is in `features/README.md` (generated). This file explains the
order and is edited by the lead when the owner changes it.

## Milestones

| Milestone | What is delivered | Features | Starts when |
| --- | --- | --- | --- |
| `M1-web` | The operator console, each salon's owner/admin/staff workspace, and each salon's customer booking page | F01 to F14, F24 | Now |
| `M2-mobile` | The customer mobile app, reached through the shared app (D-01) | F15 | Every `M1-web` feature is released |
| `M3-growth` | Dedicated salon apps, service customisation, multi-branch operations, Bridal/occasion workflows, expanded commerce, commission | F16 to F21, F25 | Every R1 feature (F01 to F15, F24) is released |
| `M4-assistance` | Guided customer and staff AI assistants with human handoff | F22 | Every `M3-growth` feature is released |

A milestone may ship as more than one version. `M2-mobile` waits for the last `M1-web` feature to
be released, not merely complete; `features/README.md` shows "after M1-web is released" until then,
and `feature-new` refuses to start F15 before it.

If the owner changes the order, the lead edits this file and the `milestone` of the features
affected, and records the decision. If the owner wants one feature started early without changing
the order, the lead records the instruction in that feature's `started_early` field.

## M1-web: the web application

Delivered in this order, which since CR-028 is not the order of the numbers: F07's follow-up, then F12, then F08 to
F11, F13, F14 and F24. Each feature depends on the ones it names in its `feature.yaml`; F12 and F24 carry
`started_early` so that the tooling follows this order.

| Feature | Delivers |
| --- | --- |
| F01 Platform foundation and tenant isolation | One deployment, isolated salons, a web address per salon, the shared services |
| F02 Sign-in, accounts and roles | Staff sign-in, roles, sessions, audit trail; retained customer API foundations only |
| F03 Operator console | Creating, billing, suspending and supporting salons |
| F04 Salon setup | Profile, branding, hours, holidays, policies |
| F05 Service catalogue | Services, categories, variants, prices, tax |
| F06 Staff, schedules and leave | Professionals, working hours, leave, blocked time |
| F07 Booking engine and staff booking foundations | Staff booking controls and shared availability/hold/confirmation APIs. Its CR-028 follow-up makes a booking a visit of several services, with notes, walk-ins without a number and the corrected states |
| F12 Customer records | The salon's record of each customer, built before payments so that payments, messages and the booking page attach to it (decision 115) |
| F08 Payments, deposits and refunds | Staff payment controls, deposits recorded outside the gateway, and shared gateway APIs; customers pay on the booking page (F24) and later in the app (F15) |
| F09 Manage a booking | Staff reschedule and cancel controls; customers do the same on the booking's page (F24). The waitlist moved to R2 |
| F10 Salon calendar and front desk | The staff's day: calendar, walk-ins, checkout with tips, counter sales, printed receipts, the day-end close |
| F11 Notifications and reminders | WhatsApp, SMS and email, with templates, preferences and a cost log |
| F13 Reports | How the business did over a period |
| F14 Guided onboarding and data import | A new salon sets itself up |
| F24 Customer booking page | A customer books, pays, confirms, reschedules and cancels in a browser on the salon's own address, with nothing to install |

CR-023 adopted the current prototype and refined these features without enlarging R1's shape. The
salon's settings are tabbed, with hours, closures and policies. Services and the team have full
add, edit and remove with guards. The desk books any open day. Bookings follow one state model.
Checkout takes part payments by tender and closes the cash drawer at day end. Staff send deposit
links. Messaging warns before its balance runs out. Reports show collections by tender and deposits
held. From F04 the data carries a branch scope, and from F05 service time is stored as segments, so
that R2 needs no rework (decisions 073 to 076).

During M1, salon owners, managers and relevant staff use the web workspace and the operator uses the
console. From F24, customers book and manage a booking in a browser on the salon's booking page,
verifying their number with a one-time code; they have no account area there. Staff can
manage customer records and bookings. Build versioned shared APIs and retain already-implemented
customer identity/privacy endpoints as foundations for mobile, without starting a mobile client.
The app's own screens and journeys (its home, My bookings, push, notification preferences) are
delivered by F15 after every M1 feature is released.

## M2-mobile: the customer app

| Feature | Delivers |
| --- | --- |
| F15 Customer mobile app | The app shell, opening a salon in the shared app, customer sign-in/accounts, booking, payments and appointment management, push notifications, calendar and directions, minimum-version control |

F15 owns the customer app and booking-flow requirements, including IAM-02 and APP-06. Its
`extends` list maps shared backend requirements to their first customer-facing mobile screens:
sign-in, profile/privacy, appointment self-service, payments, branding and notification preferences.
A completed backend requirement is not evidence of a delivered customer interface.

D-01 is settled (decision 071): the shared container app is the standard way customers reach a salon,
and a dedicated app is a plan feature published in the salon's own developer accounts (F16, M3).

## M3-growth: R2 apps, service, branch, occasion and commerce expansion

Approved under CR-020; F16 joined under CR-023. These features remain backlog until their dependencies have passed QA, merged
and the milestone gate opens. Their stage outlines do not authorise early implementation.

| Feature | Delivers |
| --- | --- |
| F16 Dedicated salon apps and store pipeline | A salon's own app in the stores under its own developer accounts, built and updated by an automated pipeline, for plans that include it |
| F17 Service customisation and treatment readiness | Conditional options and add-ons, pricing types, packages, resources, processing time, consultations and customer intake |
| F18 Multi-branch salons and module administration | Service lines, branch-scoped operations and reports, mobile branch selection and safe module offboarding |
| F19 Bridal and occasion operations | Staff enquiry, package and quote workflows, party/event scheduling, travel, ledgers, changes and reports |
| F20 Bridal customer mobile experience | Mobile discovery, enquiry, quote acceptance, verified payment, tracking and change requests |
| F21 Offers, loyalty and gift cards | Offers and rewards, gift-card sales and ledgers, deterministic checkout and safe reversal |
| F25 Commission and staff performance | Commission per service and product for each professional, tips kept separate, and the report (CR-028) |

## M4-assistance: R3 guided AI

F22 AI-assisted booking and front desk follows the authoritative R2 workflows. It includes customer
and staff assistants, WhatsApp/shared-inbox integration, explicit action confirmation, human handoff,
privacy and provider-failure controls, and subscription/usage administration.

Other unscheduled Release 2 and 3 candidates remain in `features/BACKLOG.md`; they are not committed scope.
