---
id: CR-003
title: Treat a dependency that accepts connections but does not answer as down
status: approved
requested_by: QA review of F01-S01
requested_on: 2026-10-12
decided_on: 2026-10-12
requirements: []
features: [F01]
stages: [F01-S02]
---

# CR-003 Treat a dependency that accepts connections but does not answer as down

## Request

From `SUG-F01-S01-03`: state in the next spec that "down" includes "accepts connections and does not answer", and add `local-service.sh pause|resume postgres` so that QA can automate the case.

## Why

`BUG-F01-S01-003` showed that health hangs for as long as PostgreSQL accepts connections without answering (over four minutes when the container is paused). The acceptance criterion only said "stopped", so the stage still passed. From F01-S02 every request that reads the database would hang the same way, which is how a real outage usually looks.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | None changed. Makes NFR-15 (a failed dependency degrades one function, not the system) testable |
| Features and stages | F01-S02's spec gains an acceptance criterion. The defect itself was fixed and verified under `BUG-F01-S01-003`, and the pause and resume controls were added then as the test hook QA needed to verify it (commit e5f827c), so what remains is the acceptance criterion |
| Test cases and automation | A resilience case in F01-S02 using pause and resume |
| Data, API and released apps | None |
| Size and delay | Smaller than first proposed: one acceptance criterion and its case. No delay |

## Recommendation

Approve.

## Decision

Approved by the owner on 2026-10-12, as recommended: F01-S02's spec gains the acceptance criterion.
