# F01 test cases

Environment: local only; the synthetic alpha, beta and gamma salons of docs/qa/test-plan.md. No user accounts exist until F02.

## Test approach

| Type | Why it applies |
| --- | --- |
| build | Startup, contract drift, images, scanning and workflow gates. |
| api | Salon contracts, problem responses, time hooks and probes. |
| tenant-isolation | Every salon endpoint, tenant table, rendered shell and background delivery must stay inside its salon. |
| security | Forged hosts and headers, immutable audit, least privilege, TLS, secret and log exposure. |
| functional, branding, responsive, accessibility | Public shell and neutral states at the two widths expressly required by S01/S03. |
| data-integrity | Migrations, seed, audit/outbox transaction, UTC and DST boundaries. |
| resilience, concurrency | Database/API/provider outages, outbox leases, simultaneous requests and deployment under traffic. |
| load | Existing S02 read baseline against NFR-03; local measurement only. |

Not applied: permissions for end-user roles, payment-integrity and mobile: F01 has no authenticated action, money or mobile surface. Database permissions are exercised through tenant-isolation/security cases. No remote load, penetration test, real messages or payments.

S01/S02 cases are reused. Their superseded brand/error/network/migration expectations follow approved S03 AC-3, S04 AC-1/AC-4 and decision 032; isolation assertions stay exact. S03/S04/S05 add one case per criterion; negative values are table rows, and extra cases exist only to protect data or retained work.

Across-stage restart and deployment risks are exercised by S04 AC-11 and S05 AC-8/AC-9 rather than repeated in a second journey.

## Cases

### TC-F01-001 Salon identity stays bound across shell API audit delivery and cleanup

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** BRD-03, BKG-15
- **Performed as:** QA operator on synthetic local data; public caller on each salon hostname.
- **Preconditions:** Final integrated F01 tree is committed; local product is running and all salons are active. Stack cases also require the local HTTP production-like stack seeded; deployment cases require versions N and N+1 from this tested tree.
- **Steps:**
  1. For each salon open web, read brand, create probe; await delivery and verify trace/tenant; try each foreign probe; advance cleanup and revisit web.
- **Expected:** Only own brand/probe/audit/logs; foreign ids 404; all expired work removed by each salon job; the signed-out entry (staff sign-in, CR-012) still names that salon.
- **Automation:** `qa/web/F01/journey-salon-isolation.spec.ts`
### TC-F01-002 First real staging deployment serves salon subdomains with public TLS

- **Type:** security
- **Priority:** high
- **Covers:** BRD-03
- **Performed as:** Operator and QA, on synthetic staging salons only, after an explicit owner deployment request.
- **Preconditions:** Owner has decided hosting D-08 and domain D-02, configured the DNS challenge module and credentials, provided host access and explicitly requested staging deployment.
- **Steps:**
  1. Start and seed the requested real staging stack using the committed container deployment scripts.
  2. Open each synthetic salon's subdomain, validate public TLS and identity, exercise manual delivery under traffic and inspect provider-dependent monitoring.
- **Expected:** Valid public certificate for every salon, safe tenant identity and deployment without lost responses; monitoring is recorded for the chosen provider.
- **Automation:** deferred; first real-host deployment is outside local F01 proof, explicitly deferred by decision 018; unblocked by hosting, domain, DNS module/credentials, host access and owner deployment request. Public certificate, remote delivery and provider-dependent monitoring are not certified.
