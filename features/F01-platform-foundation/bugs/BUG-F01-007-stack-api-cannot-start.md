---
id: BUG-F01-007
title: In the production-like stack every API instance fails to start, because the platform database reads the owning role's setting that instances are never given
severity: major
status: open
found_after_merge: 2026-10-30
found_by: TC-F01-S05-001
breaks: AC-3
found_on: 2026-10-30
fixed_in: null
fix_note: null
developer_note: null
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F01-007 In the production-like stack every API instance fails to start, because the platform database reads the owning role's setting that instances are never given

Found while preparing the owner-requested F01 to F03 regression after F23. The failing check is
F01-S05's production-like stack (`qa/checks/F01/S05-stack.sh`, `S05-deployment.sh`); the cause is
F03-S01 code, and it happens identically on `origin/main`, so F23 did not cause it.

## Steps

Account: none. Data: `.env.stack` with every required setting (QA added the F03 settings that the
file lacked, random and local only) and `SALON_STACK_DB_VOLUME` naming a new, empty volume
(`salon-stack-f23-regression-20261030`), so no retained volume is touched.

1. `infra/scripts/images-build.sh` on the tested tree (7049bf2; product code as 51c15dc).
2. `infra/scripts/stack-up.sh`.
3. For comparison, run `origin/main`'s jar (2301191, built with `./mvnw -DskipTests package` in a
   scratch worktree) in the same image, network and environment as `salon-stack-api-1`.

## Expected

The migrations run once as the owning role, and the two API instances start with the application
role and the platform role only (as `infra/stack/compose.yaml` and
`docs/operations/environments-and-releases.md` say: "Only the migration container receives the owning
role's password"), become ready, and the stack serves alpha (F01-S05 AC-3), so that the F01-S05
checks and every later deployment can run.

## Actual

The migrations succeed; both API instances stop at start-up and the stack never becomes ready:

```
Caused by: org.springframework.beans.BeanInstantiationException: Failed to instantiate [com.salonplatform.shared.persistence.PlatformDatabase]: Constructor threw exception
Caused by: org.springframework.util.PlaceholderResolutionException: Could not resolve placeholder 'SALON_DB_USER' in value "${SALON_DB_USER}"
	at com.salonplatform.shared.persistence.PlatformDatabase.<init>(PlatformDatabase.java:65)
```

`PlatformDatabase` reads `spring.flyway.user` (`environment.getProperty("spring.flyway.user", "")`),
which `application.yaml` defines as `${SALON_DB_USER}`; the stack deliberately gives that variable only
to the migration container, and the default does not apply to an unresolvable placeholder. `origin/main`'s
jar fails the same way (`com.salonplatform.shared.infrastructure.PlatformDatabase`, before F23's package
move). Every time (2 instances of 2, and the comparison run). Nothing outside `local` can start the API,
so no deployment of F03 or later can succeed; it was not seen earlier because F03's own pass does not
run F01's stack checks.

## Evidence

`.stack/logs/up.log`; `docker logs salon-stack-api-1` (JSON log lines; the cause above); the comparison
run's log in the QA scratch folder. No secret appears in these lines.

## Re-verification

<QA, after a fix.>
