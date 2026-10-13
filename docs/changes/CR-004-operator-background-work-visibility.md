---
id: CR-004
title: Operator visibility of failed background work
status: proposed
requested_by: QA review of F01, SUG-F01-01
requested_on: 2026-10-13
decided_on: null
requirements: [PLT-07, NFR-16]
features: [F03]
stages: []
---

# CR-004 Operator visibility of failed background work

## Request

"Give operators an authenticated view of dead messages and backlog when the console is built."

## Why

F01 provides tenant-tagged logs, traces and local meters, while appropriately hiding test hooks
and metrics endpoints outside the local profile. An operator currently needs diagnostic access
to identify failed work. An authenticated summary could distinguish transient outages from work
requiring intervention without exposing message bodies or customer data.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | Clarify NFR-16's operator visibility and alerting, respecting PLT-07's deliberate support-access boundary. No register change until owner approval. |
| Features and stages | F03 is in backlog and has no planned stages. Consider the view during its planning; F01 stays closed on its approved foundation scope. |
| Test cases and automation | Add permission, tenant-isolation, counts, empty/error and support-access cases to the future console stage. No F01 case is retired. |
| Data, API and released apps | Reuse background-work status where possible; a new authenticated summary endpoint may be needed. Never return message bodies or cross-tenant records through salon access. No current migration or client change. |
| Size and delay | A small console view plus API/permission tests; estimate when F03 is planned. Does not delay F01's merge. |

## Recommendation

Consider approval during F03 planning, after defining operator roles and support-access rules.
Retain this as a proposal; no implementation is authorised by the F01 completion request.

## Decision

Pending the owner's decision.
