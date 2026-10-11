# Roadmap

The order in which the product is delivered. It was set by the owner: the web application first,
released, and only then the customer mobile app. Only the owner changes this order.

The live status of every feature is in `features/README.md` (generated). This file explains the
order and is edited by the lead when the owner changes it.

## Milestones

| Milestone | What is delivered | Features | Starts when |
| --- | --- | --- | --- |
| `M1-web` | The tenant-based web application: the operator console, each salon's staff workspace, and each salon's public booking site at its own web address | F01 to F14 | Now |
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
| F02 Sign-in, accounts and roles | Staff and customer sign-in, roles, sessions, audit trail |
| F03 Operator console | Creating, billing, suspending and supporting salons |
| F04 Salon setup | Profile, branding, hours, holidays, policies |
| F05 Service catalogue | Services, categories, variants, prices, tax |
| F06 Staff, schedules and leave | Professionals, working hours, leave, blocked time |
| F07 Online booking on the web | The customer books at the salon's web address |
| F08 Payments, deposits and refunds | Online payment through the salon's own gateway account |
| F09 Manage a booking | Reschedule, cancel, waitlist |
| F10 Salon calendar and front desk | The staff's day: calendar, walk-ins, checkout |
| F11 Notifications and reminders | WhatsApp, SMS and email, with templates, preferences and a cost log |
| F12 Customer records | The salon's record of each customer |
| F13 Reports | How the business did over a period |
| F14 Guided onboarding and data import | A new salon sets itself up |

What this means while there is no app:

- **Customers book in their phone's browser.** The booking site is the only customer surface in
  this milestone, so it is designed for a 360-pixel-wide phone first and tested there on every stage.
- **The API is built for the app from the start**: versioned paths, token sign-in, a generated
  contract, branding delivered at run time. `M2-mobile` should need no rework of the backend.
- **Notifications go out by WhatsApp, SMS and email.** Push arrives with the app.

## M2-mobile: the customer app

| Feature | Delivers |
| --- | --- |
| F15 Customer mobile app | The app shell, opening a salon in the shared app, booking and managing appointments as on the web, push notifications, calendar and directions, minimum-version control |
| F16 Dedicated salon apps and store pipeline | A salon's own app in the stores under its own developer accounts, built and updated by an automated pipeline |

Requirements that the web milestone delivers on the web and F15 delivers again in the app are
listed under `extends` in F15's `feature.yaml`: the booking flow and "my bookings" (APP-01, APP-02,
APP-03, APP-06 to APP-10), customer sign-in and account deletion (IAM-01, IAM-05), run-time branding
(BRD-04), and the push channel and in-app notification centre (NOT-02, NOT-08).

Open decision D-01 (the mobile distribution model) must be settled before F15 is planned.

## After Release 1

Candidates for Releases 2 and 3 are in `features/BACKLOG.md`. They are not scheduled.
