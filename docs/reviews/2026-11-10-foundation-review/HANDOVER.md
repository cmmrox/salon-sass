# Handover: foundation fixes before F08

For the lead, developer and QA sessions that follow the review (Claude Opus at high effort; `docs/operations/git-delivery.md`,
"Models by role"). Everything here is local work. Nothing authorises a deploy, a tag, a real payment or a real message.

## Read first

1. `README.md` beside this file: the verdict and what came of the review.
2. `docs/changes/CR-028-foundation-review-requirements.md` and `docs/changes/CR-029-review-before-qa-and-leaner-verification.md`.
3. Decisions 113 to 123 in `docs/decisions/DECISION_LOG.md`. They are settled; do not reopen one without new information.
4. The report a package names, for its evidence. Finding ids (H1, M3, L4) are the report's own.

## Where things stand

- The documents, register, features, bugs and skills were changed on `charithm/docs/CR-028-foundation-review`. No
  application code, test or tooling was changed. Start each package from `origin/main` once that branch is merged.
- F07's merge is recorded, and `qa/scripts/sdlc validate` passes. `qa/scripts/sdlc status` names `feature-new F12` as
  next; **do not start it until the packages below are merged**, because F12 builds on the reworked booking.
- Every code finding in the reports comes from reading the code. Nothing was built, run or measured. Treat each as a
  strong lead: reproduce a defect before fixing it, and measure before spending effort on a performance finding.
- The skill changes (CR-029) were not tried in a fresh session. Run scenarios 39 to 42 of
  `docs/operations/skill-evaluation.md` at the start of the next feature.

## Status on 2026-11-10

- **Package 3** is merged (PR #28): parallel CI, the tooling fixes and decision 124.
- **Packages 1 and 2** are one pull request from `charithm/bugfix/foundation-review`: the seven fixes below, BUG-F02-017
  (found by package 2) and BUG-F03-011 (found while verifying BUG-F03-010; decision 125), each verified by QA with a
  regression case that fails without its fix; the stale regression cases repaired; smoke 11 of 11; regression 107 of
  108, the one failure an environment fault that passed when rerun.
- **Still open from these packages:** BUG-F01-009 is `fixed` and unverified (it needs the rolling-deploy check under
  load, about 35 minutes, which runs only when the owner asks); the repaired k6 entries `rush` and `web-rush` are unrun
  for the same reason; regression takes 53 minutes against its 20-minute budget (sharding is in `features/BACKLOG.md`).
- **Moved to package 4** by rulings during package 1: the platform-wide sign-in limit per address, password hashing
  still inside a transaction in two admin paths, `Retry-After` on the staff 429, and not counting a 503 toward the
  attempt limits. Package 5 also takes the holiday import's and the export download's time budgets.
- **Next:** packages 4 and 5 (in parallel), then package 6.

## How to work the packages

- A **bug** is fixed with `bug-fix`: reproduce, a test that fails for the bug's reason, the fix, then QA verifies. A bug
  that does not reproduce gets a `developer_note`, not a silent close.
- A **refactor** follows "Refactors and upgrades" in the lifecycle: its decision is already recorded; the developer's
  gates are its proof, with every existing test's assertions unchanged, then smoke and regression, then one pull request.
- **F07's follow-up** follows decision 098: the feature keeps its statuses and merge record, the new criteria join its
  stage specs, QA re-verifies the changed scope, and it ships as one pull request.
- When the code contradicts a decision, or a decision cannot be met as written, stop and ask the lead. Do not adapt the
  decision in the code.
- Packages 1, 2 and 3 can run at once. Packages 4 and 5 can run at once (API and web). Package 6 comes after 4 and 5.

## Package 1: defects (`bug-fix`, branch `charithm/bugfix/foundation-review-defects`)

| Bug | What | Note |
| --- | --- | --- |
| BUG-F02-016 | Staff sign-in checks the password inside a transaction and a lock | Follow `OperatorSignInServiceImpl`; decision 120 also asks for a cap on checks in flight and one platform-wide limit per address |
| BUG-F03-009 | A deleted salon's registry row keeps its profile and the owner's contact | Decide with the lead which columns the tombstone keeps; `docs/architecture/operator-console.md` is corrected at close |
| BUG-F03-010 | Each billing reminder requests another full charge while one is unsettled | No real provider is connected; fix before billing is switched on |
| BUG-F04-001 | An upload over a slow connection is answered 503 | The limit starts after the body is read (`docs/engineering/web.md`, "Structure") |
| BUG-F06-002 | Moving a service to another category changes who performs it, unchecked | `catalogue` asks through a contract `staff` or `booking` implements (the decision 087 pattern) |
| BUG-F07-006 | A lost answer to holding a time leaves a hold the desk cannot use | Keep the idempotency key when the failure is not an answer from the API |

BUG-F05-002 and BUG-F06-001 (idle sign-out on Services, Products, Team and schedules) are closed by package 5.

## Package 2: QA, regression back to passing (QA role)

- Repair the 10 stale F01 to F03 expectations listed on PR #21 and in `qa-and-delivery.md`.
- Find the root cause of the two intermittent failures (TC-F03-S02-006, TC-F04-S03-032): a bug for a product defect,
  a repair for a test defect, never a retry. (Done: the first was a test defect, the second is BUG-F02-017.)
- Verify BUG-F01-009, which is `fixed` and was never verified.
- Run regression on `main` and record the run. From here it runs at every feature close (decision 122).

## Package 3: tooling and CI (lead's paths; branch `charithm/chore/ci-and-runner`; each change with a test, in its own commit)

From `qa-and-delivery.md`, recommendations 5, 6, 8, 9, 10 and 11:

- **CI:** parallel jobs with the fast ones first and Maven split by module; add `qa/scripts/sdlc validate`, the
  accessibility check, `knip`, the format check, QA type-check and smoke. It took 24 minutes in one serial job.
- **Self-inflicted gate failures:** derive the migration list and the problem-code list in tests from the code; run
  Spotless before the long build.
- **Runner:** `--entry` and `--rerun-failed`, a time limit per entry, a warning when a suite passes its budget.
- **Build identity:** one command that rebuilds and restarts the QA instance and asserts `/api/v1/meta` equals `HEAD`;
  `global-setup` refuses a mismatch.
- **Coverage:** count a case only from a real `test(` call, not a comment, `test.skip` or `test.fixme`.
- **Conformance:** an ESLint rule against raw controls and CSS Modules, replacing the regex test.
- **Pending criteria:** let criteria in a spec's `## CR-nnn` section be pending while that change request is `approved`,
  so decision 098's first commit does not fail `validate` (see CR-028, "Criteria for F07's follow-up").
- **Status follows the lifecycle** (from the review of PR #27): `sdlc status` names `feature-review` before
  `feature-verify`, using a new `integration.reviewed_revision`; a feature waits, and `start-check` refuses, while a
  feature it depends on has stages named by an `approved` change request (today F12 is offered although CR-028's
  follow-up to F07 is pending); a requirement added by such a change request is shown as pending in `qa/coverage.md`,
  not as passed with the feature's earlier QA; and a lower-numbered feature of a later milestone is not a predecessor
  (F25 and F22 would otherwise wait for each other).
- **Regression time:** shard across QA instances now; a test clock per salon later, which is what allows parallel workers.

## Package 4: platform hardening (refactor, branch `charithm/refactor/platform-hardening`)

Decisions 119 and 120. Evidence in `api-platform.md` and `api-domain.md`.

| Finding | Change |
| --- | --- |
| Platform H2 | The outbox dispatcher claims only event types with a subscriber in its own build; a fixture proves an unsubscribed type stays pending |
| Platform M1 | Statement, lock and idle-in-transaction timeouts on the application's database roles; the 3-second client timeout no longer caps jobs and exports |
| Platform M2 | Authenticating a request takes no salon-wide exclusive lock and writes nothing on a read |
| Platform M3 | The outbox and scheduler stop looping over every salon on every tick; a job cannot run twice past its hold |
| Platform M5, M6 | Lockout that cannot be aimed at a named Owner indefinitely; limited attempts do not flood the audit table; ended sessions and refresh history are cleaned and indexed |
| Platform M8 | Ciphertexts carry a key id, so the encryption key can be rotated |
| Domain M4 | Provided read interfaces run read-only in their own transaction and refuse a writing caller, with an architecture test |

Left for the feature that needs them, recorded so they are not lost: platform M4 and M10 (token-only tenancy and the
platform-wide ceiling on customer codes) when F24 is planned; M9 (the closure export in memory) before a salon of real
size is closed; M7 (salon endpoints on the platform connection) as a rule for new endpoints.

## Package 5: web data layer (refactor, branch `charithm/refactor/web-data-layer`)

Decision 121 and `docs/engineering/web.md`, "Structure". Evidence in `web.md`.

| Finding | Change |
| --- | --- |
| Web H1, H3 | One request module that renews once and retries, maps errors once and discards an answer that is no longer current; TanStack Query hooks per module replace the hand-written hooks (pin the version and report it in the handoff for `docs/architecture/tech-stack.md`). Closes BUG-F05-002 and BUG-F06-001 |
| Web H3 | A BFF read no longer costs three upstream calls: drop the `/identity/me` pre-flight and the proxy's salon lookup for BFF calls where the API authenticates on its own |
| Web H4, M5 | Each module registers its own pages, actions and permissions; `identity` keeps sign-in and sessions only; one route table replaces the eight places a page is declared (`/workspace/bookings` and `/workspace/team` are missing from `PROTECTED` today) |
| Web M2 | A failed re-read keeps the last good list and says it is stale; Bookings refreshes by polling |
| Web M3 | `error.tsx` per route group and `global-error.tsx`; formatters do not throw on a bad date |
| Web M6, M7 | Static assets cost no salon lookup and are cacheable; pages send a content security policy, `frame-ancestors`, a referrer policy and `nosniff` |

Public behaviour and every existing test's assertions stay the same. At close the lead corrects the rest of
`docs/engineering/web.md` that `web.md` M9 lists as untrue (zod schemas, entitlement-driven navigation, the 200-line
rule, the knip gate). Web M8 (Sinhala and Tamil readiness) waits for PLT-15.

## Package 6: F07's follow-up (decision 098, branch `charithm/feature/F07-cr-028-visits`)

1. **Lead:** copy CR-028's three criteria sections to the end of the stage specs as the first commit; confirm the changed
   New appointment and appointment sheets with the owner against the prototype before the screens are built.
2. **Developer, API:** the visit and its service lines (decision 114) by forward migration, keeping the data in the
   local and QA databases; the state changes of decision 116; any country's mobile and none for a walk-in; the note,
   which needs a history that is not state-only (`api-domain.md` M6; also allow a customer as an actor and a missing
   `created_by`, which F24 needs); availability for a list of services and for a range of days (M8); the money type,
   the deposit calculator and rates in basis points (decision 118, M2), correcting the catalogue comments that say a
   booking keeps a tax version (M1); the read interface and an event per change of a visit (decision 119, M5).
   Small ones worth taking while the code is open: L1 (purge ended holds), L4 (find by reference without a date
   range, which S01 AC-24 asks for), L5 (a repeated confirm answers the booking).
3. **Developer, web:** the screens of S03's new criteria, on package 5's data layer.
4. **QA:** rewrite F07's cases that place, read or change a booking; add a case per new criterion; apply
   `docs/qa/test-types.md`, "Where QA's time goes"; targeted re-verification of F07, then regression.
5. **Lead:** `feature-review` on the whole diff before QA starts; rewrite `docs/engineering/backend.md`, "Booking
   integrity" at close; mark CR-028's F07 part implemented when the pull request's merge is verified.

## Before `feature-new F12`

- Packages 1 to 6 merged, each merge verified, smoke passing on `main`, regression passing on `main`.
- Every bug above `verified`.
- A review of the six pull requests' diffs on the strongest model available (decision 122), as the owner asked.
- F12's design check answers how a visit is linked to a customer when two people share a number (decision 115, CRM-02).

## Decide when planning, not now

- **F08:** the PayHere sandbox result (partial refunds, plan limits, address whitelisting); the gateway notification
  route exempt from read-only and suspension (decision 120); deposits recorded outside the gateway (PAY-16); a payment
  link's own expiry (PAY-09).
- **F10:** a drag library with a non-drag alternative, polling or a streamed channel, the forms approach for checkout
  (`web.md`, "Decisions needed before F10"); booking over a booking (decision 123); a sale that is not a booking (PAY-18).
- **F10 and F13, for F18:** which records are shared across branches, whether a person works at two, and the branch on
  the signed-in principal and the audit trail (`api-domain.md` M7, `api-platform.md`, "Readiness").
- **F11:** two-way reminders through the booking's link; a channel for staff notifications; the SMS sender per salon.
- **F13:** schedule history for past utilisation (`api-domain.md` M10).
- **F24:** the platform-wide ceiling on one-time codes, the purge of ended holds, the page's design confirmation.

## What the review did not cover

No build, test, query plan or load was run. Dependency versions were not checked against advisories. The production
edge and any real environment were not read. F04 to F06 specs were spot-checked, not read line by line. Market facts
from secondary sources are listed as open checks in `README.md`.
