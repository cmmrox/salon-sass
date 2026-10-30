---
id: BUG-F02-015
title: When the database goes silent, in-flight identity requests answer 500 internal_error instead of 503 service_unavailable
severity: major
status: verified
found_after_merge: 2026-10-30
found_by: TC-F02-S01-029
breaks: AC-10
found_on: 2026-10-30
fixed_in: 94bbee9
fix_note: "Both transaction managers (tenant JPA and platform JDBC) now answer 503 when a commit or rollback fails on a connection already closed: they throw TransactionSystemException caused by DataAccessResourceFailureException, replacing whatever the use case concluded from what it read (kept as its application exception, logged); failures on a live connection and genuine 404s are unchanged."
developer_note: "Cause (Hibernate 7.4.5 bytecode and a probe): SessionImpl.find returns null for a JDBCConnectionException when the transaction is already rollback-only (HHH-12472). The refactor's SessionAccess.owner uses Spring Data findById (em.find), so the lost connection read as no account, the use case answered 401, IdentityWorkImpl committed as it does for 401, and the commit's rollback failed on the closed connection in doCommit, which the old doRollback guard never covered: 500. origin/main used JPQL, which throws, so it reached the guarded rollback. The platform connection's DataSourceTransactionManager had no guard, so its failed rollback replaced the database failure with a TransactionSystemException (500) for any platform/operator request; fixed under the same rule. Tests: IdentityDatabaseSilentIntegrationTest (QA's steps: lock identity_staff, /me in flight, pause; 503 within 5 s, then 200) and TransactionOnLostConnectionIntegrationTest (tenant lookup returning normally, lookup turned into 404, failing tenant work, failing platform work, and a genuine 404 on a live connection in both); 4 of 6 failed before the fix. Rule set by the lead: once a transaction's connection is lost, the request answers 503, whatever the use case concluded. Gates on the combined tree at 94bbee9 (logs in /Users/cmmrox/Personal/Projects/salon-sass-bugfix/build/regression-bugfix-evidence/f02-015/gates): cd apps/api && ./mvnw -q verify exit 0, 928 tests, 0 failures, 475 s at load average 7-9; pnpm contracts:check exit 0."
verified_on: 2026-10-30
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

QA, 2026-10-30, on d9c8379 (fresh database salon_qa_d9c8379): TC-F02-S01-029 alone, 3 runs, all pass: both in-flight
requests answer 503 `service_unavailable` in 3.19 to 3.47 s each time (6 of 6), and the API recovers (200 afterwards).
The API log shows `GET /api/v1/identity/me` 503 for every request during the outages and no 500. Verified.
