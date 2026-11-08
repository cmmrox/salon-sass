---
id: BUG-F03-008
title: A staff seat request that waits behind a plan move reads "no plan" and is not limited, leaving the salon over its new plan
severity: major
status: verified
found_after_merge: 2026-11-02
found_by: TC-F06-S01-034
breaks: "PLT-04: entitlements are enforced by the backend. F03-S02 AC-10 (a staff member beyond the plan's seat limit is 409 entitlement_exceeded) and AC-11 (usage above the new plan's limits refuses the move); F06-S01 AC-6"
found_on: 2026-11-02
fixed_in: 144a47d
fix_note: "Migration V29 rewrites salon_entitlements(): the lock and the plan read are now two statements of a VOLATILE function, with signature and grants unchanged, so a request that waited behind a move reads the plan the move committed. V14 took the lock and read the plan in one statement, FOR SHARE OF t over a LEFT JOIN of plan; after the wait PostgreSQL rechecks only the locked row, so the join no longer matched and the plan came back NULL, which the caller read as a salon without a plan."
developer_note: null
verified_on: 2026-11-09
approved_by: null
found_in_release: null
---

# BUG-F03-008 A staff seat request that waits behind a plan move reads "no plan" and is not limited, leaving the salon over its new plan

Found while F06-S01 was built, by the developer on the running product, and filed by QA as the lead
asked. The hole has been there since F03-S02 for creating a staff account. F06-S01 adds a second way
in, adding a team member with no sign-in. The fix is on the F06 feature branch and ships with F06's PR,
so this stays `fixed`, not `verified`, until `feature-verify F06`. F03's statuses stay as they are.

## Steps

Account: an operator Admin and the salon's Owner. Data: a salon the operator creates now on a plan of 6
seats, using 2 (the Owner and a team member with no sign-in), and a second plan of 2 seats.

1. In one tick, send the operator's plan move to the 2-seat plan
   (`PUT /api/v1/operator/salons/{id}/plan` with the salon's current version) and, on the salon's host, a
   seat request: `POST /api/v1/identity/staff` or `POST /api/v1/team/members`.
2. Read `GET /api/v1/salon/entitlements`.

## Expected

The two cannot both succeed. Either the move commits first and the seat request is 409
`entitlement_exceeded` naming `staffSeats`, or the seat request commits first and the move is refused
with 409 `entitlement_exceeded`. Seats used never exceed the limit of the plan the salon ends on.

## Actual

Both succeed. The move answers 200, the seat request 201, and the salon uses 3 seats on a plan of 2.

- From the developer, on the running product: over the limit in 7 of 12 rounds, with a caller waiting
  behind a move getting `plan_id` NULL; after the fix, 0 of 12 on both paths.
- From QA, on a build of 9c4ce9a, the commit before the fix, by `TC-F06-S01-034`: 13 of 13 runs failed
  (7 with the prepared case, 6 with the case as repaired). In the 6 repaired runs, which race each path
  on its own, 4 failed on a new account and 2 on a new person.

## Evidence

Run on a throwaway database (`qa/api/F06/S01-seats-and-races.spec.ts`, case 034), build 9c4ce9a:

```
Error: a new account, 0 ms: seats used fit the plan the salon ended on (move 200, account 201)
Error: a new person, 0 ms: seats used fit the plan the salon ended on (move 200, person 201)
Error: a new person, 1 ms: seats used fit the plan the salon ended on (move 200, person 201)
Expected: <= 2
Received:    3
```

The same case on c9b2d57, which holds the fix, passes: 30 rounds a run, of a new person, a new account and
both, each at ten gaps, repeated five times.

## Re-verification

Verified at `feature-verify F06` on 2026-11-09. `TC-F06-S01-034` ran in the recorded feature runs on the
QA instance, built from the F06 branch with V29 applied (application source as at ed7d126):

- `qa/reports/20261109-030629-F06-cdaf2ecaa2d5` (tested revision 108210b): passed;
- `qa/reports/20261109-031920-F06-ca7f75ae348c` (tested revision a27b497, the full pass): passed.

Each run races the operator's move to a plan of exactly the seats used against a new person, a new
account and both, at a sweep of gaps in both orders, and asserts that seats used never exceed the plan
the salon ends on, with every refusal 409 `entitlement_exceeded`. The same case failed 13 of 13 times
on the build before the fix (above), so it would catch the defect again. Only F06's automation ran; F03's
own cases were not run, as the scope of this pass is F06.
