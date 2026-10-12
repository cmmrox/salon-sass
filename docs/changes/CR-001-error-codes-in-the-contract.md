---
id: CR-001
title: List the error codes in the API contract
status: approved
requested_by: QA review of F01-S01
requested_on: 2026-10-12
decided_on: 2026-10-12
requirements: []
features: [F01]
stages: [F01-S04]
---

# CR-001 List the error codes in the API contract

## Request

From `SUG-F01-S01-01`: declare the general error codes as an enumeration in the OpenAPI document, so that a generated client can learn them from `docs/api/openapi.json`.

## Why

Every client and every test branches on `code`, but the contract types it as a bare string. The web app, and later the customer app, would each keep their own list by hand, and the lists would drift.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None changed. Supports NFR-18 (clients keep working as the API changes) |
| Features and stages | F01-S04 (platform services, `outlined`) already owns the single error format; it would add the enumeration. F01-S01 is not reopened |
| Test cases and automation | One API case in F01-S04 asserting the contract lists every code the API returns |
| Data, API and released apps | Adding an enumeration to a response field is compatible today because no client is released. Later, a new code must be added to the contract before the API returns it |
| Size and delay | Small. No delay |

## Recommendation

Approve, as part of F01-S04.

## Decision

Approved by the owner on 2026-10-12, as recommended: built in F01-S04.
