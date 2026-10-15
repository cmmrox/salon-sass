---
id: CR-010
title: Salon staff web only and deferred React Native customer app
status: approved
requested_by: owner
requested_on: 2026-10-15
decided_on: 2026-10-15
requirements: [IAM-01, IAM-02, IAM-04, APP-06, BKG-16, MKT-01, BRD-03, BRD-04, BRD-12, NFR-21]
features: [F01, F02]
stages: [F01-S03, F02-S04]
integration_branch: charithm/feature/F02-staff-web-only
---

# CR-010 Salon staff web only and deferred React Native customer app

## Request

The owner says the Customer HTML tab is the design for a React Native mobile application,
not a customer website. Only salon owners, administrators and relevant staff use the salon web
backend. Finish and release the salon administration web application before developing the
customer React Native application. Correct this before the next feature and update shared skills.

## Impact and decision

Approved directly by the owner's current instruction; no additional confirmation is needed.
This supersedes the customer-web interpretation in the original BRD snapshot and CR-007.

- Reopen complete, unreleased F01-S03 and F02-S04 and their features. Tenant root enters
  `/workspace`; legacy `/sign-in` and `/account` redirect to staff sign-in. Customer BFF actions
  return 404 without upstream calls, customer cookies or message requests. No customer web UI,
  booking site, sign-in, profile or privacy flow is delivered. Staff and tenant security hold.
- Keep the already-built customer Java APIs and data as backend foundations for M2-mobile;
  they do not constitute a delivered customer application. No mobile implementation now.
- F07 becomes booking engine and staff booking foundations; customer-facing APP requirements
  and IAM-02 move to F15. Customer self-service variants of F08/F09/F11 are deferred to F15;
  M1 builds staff controls and backend capability only. F15 waits for every M1 feature's release.
- Change IAM-01, IAM-02, IAM-04, APP-06, BKG-16, MKT-01, BRD-03/04/12 and NFR-21 wording to name these boundaries.
  Other customer requirements remain valid for the future mobile client and shared API.
- QA removes obsolete customer-browser expectations, verifies changed staff/tenant routes and
  forbidden customer BFF paths independently, and retains unaffected API/security evidence.
  No unrequested regression, staging, TLS, deployment, real message or payment is included.
- Preserve PR #5 repair history and original PR #3/#4 history. The correction uses one grouped
  F01/F02 repair PR, separate lead/developer/QA ownership and proportional verification.

## Design and process

Default the HTML prototype to Salon web. Label its Customer preview as a deferred React Native
mobile design reference; it is not a second web app or a production navigation choice. Keep the
future customer design available for M2. Skills must resolve platform, audience and milestone
from product documents before mapping an HTML reference into a running application.

## Completion evidence

Implementation, independent scoped verification and delivery evidence are recorded in the
feature/stage yaml and scoped QA assessment. F03 waits for this repair's verified owner merge.
