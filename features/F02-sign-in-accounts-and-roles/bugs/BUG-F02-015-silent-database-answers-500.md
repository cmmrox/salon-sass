---
id: BUG-F02-015
title: When the database goes silent, in-flight identity requests answer 500 internal_error instead of 503 service_unavailable
severity: major
status: open
found_after_merge: 2026-10-30
found_by: TC-F02-S01-029
breaks: AC-10
found_on: 2026-10-30
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F02-015 When the database goes silent, in-flight identity requests answer 500 internal_error instead of 503 service_unavailable

Found by the owner-requested F01 to F03 regression after the modular-architecture refactor (run
`qa/reports/20261030-073318-regression-a908c388c8aa`, entry F02-S01 staff-runtime). It is new in the
refactor: origin/main (2301191) answers 503 every time under the same test.

## Steps

Account: two synthetic staff, one of alpha and one of beta (the shared identity fixture). Data: a fresh
local database.

1. As the owning role, `LOCK TABLE identity_staff IN ACCESS EXCLUSIVE MODE` in an open transaction.
2. Send `GET /api/v1/identity/me` for each staff member; wait until both wait on the lock.
3. `infra/scripts/local-service.sh pause postgres` (the database accepts nothing and answers nothing).
4. Read both answers; resume PostgreSQL and roll the lock back.

(`qa/api/F02/S01-runtime.spec.ts`, TC-F02-S01-029, does exactly this.)

## Expected

F02-S01 AC-10 and `docs/engineering/backend.md` (API conventions): each in-flight request is answered
503 `service_unavailable` within five seconds (the 3 s database request limit), and the API recovers.

## Actual

Both requests answer 500 `internal_error` after about 3.1 to 3.4 s; recovery afterwards is fine. 3 of 3
runs on HEAD (7049bf2, product as 51c15dc) on a fresh database; 3 of 3 runs on origin/main's jar on its own
fresh database answer 503 and pass.

The API log shows why: the query on `identity_staff` through Spring Data hits the socket limit
(`JDBCConnectionException` <- `PSQLException` SQLState 08006 <- `SocketTimeoutException: Read timed out`,
which `DatabaseUnavailable` recognises), but the transaction's rollback then fails on the broken
connection, and that exception replaces the original one:

```
ERROR com.salonplatform.shared.web.ApiExceptionHandler | Unhandled exception
  org.springframework.orm.jpa.JpaSystemException: Unable to rollback against JDBC Connection
  Caused by: org.hibernate.TransactionException: Unable to rollback against JDBC Connection
  Caused by: java.sql.SQLException: Connection is closed
GET /api/v1/identity/me 500 3416 ms
```

The rollback exception's cause chain carries no database-unavailable cause, so it is answered 500. On
origin/main the same request (then on the EntityManager in `IdentityWork`) was answered 503. Stack:
`SessionAccess.owner` (118) <- `SessionAccess.auth` <- `SessionAccess.me` <- `IdentityWork.run` <-
`StaffAccessController.me`.

## Evidence

Local, uncommitted: `qa/reports/20261030-diag-silent-db/` (head-run1..3.log: 500; main-run1..3.log: pass;
head-api-run3.log: the API log above). No secret in them.

## Re-verification

<QA, after a fix.>
