---
id: CR-006
title: Account confirmation, role explanation and authenticator instructions
status: proposed
requested_by: QA review of F02, SUG-F02-01 to SUG-F02-03
requested_on: 2026-10-14
decided_on: null
requirements: [IAM-05, IAM-06, IAM-07, NFR-17]
features: [F02]
stages: [F02-S04]
---

# CR-006 Account flow usability

## Request

"Place and focus confirmation near the action that opened it. Preview the fixed role's
capabilities before an Owner creates or changes staff access. Distinguish authenticator code
rotation from verification-session expiry in the instructions."

## Why

The actual F02 keyboard/visual review found confirmation controls reachable but distant from
Delete on a long phone page, a role dropdown without a capability preview, and shared code-expiry
copy that could better distinguish the five-minute challenge from the authenticator's changing
code. Existing actions, permissions, cancellation and code entry work. These are improvements,
separate from the required bugs filed during verification. The review compares primary Fresha,
Booksy and Vagaro guidance and links the observed screenshots and keyboard records.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | Clarify account-flow presentation under IAM-05/06/07 and NFR-17 after owner approval; no register or current acceptance criterion changes while proposed. |
| Features and stages | F02-S04 is complete. Approval before release would reopen it and F02 under the lifecycle; approval after release requires a new stage. Current F02 closure and merge can proceed on its approved scope. |
| Test cases and automation | Add confirmation focus/scroll/return-focus checks, selected-role explanation checks and distinct authenticator/customer wording checks. Preserve current permission, cancellation, single-use, cookie and tab-race assertions. |
| Data, API and released apps | Presentation and translation changes only; reuse the fixed role matrix. No custom role editing, authentication mechanism change, API migration or personal-data retention change is proposed. |
| Size and delay | Small UI/copy work plus focused developer checks and independent F02 re-verification. Estimate after approval; it does not delay the currently authorized feature delivery. |

## Recommendation

Consider all three improvements together in a later account-flow refinement. Prioritize bringing
confirmation into view, then concise role explanations and authenticator instructions. Keep this
proposed and unimplemented until the owner decides; the current completion request does not
automatically approve QA suggestions.

## Decision

Pending the owner's decision.
