# F02 Cross-stage journeys and local baseline: draft acceptance cases

Expected outcomes come from the final stage specs, feature requirements and read-first documents. Not executed; no manual result, deferral, evidence or pass recorded. Automation registration waits actual final handoff/selectors/contract.

## Test approach

| Type | Why it applies |
| --- | --- |
| API and security | Authentication, sessions, code proofs, exports and personal data |
| Tenant isolation and permissions | Every new endpoint/record and protected action |
| Concurrency | One-use credentials, last Owner, abuse ceilings, profile versions, tab coordination and deletion races |
| Data integrity and build | Migrations, storage/encryption, generated contracts, translations and stable test handles |
| Resilience | One down/silent case per database, local provider and BFF API dependency |
| Functional, responsive, branding and accessibility | Every new screen, all required widths, three brands, keyboard and automated scan |
| Load | One fixed ten-user local read baseline against the documented target |

Not applied: payment integrity (no money flow), mobile (F15 later). Regression, smoke, staging, real providers and penetration tests are outside the requested F02 scope. API rules are proved once; browser journeys assert cross-stage values. Each critical or count/scrape-based test must be seen failing once for the correct reason before the final pass; that has not happened yet.

Environment: local only, fixed synthetic alpha/beta/gamma. Credentials are random and kept in memory. Authentication uses real password/TOTP/code APIs and local message capture. Restore the application clock after each use.

## Cases

### TC-F02-001 Owner provisioning and removal connects workspace controls to immediate staff access

- **Type:** functional
- **Priority:** critical
- **Covers:** IAM-06, IAM-07, IAM-08, IAM-09, IAM-11
- **Performed as / preconditions:** An alpha Owner has completed web sign-in; new staff uses an independent browser.
- **Steps:** Owner creates a Professional, staff signs in with own scope, Owner grants salon scope to the existing credential, then removes the person; inspect next protected action and Owner audit.
- **Expected:** S01 and S04 carry the same account and live permission effects through controls, staff authority and audit. The journey does not repeat API role matrices.
- **Automation:** draft pending final handoff; not executed, no deferral recorded.

### TC-F02-002 customer sign-in profile download and deletion form one coherent salon journey

- **Type:** functional
- **Priority:** critical
- **Covers:** IAM-01, IAM-03, IAM-04, IAM-05, IAM-09
- **Performed as / preconditions:** A signed-out customer on a small phone at beta; a separate gamma account has the same number.
- **Steps:** Request and verify a code, complete profile/preferences, add another device, download a copy, confirm deletion and inspect the old device and gamma.
- **Expected:** S02, S03 and S04 connect the right customer id/version, brand and data file. Deletion ends beta authority while gamma remains independent and fresh registration has no old profile.
- **Automation:** draft pending final handoff; not executed, no deferral recorded.

### TC-F02-003 staff and customer coexistence survives expiry across separate account journeys

- **Type:** functional
- **Priority:** critical
- **Covers:** IAM-03, IAM-06, IAM-09
- **Performed as / preconditions:** One salon browser has staff and customer families and two tabs per kind.
- **Steps:** Open both account surfaces, advance access expiry, renew through the normal flow and sign out one kind before using the other.
- **Expected:** Correct principals remain in both journeys; renewal and revocation stay kind-specific without session leakage. This is one cross-stage journey, not every combination.
- **Automation:** draft pending final handoff; not executed, no deferral recorded.

### TC-F02-004 bounded local reads meet the documented per-read latency baseline

- **Type:** load
- **Priority:** high
- **Covers:** IAM-03, IAM-07, IAM-09
- **Performed as / preconditions:** Three synthetic salons, five staff roles each, 50 customers and 100 audit entries per salon; ten local concurrent users. Record machine and data volume.
- **Steps:** Run one read scenario for current principal, own devices, permitted staff/audit and own customer profile. Use real API credentials and collect per-read tagged latency and failures.
- **Expected:** Each read has p95 below 300 ms with valid response fields and no foreign data. This is the documented local baseline, not production-scale certification. Password hashing, delivery and mutations are outside its target.
- **Automation:** draft pending final handoff; not executed, no deferral recorded.
