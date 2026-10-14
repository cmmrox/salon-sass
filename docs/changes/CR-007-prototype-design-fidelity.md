---
id: CR-007
title: Restore supplied Customer and Salon design fidelity
status: approved
requested_by: owner
requested_on: 2026-10-14
decided_on: 2026-10-14
requirements: [NFR-21]
features: [F01, F02]
stages: [F01-S03, F02-S04]
integration_branch: charithm/feature/F02-prototype-design-fidelity
---

# CR-007 Restore supplied Customer and Salon design fidelity

## Request

The owner requires all current UI to follow the already supplied mobile/customer and salon HTML
design architecture and look and feel before the next feature. New components may be added but
must stick to that design unless the owner requests a change; update skills as needed.

## Why

Existing cool-grey/system-font tokens, square controls, full-width customer brand banner and narrow
staff forms differ from the supplied warm Poppins system, pills, rounded cards, 430px customer
canvas and responsive workspace. Prior functional/accessibility/overflow checks missed fidelity.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | Add NFR-21 and make the supplied HTML visual authority explicit. Identity and security rules remain. |
| Features and stages | Reopen complete unreleased F01-S03 and F02-S04; repair every existing public/customer/workspace/foundation screen through shared components. F03 stays backlog. |
| Cases and automation | Independent F01/F02 verification, source-derived visual assertions and rendered screenshots. F01 name-on-brand expectation becomes neutral name plus accessible branded circular identity fallback. |
| Data, API and released apps | No schema/API/permission/session changes; no native app or fabricated future booking/catalogue/branding capability. |
| Size and delay | Shared visual foundations, customer/workspace shells, current account states and platform errors. Next feature waits for correction and independent QA. |

## Recommendation

Implement now, preserve the supplied HTML and strengthen comparison gates. Confirmation UI adopts
the prototype bottom-sheet/desktop-drawer grammar, which also addresses SUG-F02-01 from CR-006.
CR-006 remains proposed for its independent role-preview/authenticator-copy suggestions; those
are not approved by this visual correction.

## Decision

Approved directly by the owner's 2026-10-14 instruction to fix all UI to the supplied design and
update skills if needed. Local reversible repairs need no renewed confirmation. No deployment,
tag, real message or payment is authorised.

## Delivery history

Original F01 PR #3 and F02 PR #4 are merged. Live check confirmed F02 merge
`b94abb01ea7b16576c9450b911b5c4664e860b32`. This owner-requested cross-feature correction uses one
scoped follow-up repair PR, separate role commits and independent F01/F02 evidence. Original merge
records remain historical; repair revisions and PR are recorded here at completion. F03 is not started.
