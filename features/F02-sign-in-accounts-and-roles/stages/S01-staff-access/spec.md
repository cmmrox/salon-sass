# F02-S01 Staff sign-in, sessions and role permissions

## Objective

Staff can authenticate, establish or revoke sessions, and administer salon-specific access through
callable APIs. This creates the permission and principal boundary later features use.

## Read first

- `docs/architecture/identity-and-access.md`
- `docs/architecture/multi-tenancy.md`
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/tech-stack.md`
- `docs/architecture/repository-structure.md`
- `docs/engineering/backend.md`
- `docs/operations/environments-and-releases.md`
- `docs/qa/test-plan.md`

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| IAM-06 | Staff password sign-in and optional two-step verification, required for Owners | full API; web in S04 |
| IAM-07 | Defined permissions for five staff roles | full API; business actions use the facade in later features |
| IAM-08 | Professional own scope unless the Owner grants salon-wide visibility | access contract; calendar/customer/revenue enforcement in their owning features |
| IAM-09 | Short access, renewal, devices and immediate removal revocation | staff API; customer reuse in S02, web in S04 |
| IAM-11 | Sensitive actions record who, what and when | identity actions and protected audit API; other modules add actions later |

## Scope

API staff identities, password and TOTP sign-in/enrollment, live scoped principals, permission facade,
sessions/refresh/revocation, staff administration, immutable audit reads, migrations, local fixtures,
independent integration isolation tests and generated contract/client. Extend local/stack secret
configuration without changing running remote services. Read the installed security libraries' docs.

## Out of scope

Customer authentication (S02), customer privacy (S03), web screens/cookies (S04), initial real salon
Owner provisioning/operator support (F03), calendar/revenue/catalogue/payment actions in later
features, MFA recovery/disable, real providers, deployment, regression and smoke automation.

## Acceptance criteria

- AC-1: Valid staff email or normalized mobile/password issues a five-minute signed access credential and seven-day rotating refresh session only for that salon; wrong/unknown/disabled credentials issue neither, return the same 401 outcome, and failed sign-ins count toward durable identifier/address limits. (IAM-06, IAM-09)
- AC-2: An Owner without MFA receives enrollment-only authority after password verification; with MFA receives a verification challenge. A valid TOTP completes sign-in; wrong, expired, consumed or replayed challenges/codes and simultaneous reuse cannot produce another session. Non-Owners can enroll voluntarily; no Owner can authenticate or retain a promoted session without MFA. (IAM-06)
- AC-3: Access requires fixed JWT algorithm/signature/issuer/audience/expiry and a live account/session. Foreign-salon credentials and ids expose no data, raw API native access resolves only a verified signed tenant, reserved hosts remain platform-only, and pooled/concurrent requests keep salon boundaries. (IAM-06, IAM-07, IAM-09)
- AC-4: Only the current principal can list/revoke its sessions, with pagination, current-device identification and bounded device labels. Expiry/revocation/logout takes effect immediately. Refresh rotates once atomically and replay revokes its family; no raw refresh credential is stored. (IAM-09)
- AC-5: The permission matrix and Professional scope of the identity document are returned by the current-principal endpoint and enforced in every use case; each of the five roles has exactly the permitted identity actions, and role/grant changes affect existing credentials immediately. (IAM-07, IAM-08)
- AC-6: Only an Owner creates/changes/removes staff and grants Professional salon-wide visibility; Manager can list staff, other roles cannot. Invalid identifiers/passwords/roles, duplicates, stale versions and foreign ids change nothing; concurrent removal/demotion cannot remove the last active Owner. Removing staff immediately revokes every session, and promotion to Owner requires fresh MFA sign-in. (IAM-06, IAM-07, IAM-08, IAM-09)
- AC-7: Staff can change their own password only with the correct old password and the documented strong-password policy; other sessions stop working, the current credentials rotate, and the old password no longer signs in. (IAM-06, IAM-09)
- AC-8: Identity sign-in attempts, staff changes, MFA/password changes and revocations produce immutable actor/time/salon/subject audit entries without password, token, secret or full phone. Only Owner/Manager can read paginated audit entries, and foreign-salon filters expose nothing. (IAM-11)
- AC-9: Every new record is isolated by forced RLS and Hibernate filtering with composite tenant references, missing context exposes no rows, keys and stored secrets follow the documented hashing/encryption policy, and generated contract/client enumerate stable auth errors and explicit response DTOs. (IAM-06, IAM-07, IAM-09, IAM-11)
- AC-10: Local-only loopback identity fixtures can idempotently seed all roles from supplied synthetic credentials for alpha/beta/gamma, reset only synthetic identity data, and expose no credentials in responses or OpenAPI; startup preserves generated local keys across restart and refuses unconfigured nonlocal secrets. The running local product starts with the existing scripts and recovers from unavailable/silent database with the existing request limits. (IAM-06, IAM-09)

## Design notes

The policies and role matrix in `docs/architecture/identity-and-access.md` constrain the build.
Keep shared free of identity dependencies with an injected signed-tenant verifier seam. Resolve
before opening tenant transactions. Domain rules contain no Spring or HTTP. Commit failed auth
counters/audit independently of an error response. Lock or atomically update one-use session and
MFA state and last-Owner changes. Use F01 clock, audit writer and transaction limits.

## API and data changes

Under `/api/v1/identity`: `POST /staff/sign-in` accepts identifier, password and deviceLabel;
returns authenticated tokens or an MFA challenge (state, challengeId, challengeToken, expiresAt).
`POST /staff/mfa/challenges` starts voluntary enrollment for authenticated staff;
`POST /staff/mfa/enroll` accepts enrollment challengeId/challengeToken and returns secret/otpauth URI;
`POST /staff/mfa/verify` accepts challengeId/challengeToken/code and completes authentication or enrollment.
`GET /me` returns id, kind, role, permissions, scope and MFA state (no secret).
`GET /sessions`, `POST /sessions/refresh` (refreshToken), `DELETE /sessions/{id}`,
`DELETE /sessions` manage one's devices; token responses include sessionId, accessToken,
refreshToken and access/refresh expiry instants. `PUT /staff/password` takes old/new password
and returns rotated credentials. `GET /staff`, `POST /staff`, `PATCH /staff/{id}` (version,
role or Professional grant), `DELETE /staff/{id}` implement administration.
`GET /audit` returns paginated secret-free identity audit DTOs; filters cannot choose a salon.
Register every stable error in ErrorCode and generated OpenAPI. Lists use page/size with bounded size.

Append migrations for staff accounts, sessions/refresh history, MFA challenges/consumed steps and
rate counters as needed. Foreign tenant record ids return 404, same as missing. The developer may
choose internal names and DTO structure beyond the promised fields; list these in the handoff.
Fixtures under `/local/identity` are local-only, synthetic-only, loopback-only and contract-excluded.
A JSON seed hook accepts credentials, never returns them; generated local secret file is ignored.

## UI and UX

Not applicable for this stage. S04 supplies screens. API field errors identify invalid fields.

## Risks and test focus

Security, permissions and tenant isolation for every protected endpoint/record; API contract and
validation; concurrency for refresh/TOTP/last-Owner invariants; data-integrity for migrations and
secret storage; database resilience and durable failed-attempt limits. Include wrong-role, missing
context, expiry, hostile JWT and foreign id tests. Independently prove RLS and Hibernate for new
identity entities using the established disposable-test patterns. QA writes no cases until feature QA.

## Verification commands

```bash
cd apps/api && ./mvnw -q verify
pnpm contracts:check
```

## Done criteria

- The developer checks every acceptance criterion against the running product and records it in handoff.
- Both gates pass; generated OpenAPI/client are committed.
- Lead records actual key settings, synthetic fixture/reset usage and endpoint shapes in project docs.
- Lead reviews any new dependency, gap or document discrepancy before closure.
