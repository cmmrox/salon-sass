---
id: BUG-F01-002
title: Migration-only container cannot start because tenant filter requires web resolver
severity: major
status: verified
found_by: TC-F01-S05-001
breaks: AC-1
found_on: 2026-10-14
fixed_in: c5a18b8
fix_note: Create TenantFilter only for servlet web applications, preserving required resolver/security handling; focused tests, full508-test Maven and actual local,migrate jar startup pass.
developer_note: null
verified_on: 2026-10-14
approved_by: null
found_in_release: null
---

# BUG-F01-002 Migration-only container cannot start

## Steps

Role: local QA operator. Data: synthetic local HTTP stack, explicitly fresh
`salon-stack-cr007-postgres-data`; original retained volume remains untouched.

1. Build `qa-cr007-n` using `infra/scripts/images-build.sh` from tested revision
   `fe53f54cdda31fdb4c222ba63f6a728e4c816a6c` and provide private local stack configuration.
2. Run `infra/scripts/stack-up.sh`.

## Expected

The migration-only process applies current migrations, exits zero, and allows API/web instances
to start. F01-S05 AC-1/AC-3 and NFR-18 require runnable production images and stack startup.

## Actual

Migrations1–10 apply successfully, then application context creation fails because TenantFilter
requires `HandlerExceptionResolver`, absent with web application type NONE. Migration exits1;
stack scripts refuse to start the application instances. Observed once; log gives deterministic
missing-bean startup failure. This is a product defect, not an unavailable QA tool.

## Evidence

`qa/reports/20261014-142945-F01/preflight-migration-startup.log` preserves the actual migration
container output. Final diagnostic: `Field errors in ...TenantFilter required a bean of type
'org.springframework.web.servlet.HandlerExceptionResolver' that could not be found.`

## Re-verification

Independently verified 2026-10-14 on e4e254f4463a274fdfd055df67b3056e83a249f0. Full F01 qa/reports/20261014-232942-F01-2f5e0e96dfa2 passed all 32 registered automation groups. Actual migration-only container startup completed and production metadata/health matched the tested source; TC-F01-S05-001 passed. The original missing-MVC-resolver failure remains preserved.
