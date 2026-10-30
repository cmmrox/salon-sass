---
id: BUG-F03-007
title: The documented offline operator bootstrap command stops with "RESOLVED_JAVA_HOME unbound variable" and creates no operator
severity: minor
status: verified
found_after_merge: 2026-10-30
found_by: exploratory
breaks: "docs/architecture/operator-console.md, Operator authentication: bootstrap is an explicit offline administrative command (F03-S01 handoff.run)"
found_on: 2026-10-30
fixed_in: b6c84f9
fix_note: "operator-bootstrap.sh asks require_prerequisites for postgres and api, so the api prerequisite resolves RESOLVED_JAVA_HOME (or reports the missing JDK 25 as local-service.sh does); infra/scripts/test-script-prerequisites.sh holds every Java-running script to it."
developer_note: "Checked on a throwaway database (salon_bugfix_f03_007, dropped): before, exit 1 'RESOLVED_JAVA_HOME: unbound variable'; after, the account is created (exit 0), a second run leaves it unchanged (exit 0), and JAVA25_HOME=/nonexistent stops at the prerequisite check naming JDK 25 (exit 1). The new test failed 4 checks before the fix and passes 5 after. Gates at 0dbe7d2 (complete logs in /Users/cmmrox/Personal/Projects/salon-sass-bugfix/build/regression-bugfix-evidence/gates, summary.txt): ./mvnw -q verify exit 0 (864 tests; three earlier runs failed only OperatorAccessIntegrationTest.simultaneousAttemptsOnOneEmailCountEveryAttempt under load averages 10-40, which passes alone and with the new tests run first, and base fbab6d0 passed at the same time), pnpm -r lint/typecheck/test exit 0 (web 700/700 on retry after load timeouts), web build, format:check, knip, contracts:check, lint-workflows, images-build, images-scan, test-edge-health-tolerance, test-script-prerequisites, sdlc selftest exit 0."
verified_on: 2026-10-30
approved_by: null
found_in_release: null
---

# BUG-F03-007 The documented offline operator bootstrap command stops with "RESOLVED_JAVA_HOME unbound variable" and creates no operator

Found while preparing the F23 feature pass (reported unverified in the F23-S06 handoff; reproduced
independently by QA). The script dates from F03-S01 (commit 4364751) and is identical on
`origin/main`, so it is a defect of the delivered F03, not of F23.

## Steps

Account: none yet; a synthetic `OPERATOR_ADMIN` properties file (`email=f23qa-bootstrap@example.invalid`,
`displayName`, `role`, a random password), mode 0600, in a scratch folder. Data: a fresh database
`salon_f23_qa` in `salon-postgres` with PostgreSQL running and `apps/api/target/salon-api.jar` built.
`RESOLVED_JAVA_HOME` not set in the shell (the normal case: nothing documents setting it).

1. `SALON_DB_NAME=salon_f23_qa infra/scripts/operator-bootstrap.sh <file>`

## Expected

The operator account is created (or the script reports that the email already has one), as
`docs/architecture/operator-console.md` and the F03-S01 handoff describe the offline bootstrap.
If Java 25 cannot be found, the script says so the way `local-service.sh` does.

## Actual

Exit 1:

```
infra/scripts/operator-bootstrap.sh: line 27: RESOLVED_JAVA_HOME: unbound variable
error: the operator bootstrap failed. See .local/logs/operator-bootstrap.log
```

`select count(*) from operator_user` stays 0. Every time (1 of 1, and the same in the F23-S06
developer's walk-through). Cause, as seen in the script: it calls `require_prerequisites` with no
service names, so `resolve_java_home` (run only for `api`) never sets `RESOLVED_JAVA_HOME`, and the
`set -u` script then reads it (line 27; line 24 too when the jar has to be built). Workaround:
export `RESOLVED_JAVA_HOME` to a Java 25 home by hand, which no document mentions.

## Evidence

Run log kept in the local, uncommitted `qa/reports/20261030-F23-preflight/operator-bootstrap-run1.log`
(no credential in it): the two lines above. The account file was
random, synthetic and deleted afterwards; the fresh database is dropped at the end of the pass.

## Re-verification

QA, 2026-10-30, on d9c8379 (fresh database salon_qa_d9c8379, RESOLVED_JAVA_HOME unset): `operator-bootstrap.sh` with a synthetic
`OPERATOR_ADMIN` file exits 0 and prints "Operator bootstrap: the account was created; it enrolls MFA at its first sign-in";
`operator_user` holds the account (1 row). A second run exits 0, "an account with that email already exists and was left
unchanged". The account file was random and deleted. Verified.
