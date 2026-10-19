---
id: BUG-F03-001
title: The generated contract forbids the nulls the operator, billing and entitlement APIs return and documents 200 for the support routes' 201 and 204
severity: major
status: open
found_by: TC-F03-S01-041
breaks: AC-14
found_on: 2026-10-19
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F03-001 The generated contract forbids the nulls the operator, billing and entitlement APIs return and documents 200 for the support routes' 201 and 204

## Steps

Account: a synthetic `OPERATOR_ADMIN` from `POST /local/operator/seed` (random credentials, not
recorded). Data: none beyond the committed `docs/api/openapi.json` and the running API (build
1af62a2, product source identical to 8ef1649).

1. `POST /api/v1/operator/sign-in` on `console.localhost` with a correct password: the 200 body has
   `"credentials": null` (no session before MFA, S01 AC-1).
2. Validate that body against `#/components/schemas/OperatorSignIn` with a JSON Schema 2020-12
   validator (OpenAPI 3.1 is JSON Schema 2020-12), without changing the document.
3. Read the documented success statuses of the S04 routes in the same document.

## Expected

F03-S01 spec, "API and data changes": "Generate OpenAPI/client including nullability, 201/204
statuses and errors"; legacy salons show `plan: null` ("plan is `{id,key,name}` or null for a
legacy unassigned salon"), and sign-in returns no credentials before MFA. F03-S02 makes the
legacy entitlements and the banner `notice` nullable. The S04 API table names 201 for starting a
session and for a fresh entry code and 204 for every ending. A client generated from the contract
must accept what the API returns.

## Actual

Every time. Nullable objects are written as `{"$ref": X, "type": ["object","null"]}`; under
OpenAPI 3.1 both keywords apply, so `null` fails `X`. Nullable enums omit `null` from `enum`.
Affected properties: `OperatorSignIn.credentials`, `SalonDetail.plan`, `SalonSummary.plan`,
`SubscriptionView.plan`, `SubscriptionView.currentPeriod`, `Entitlements.plan`,
`Entitlements.limits`, `Entitlements.features`, `Entitlements.subscription`,
`SubscriptionState.notice`. The generated TypeScript client follows the `$ref` and types them
non-null (for example `credentials: components["schemas"]["OperatorTokens"]` in
`packages/api-contracts/src/generated/schema.ts`), so a web or mobile client is told a legacy
salon always has a plan.
The support routes document `200` where the API answers `201`
(`POST /api/v1/operator/salons/{id}/support-sessions`, `POST /api/v1/operator/support-sessions/{id}/entry-codes`)
and `204` (`DELETE /api/v1/operator/support-sessions/{id}`, `DELETE /api/v1/salon/support-sessions/{id}`,
`DELETE /api/v1/support/session`).

The same class of defect was BUG-F02-001 (audit `before`/`after`); the S01 and S02 contract
tests did not catch it because they compare the document with the generator, not the responses
with the document.

## Evidence

- Validation of a live sign-in body (credentials not printed): the only error is
  `["/credentials","type","must be object"]` (`qa/reports/f03-dev/s01-access.log`, 13 of 14 S01
  access tests failing on `signIn: generated contract shape` before QA isolated the defect).
- `SubscriptionState.notice`: `{"enum":["trial_ending","past_due","read_only"],"type":["string","null"]}`.
- TC-F03-S01-041 in the recorded run (see the run folder in `feature.yaml` `evidence.last_run`,
  entry `F03-S01 operator-runtime`) lists the ten properties and the five statuses.

QA note: so that one contract defect does not hide every other result, F03's own response
checks read these ten properties as nullable (`contractValidator` in `qa/shared/operator.ts`);
they still fail on a missing, extra or wrongly typed field (failure proof in the feature
`cases.md`). TC-F03-S01-041 keeps this defect visible on the raw document.

## Re-verification

