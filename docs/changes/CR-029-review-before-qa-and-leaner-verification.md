---
id: CR-029
title: Review before QA, a design check at planning, and leaner verification
status: approved
requested_by: owner
requested_on: 2026-11-10
decided_on: 2026-11-10
requirements: []
features: []
stages: []
---

# CR-029 Review before QA, a design check at planning, and leaner verification

## Request

The owner asked whether the skills and the lifecycle support the whole project, and what would deliver the same quality
faster. The evidence is in `docs/reviews/2026-11-10-foundation-review/qa-and-delivery.md`; the owner approved the
recommendations on 2026-11-10.

## Why

- **Defects are found after the feature is closed.** In F04 to F07 QA wrote 449 tests and found one product bug, while
  the automated review on the feature's pull request found ten, after QA had passed and the feature was closed. F07 was
  closed twice for that reason. Nobody reads the code before then: `stage-close` is a light check and QA tests from outside.
- **One class of bug keeps coming back**: about eleven web defects where a screen kept a stale answer or acted on a late
  one, seven of them found only by that review.
- **Nothing asks what later features need.** F07 built one service per booking, a free-text customer and one event, each
  correct for its spec and each in the way of F08 to F15. Comparison with other products happened only in QA's review
  after the build, which produced CR-024 to CR-027 as rework after merge.
- **Verification is slower and looser than the documents say.** Regression is failing on `main` and takes 78 minutes
  against a 20-minute budget on one worker; CI takes 24 minutes in one serial job and runs neither `sdlc validate`, the
  accessibility check nor smoke; QA's API tests largely repeat the developer's integration tests.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None |
| Features and stages | None reopened. The changes apply from the next feature planned |
| Lifecycle (`salon-sdlc`) | `feature-new` gains a design check and a market check; `stage-close` rules on every difference a handoff reports and pushes the stage to a draft pull request; a new `feature-review` step answers every review finding before `feature-verify`; `feature-close` runs regression while the pull request waits and puts QA's "should" suggestions to the owner before the merge |
| QA method (`salon-qa`, `docs/qa/`) | The API layer of a feature pass is limited to what only QA proves from outside (isolation, roles, simultaneous requests, the contract, journeys); the time goes to the browser: stale and late answers, idle sessions, double submission |
| Developer method (`salon-dev`, `docs/engineering/web.md`) | Web data reads go through one shared layer that discards answers that are no longer current, with a test per screen for a late answer |
| Agents | The developer and QA agents name their model, so a lead session on a stronger model still builds and verifies on the model the owner chose for that work (`docs/operations/git-delivery.md`, "Models by role") |
| Tooling and CI | Listed for the developer in the handover: `validate`, the accessibility check and smoke in CI; parallel CI jobs; runner options to rerun one entry or the failed ones; a check that the product under test was built from the tested commit; a case counted only from a real test; criteria pending under an approved change request |
| Size and delay | Skill and document edits now; the tooling is about two days of developer work, done with the foundation fixes before F08 |

## Recommendation

Approve. Each change moves a check to where its defect is cheapest to find, and none removes proof a release relies on.

## Decision

Approved by the owner on 2026-11-10, including the trade in QA's scope: QA no longer repeats at the API what the
developer's integration tests already prove, which gives up some independence there for more coverage of what has
actually escaped. Decision 122.
