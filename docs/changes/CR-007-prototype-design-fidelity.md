---
id: CR-007
title: Restore supplied Customer and Salon design fidelity
status: approved
requested_by: owner
requested_on: 2026-10-14
decided_on: 2026-10-14
requirements: [NFR-21]
features: [F01, F02]
stages: [F01-S03, F01-S05, F02-S04]
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

## Local verification support

Independent QA found the retained F01-only stack database contains its old V8 rollout probe,
while F02 correctly uses V8 for staff identity. Preserve the historical volume and archived
credentials. The supporting F01-S05 configuration fix adds optional `SALON_STACK_DB_VOLUME`,
defaulting to the existing volume name; QA explicitly selects a separate synthetic local volume.
The developer proves default/empty/custom resolution changes only that volume and keeps shell
overrides scrubbed. QA's rollout fixture chooses the next migration after the current real jar
and preserves all original entries. No old volume reset, hidden Docker command wrapper, schema
change, staging connection or deployment is involved. Final independent QA includes these changes.

Developer and QA source commits initially share the repair integration checkout sequentially,
with explicit role-separated paths and guards. Final independent QA uses
`charithm/test/F02-prototype-design-fidelity`, branched from the finished integration revision;
the lead integrates its result commits with a merge commit. Existing history is preserved.

## Independent QA findings

The initial F01 run on `fe53f54` is preserved as failing/interrupted, never passed. Independent
QA filed major BUG-F01-002 (migration-only startup incorrectly instantiates the servlet tenant
filter) and BUG-F01-003 (4.4669:1 success text contrast). Fix both before certification. The
contrast correction uses existing ink on the reference green surface under the existing AA
requirement; all palette and accessibility assertions remain. A non-servlet migration process
must not create servlet filters, while servlet tenant isolation and error mapping remain intact.
The fixed source receives complete independent F01 and F02 verification.

## Historical verification and current fixture compatibility

The original F01 and F02 full passes and merges remain valid history for their tested revisions
and scopes. The current repair changes shared UI and receives new verification; it does not
undo those original deliveries. Initial current failures include genuine product defects
(BUG-F01-002/003, fixed) and obsolete F01 test assumptions introduced before F02 existed.

QA supplies explicit private independent keys for positive nonlocal profile checks, while keeping
negative missing-secret checks unconfigured. The source scanner distinguishes rendered values
from comparison operands and recognizes the verified shared Button `testId` contract; negative
hardcoded-text and missing-handle witnesses remain. F01-S02 AC-3 is clarified to the already
approved F02 identity contract: invalid bearer UUIDs return generic 401 `invalid_session` with
no tenant leakage; non-credential tenant selectors still return the hostname's own salon. No
authentication business rule, isolation requirement or accessibility threshold is relaxed.
These QA fixes are batched before the final frozen-revision feature passes.
