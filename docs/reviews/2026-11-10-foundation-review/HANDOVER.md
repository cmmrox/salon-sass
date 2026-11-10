# Handover: foundation fixes before F08

For the lead, developer and QA sessions that follow the review (Claude Opus, high effort). Read
`README.md` beside this file, `docs/changes/CR-028-foundation-review-requirements.md`,
`docs/changes/CR-029-review-before-qa-and-leaner-verification.md` and decisions 113 to 123 first. Finding ids (H1, M3)
refer to the report named. Every package is local only; nothing here authorises a deploy.

## Where things stand

- Branch `charithm/docs/CR-028-foundation-review` holds the documents, register, features, bugs and skills. It needs
  the owner's pull request and merge before any package starts. No application code was changed.
- F07's merge is recorded. `qa/scripts/sdlc validate` passes on the branch.
- Not done: the skill changes were not tried in a fresh session (`docs/operations/skill-evaluation.md` needs
  scenarios for `feature-review`, the design check and the draft pull request).

## Packages, in order

| # | Kind and branch | Scope | Proof |
| --- | --- | --- | --- |
| 1 | `bug-fix`, `charithm/bugfix/foundation-review-defects` | BUG-F02-016, BUG-F03-009, BUG-F03-010, BUG-F04-001, BUG-F06-002, BUG-F07-006. Reproduce each first | A failing test per bug, the affected stages' gates, QA verifies each |
| 2 | QA, same wave | Repair the 10 stale regression cases and file the two intermittent ones (TC-F03-S02-006, TC-F04-S03-032) (`qa-and-delivery.md`); verify BUG-F01-009 | Regression passes on `main` |
| 3 | Tooling (lead's paths, own commits with tests), `charithm/chore/ci-and-runner` | `qa-and-delivery.md` recommendations 5, 6, 9, 10, 11: `validate`, the accessibility check and smoke in CI; parallel CI jobs; runner `--entry` and `--rerun-failed`; build-identity check; a case counted only from a real test. Also let criteria under an `approved` change request be pending in `validate` (CR-028, "Criteria for F07's follow-up") | `sdlc selftest`; CI green |
| 4 | Refactor, `charithm/refactor/platform-hardening` | Decisions 119 and 120: `api-platform.md` H2, M1, M2, M3, M5, M6, M8; `api-domain.md` M4 (read-only rule with its architecture test) | Developer gates, smoke, regression |
| 5 | Refactor, `charithm/refactor/web-data-layer` (parallel with 4) | Decision 121: `web.md` H1, H3, H4, M2, M3, M5, M6, M7. Closes BUG-F05-002 and BUG-F06-001, which QA verifies | Developer gates, a late-answer test per hook, smoke, regression |
| 6 | F07 follow-up (decision 098), `charithm/feature/F07-cr-028-visits`, after 4 and 5 | The lead copies CR-028's criteria into the three stage specs and confirms the changed sheets with the owner; then the visit model, states, mobile numbers, note, multi-service availability and screens (decisions 114, 116), the money type, deposit calculator and basis points (118), the read interface and events (119); `api-domain.md` H1 to H3, M2, M5, M6, M8 | Developer gates, QA's cases for every new criterion, targeted re-verification of F07, regression |

After package 6 the lead runs `feature-review` on its diff, then `start-check F12` and `feature-new F12`, then F08.

## Decide when planning, not now

- **F08:** the PayHere sandbox result; the gateway notification route exempt from read-only and suspension
  (decision 120); deposits recorded outside the gateway (PAY-16).
- **F10:** the calendar's drag library with a non-drag alternative, polling or a streamed channel, the forms approach
  for checkout (`web.md`, "Decisions needed before F10"); booking over a booking (decision 123).
- **F10 and F13, for F18:** which records are shared across branches and whether a person works at two
  (`api-domain.md` M7).
- **F13:** schedule history for past utilisation (`api-domain.md` M10).
