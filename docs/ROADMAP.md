# Roadmap

The order in which the product is delivered. It was set by the owner: the salon administration web application first,
released, and only then the React Native customer mobile app. No customer website is provided (CR-010). Only the owner changes this order.

The live status of every feature is in `features/README.md` (generated). This file explains the
order and is edited by the lead when the owner changes it.

## Milestones

| Milestone | What is delivered | Features | Starts when |
| --- | --- | --- | --- |
| `M1-web` | The operator console and each salon's owner/admin/staff workspace; no customer web surface | F01 to F14 | Now |
| `M2-mobile` | The customer mobile app, and a dedicated store app per salon | F15, F16 | Every `M1-web` feature is released |

A milestone may ship as more than one version. `M2-mobile` waits for the last `M1-web` feature to
be released, not merely complete; `features/README.md` shows "after M1-web is released" until then,
and `feature-new` refuses to start F15 or F16 before it.

If the owner changes the order, the lead edits this file and the `milestone` of the features
affected, and records the decision. If the owner wants one feature started early without changing
the order, the lead records the instruction in that feature's `started_early` field.

## M1-web: the web application

Delivered in this order. Each feature depends on the ones before it that it names in its
`feature.yaml`.

| Feature | Delivers |
| --- | --- |
| F01 Platform foundation and tenant isolation | One deployment, isolated salons, a web address per salon, the shared services |
| F02 Sign-in, accounts and roles | Staff sign-in, roles, sessions, audit trail; retained customer API foundations only |
| F03 Operator console | Creating, billing, suspending and supporting salons |
| F04 Salon setup | Profile, branding, hours, holidays, policies |
| F05 Service catalogue | Services, categories, variants, prices, tax |
| F06 Staff, schedules and leave | Professionals, working hours, leave, blocked time |
| F07 Booking engine and staff booking foundations | Staff booking controls and shared availability/hold/confirmation APIs |
| F08 Payments, deposits and refunds | Staff payment controls and shared gateway APIs; customer payment UI waits for F15 |
| F09 Manage a booking | Staff reschedule, cancel and waitlist controls; customer self-service waits for F15 |
| F10 Salon calendar and front desk | The staff's day: calendar, walk-ins, checkout |
| F11 Notifications and reminders | WhatsApp, SMS and email, with templates, preferences and a cost log |
| F12 Customer records | The salon's record of each customer |
| F13 Reports | How the business did over a period |
| F14 Guided onboarding and data import | A new salon sets itself up |

During M1, only salon owners, managers and relevant staff use the web workspace; the operator
uses the console. Customers do not book, sign in or manage accounts in a browser. Staff can
manage customer records and bookings. Build versioned shared APIs and retain already-implemented
customer identity/privacy endpoints as foundations for mobile, without starting a mobile client.
Customer-facing screens and self-service journeys in booking, payments, waitlists and notification
preferences are delivered by F15 after every M1 feature is released.

## M2-mobile: the customer app

| Feature | Delivers |
| --- | --- |
| F15 Customer mobile app | The app shell, opening a salon in the shared app, customer sign-in/accounts, booking, payments and appointment management, push notifications, calendar and directions, minimum-version control |
| F16 Dedicated salon apps and store pipeline | A salon's own app in the stores under its own developer accounts, built and updated by an automated pipeline |

F15 owns the customer app and booking-flow requirements, including IAM-02 and APP-06. Its
`extends` list maps shared backend requirements to their first customer-facing mobile screens:
sign-in, profile/privacy, appointment self-service, payments, branding and notification preferences.
A completed backend requirement is not evidence of a delivered customer interface.

Open decision D-01 (the mobile distribution model) must be settled before F15 is planned.

## After Release 1

Candidates for Releases 2 and 3 are in `features/BACKLOG.md`. They are not scheduled.
