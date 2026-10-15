# F02 Sign-in, accounts and roles

CR-010: web delivery is staff-only. Customer OTP/profile/privacy APIs remain foundations for the
later React Native app; no customer browser interface is delivered. See `docs/ROADMAP.md`.


## What the customer gets

Customers sign in to one salon with a mobile code and manage their own profile, preferences,
signed-in devices and personal data. Staff sign in to that salon's workspace with a password and
optional two-step verification, required for Owners; permissions determine what each person can do.

## Requirements

IAM-01, IAM-03 to IAM-11. NFR-08 and NFR-10 focus the security and audit checks.

## How it is built

API and responsive web, on F01's isolated transactions, immutable audit trail and encrypted outbox
messages. Contracts and decisions are in [identity and access](../../docs/architecture/identity-and-access.md).
No mobile app, real message sending or deployment is part of this feature. The web supplies IAM-05
self-service during M1-web; F15 will reuse the endpoints in the mobile app.

## Stages

| Stage | Delivers | Why it is its own stage |
| --- | --- | --- |
| F02-S01 | Callable staff password/MFA sign-in, sessions, account administration, live permission checks and audit reads | Establishes the security boundary used by every later feature |
| F02-S02 | Callable customer code sign-in with local message capture and durable abuse limits | Isolates single-use races and the message-cost risk |
| F02-S03 | Callable customer profile, export and deletion with immediate session revocation | Establishes ownership and privacy guarantees before screens |
| F02-S04 | Responsive staff and customer sign-in, account, device and staff access screens | Completes human journeys across the established contracts |

## Feature-level test focus

Independent QA runs only F02, once all four stages are closed. Prove staff password/MFA and customer
code journeys, separate cookies on one hostname, tenant isolation for every endpoint and new record,
each role for every protected action, immediate permission/revocation effects, one-use code and
refresh races, abuse ceilings and alerts, safe export/deletion, browser CSRF and server-only tokens,
all design widths, three salon brands, accessibility, database/provider recovery and audit secrecy.
Public pages remain usable without sign-in. Earlier features' automation runs only on owner request.

## Decisions and open questions

Decision 035 records technical policies and the implementation order; the owner requested the
complete feature, independent QA and merge on 2026-10-13, authorizing progression through this
outline without a second planning approval and the F02 PR merge after checks. D-07 real message
sender, D-02 domain and D-08 hosting remain untouched; local capture exercises the integration.
