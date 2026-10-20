# F03-S01 Operator access, salon provisioning and plans

## Objective

An authenticated platform operator can define a plan, create a salon with its first Owner, and
find that salon in the console. The new salon's staff workspace is reachable immediately after
successful creation; operator credentials remain separate from all salon credentials.

## Read first

- `docs/ROADMAP.md`: operator/staff web delivery and deferred customer mobile scope.
- `docs/brd/requirements.yaml`: PLT-01 and PLT-04, including the boundary of partial delivery.
- `docs/architecture/ARCHITECTURE.md`: platform, identity and branding boundaries.
- `docs/architecture/repository-structure.md`: module layout and role ownership.
- `docs/architecture/tech-stack.md`: installed stack, tooling and unresolved commercial decisions.
- `docs/architecture/multi-tenancy.md`: database privileges, hostname rules and forced RLS.
- `docs/architecture/identity-and-access.md`: reusable password/MFA/browser policies and Owner bootstrap.
- `docs/architecture/operator-console.md`: operator permissions, sessions, provisioning and plan policy.
- `docs/engineering/backend.md`: modules, transactions, errors, migrations and contract generation.
- `docs/engineering/web.md`: server data access, routing and bounded upstream waits.
- `docs/design/ui-ux-standards.md`: reference contract, states, accessibility and width matrix.
- `docs/design/prototype/index.html`: final Salon-mode shell, form, list and sheet references.
- `docs/qa/test-types.md`: test types for the later independent feature pass.
- `docs/operations/environments-and-releases.md`: synthetic local data and runtime rebuild discipline.
- `docs/operations/verification-evidence.md`: developer-gate evidence and source/environment identity.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| PLT-01 | An operator can create a salon tenant with business profile, plan, platform subdomain, country, currency, time zone and default language. | Full, with safe initial Owner and routing bootstrap. |
| PLT-04 | Plans define features and limits (staff seats, branches, message allowance, custom domain, dedicated app). Entitlements are enforced by the backend and reflected in every client. | Part: immutable definitions and initial assignment only; enforcement and client entitlement display are F03-S02. |

## Scope

- API: operator password/MFA access, current principal, renewal and sign-out; paginated salon/plan
  lists and details; immutable plan creation; idempotent atomic salon/Owner provisioning.
- Data/infrastructure: least-privilege platform connection, append-only migrations, module-owned
  bootstrap seams, operator security state, platform audit, retry receipts and local fixture hooks.
- Web: hostname-isolated console shell, sign-in/MFA, salon directory/detail/create and plan
  directory/detail/create, through a generated-client server BFF.
- Preserve staff-only web and existing tenant/customer API boundaries. Local synthetic data only.

## Out of scope

- Subscription prices, trials, invoices, payment providers, usage or enforcement: F03-S02. D-06
  remains undecided; synthetic plan names/limits are explicitly fixtures, never approved packages.
- Suspension/reactivation, closure, export and retention: F03-S03.
- Entering or impersonating a salon, support sessions and owner-visible support audit: F03-S04.
- Salon profile/brand editing: F04; guided onboarding: F14; customer UI: F15 after M1 release.
- Operator account administration/recovery screens, plan editing/deletion, reassignment, analytics,
  custom-domain activation and dedicated-app builds. No tags, real messages, payments or deployment.

## Acceptance criteria

- AC-1: On the exact console hostname, an operator with a correct password must complete MFA
  enrollment or verification before obtaining access to the console; incorrect, expired, consumed
  or replayed challenges issue no session. (PLT-01)
- AC-2: Every operator API/BFF/console route on a salon, bare API/platform, other reserved or
  lookalike hostname returns not found before side effects, even with a valid operator credential
  or forged forwarding header; the bare platform status and salon workspace still work. (PLT-01)
- AC-3: Each salon staff role and the customer principal is refused every protected operator
  action; Admin can create/read salons and plans, while Support is denied those actions and can
  access only its own principal/session operations. Denied actions change no business data. (PLT-01, PLT-04)
- AC-4: An operator credential cannot authenticate any tenant identity/business endpoint or open a
  workspace as staff; matching operator/salon email and password values grant no extra authority.
  Two salons retain isolated staff and brand data after provisioning. (PLT-01)
- AC-5: Expired access, revoked sessions and disabled operator accounts cannot authorize a console
  action; valid renewal rotates once, refresh reuse revokes its family, and sign-out prevents any
  further use of that session. Concurrent renewal never leaves two usable refresh families. (PLT-01)
- AC-6: Password attempts for known and unknown operator emails obey the documented email/address
  ceilings across restarts and concurrent requests; excess returns 429 with Retry-After, and failed
  MFA attempts/consumed steps cannot be reset by a failed HTTP transaction. (PLT-01)
- AC-7: An Admin can create and retrieve a plan containing every documented feature/limit; the
  exact values remain unchanged after restart and are available for salon assignment. Missing,
  unknown, wrong-type, negative or above-bound values produce field errors and no plan. (PLT-04)
- AC-8: An Admin can create a salon with the complete documented profile, unique normalized slug,
  an existing plan and first-Owner credentials; after success the list/detail show those nonsecret
  values and the salon hostname resolves the correct name and time zone. (PLT-01, PLT-04)
- AC-9: The provisioned Owner can sign in only at its salon with the supplied password, must enroll
  MFA before receiving a session, and has the existing Owner permissions afterwards; provisioning
  neither returns a password/token nor grants the operator a tenant session. (PLT-01)
- AC-10: Reserved/invalid/boundary-length slugs, unsupported country/currency/language/time zone,
  invalid profile/Owner input, unknown fields or a missing plan are rejected with the documented
  400 field errors or 404, and create no tenant, Owner, brand, assignment or success audit. (PLT-01)
- AC-11: Simultaneous distinct creation keys targeting one slug or plan key create exactly one
  subject, with stable conflict codes for losers and no orphaned records; an existing Alpha/Beta/
  Gamma salon cannot be replaced by this operation. (PLT-01, PLT-04)
- AC-12: Repeating plan/salon creation with the same operator/action/key and normalized input,
  including after a lost response or concurrent submission, returns the original id/result and
  exactly one creation audit; changed input returns idempotency_conflict without modification.
  Unauthenticated callers cannot retrieve receipts. (PLT-01, PLT-04)
- AC-13: A controlled failure during Owner/brand bootstrap leaves no resolvable salon or partial
  profile/plan assignment/Owner/brand/receipt/success audit; retry after removing the failure can
  succeed with the original key. Existing salons remain unchanged. (PLT-01)
- AC-14: Lists paginate with stable ordering, correct totals and documented literal search;
  unknown detail ids return 404, invalid paging/search returns field errors, and results expose
  only allowlisted platform metadata with no tenant business records or credential material. (PLT-01, PLT-04)
- AC-15: As the ordinary tenant database role, direct platform reads/writes and bootstrap-function
  execution fail; as platform runtime, general tenant-table reads/writes fail. Roles owning tables,
  bypassing RLS or lacking explicit configuration cause startup refusal rather than privilege
  fallback. Existing rows and tenant-isolation mechanisms survive the additive migration. (PLT-01)
- AC-16: Operator sign-in/security events and successful plan/salon creation have immutable platform
  audit records with actor, subject, application-clock time and trace id; failed or rolled-back
  creation has no success record, and audit/logs/errors/receipts contain no credential or raw
  personal-field values. (PLT-01, PLT-04)
- AC-17: Every browser mutation rejects missing/foreign Origin and cross-site metadata; operator
  tokens/challenges remain in host-only HttpOnly cookies, outside client JSON/storage/URLs/HTML,
  except the documented transient authenticator setup display. Concurrent tabs and an unsupported
  Web Locks browser follow the documented safe behavior. (PLT-01)
- AC-18: From the console a keyboard user can sign in, define a plan, create a salon and inspect its
  details; lists/forms provide loading, empty, validation, duplicate, unavailable and success states
  at every documented width, with no clipped controls or horizontal scroll and with accessible
  labels, focus/error announcements and the supplied Salon design grammar. (PLT-01, PLT-04)
- AC-19: With a stopped or silent database/API, console operations report unavailable within the
  existing five-second end-to-end outage deadline, create no partial data and recover within five
  seconds of actual dependency availability without restart; retrying an uncertain mutation uses
  its original key. (PLT-01)
- AC-20: Local fixture/bootstrap hooks are loopback-only, excluded from the public contract and
  absent outside local; production startup creates no default operator, while local restart
  preserves explicit fixture identities/keys and unrelated existing salon data. Built web artifacts
  contain neither retained environment files nor any identity/operator key value. (PLT-01)

## Design notes

Follow `docs/architecture/operator-console.md` for policy rather than widening tenant privileges.
Keep platform services in `platform`, with identity/branding public bootstrap facades and shared
transaction participation; no cross-module table access or imports of internals. Use separate
operator state and narrow permission checks inside use cases. Preserve the default tenant
transaction manager, health/meta independence and current identity contracts.

First implement and prove the privilege/atomic-bootstrap seam with PostgreSQL integration tests;
then operator authentication, plan/provisioning APIs and generated contract, then console screens.
This is developer build order within one stage, not independent QA passes. All later feature QA
waits for F03-S01 through F03-S04 to close. Open commercial D-06 does not block this stage's
configurable, unpriced definitions; it must be resolved before commercially dependent S02 behavior.

## API and data changes

All routes below are beneath `/api/v1/operator` and are console-host-only. Auth responses use the
existing credential/challenge structure with operator kind and operator-specific authority.

| Method/path | Request / result |
| --- | --- |
| `POST /sign-in` | `{email,password,deviceLabel}` -> 200 MFA challenge, never a session before MFA |
| `POST /mfa/challenges` | `{challengeId,challengeToken}` -> transient setup for enrollment challenge only |
| `POST /mfa/verify` | `{challengeId,challengeToken,code}` -> 200 credentials after single-use MFA |
| `POST /sessions/refresh` | `{refreshToken}` -> 200 rotated credentials |
| `POST /sessions/sign-out` | Current authenticated session -> 204 and revoked session |
| `GET /me` | `{id,kind,role,permissions,mfaEnabled}`; no tenant identity |
| `GET /plans`, `GET /plans/{id}` | Paginated summaries / immutable definition; `q,page,size` on list |
| `POST /plans` | Idempotency-Key; `{key,name,staffSeats,branches,messageAllowance,customDomain,dedicatedApp}` -> 201 definition |
| `GET /salons`, `GET /salons/{id}` | Paginated summaries / allowlisted profile and assignment; `q,page,size` on list |
| `POST /salons` | Idempotency-Key; `{name,slug,country,currency,timeZone,defaultLanguage,contactEmail,contactPhone,address,planId,owner:{name,email,password}}` -> 201 `{id,slug,hostname,planId,createdAt}` |

Salon detail: `{id,name,slug,hostname,status,country,currency,timeZone,defaultLanguage,contactEmail,
contactPhone,address,plan,ownerEmail,createdAt,updatedAt}`; plan is `{id,key,name}` or null for a
legacy unassigned salon. Plan detail adds id/createdAt/updatedAt to its create fields. Existing
synthetic salons may have null newly introduced profile values/ownerEmail; display them explicitly
as not configured, not fabricated defaults. Reject invalid ids as 400 field errors. No wildcard
operator proxy. BFF `/operator-web/{action}` allowlists the above operations, takes no arbitrary
upstream URL and returns only nonsecret fields; credential responses are consumed server-side.

Use existing problem codes for field/permission/authentication failures; register `slug_taken`,
`plan_key_taken` and `idempotency_conflict` before returning them. A missing/malformed
Idempotency-Key is 400 `bad_request` with the header named in field errors. Requests and
responses are no-store. Generate OpenAPI/client including nullability, 201/204 statuses and errors.

Append migrations for platform users/security state, plans, provisioning metadata/assignment,
receipts and immutable platform audit, extending `tenant` additively where appropriate. Identity
and branding own their bootstrap functions/migrations. Follow existing composite keys/RLS for
tenant rows. Keep immutable plan FK assignments, unique normalized slug/key constraints and bounded
transaction work. No rewrite of existing migrations, role grants or synthetic fixture values.

## UI and UX

No operator screen exists in the prototype. Extend its final Salon-mode `body.adm` header/sidebar,
cards/fields/pill controls, responsive list rows and `.sheet` confirmation/form grammar through
conforming shared components. Neutral theme throughout; no pricing cards, dashboard statistics,
customer navigation or links to undelivered billing/support actions.

| Screen | Route | Reference components and behavior |
| --- | --- | --- |
| Operator sign-in and MFA | `/console/sign-in` | Staff account forms, reference cards/fields and transient authenticator setup |
| Salon directory | `/console/salons` | Salon shell and responsive staff/customer management rows; search, paging, Create salon |
| Salon detail | `/console/salons/{id}` | Read-only profile/plan card and copyable hostname; no automatic workspace entry |
| Plan directory/detail | `/console/plans`, `/console/plans/{id}` | Same neutral list/card structure; explicit feature labels and numerical limits, no prices |
| Create salon / plan | Drawer or sheet from its directory | Desktop right drawer, phone bottom sheet, labels, contained focus, Escape/cancel and focus return |

Build every control with Tailwind CSS and shadcn/ui following the component map in
`docs/engineering/web.md`, "Styling" (CR-016, CR-017): Sidebar for the console navigation, Sheet for
create forms, Field/Input/Input Group/Select in forms, Input OTP for MFA, Item rows, Badge, Empty,
Pagination, Card, Alert, Skeleton and Spinner. The console sets the neutral accent (decision 047).

Only Salons, Plans and Sign out appear for Admin; Support sees a clear access-denied state and
Sign out, not nonfunctional management links. Clear passwords on success/cancel/error requiring
re-entry; retain other safe fields. Keep one idempotency key for an unchanged pending submission;
create a new key for a deliberately new submission. After uncertain failure offer a retry with the
same key and payload; retain sensitive form input only transiently in the active component, never
persist it. Show the submitted hostname, assigned plan and next Owner sign-in action on success.
Use next-intl messages, tokens, data-testid and the full width/focus/contrast matrix. Preserve
visual comparison evidence against the named Salon components and explicitly label absent exact
operator reference screens. These screens do not apply salon branding to the console.

## Risks and test focus

- `api`: public/generated contract, strict input tables, paging, null legacy fields and stable errors.
- `tenant-isolation`: every new salon-returning endpoint, principal/host separation, bootstrap grants,
  pooled connection/context restoration and existing Alpha/Beta data; never trim this coverage.
- `permissions`: Admin/Support plus every staff role/customer against protected actions; no state
  change on denial. Operator authority must never imply tenant access.
- `security`: MFA replay, persistent rate limits, refresh family races, CSRF, host/cookie/assertion
  binding, bootstrap exposure, no secrets in runtime/artifacts and safe failed-auth audit commits.
- `concurrency`: one slug/key, same receipt key and MFA/refresh single-use invariants; one case per
  invariant rather than many race sizes.
- `data-integrity`: additive migration with existing salons, complete rollback at the bootstrap
  seam, exact immutable plan values and assignment, no orphaned rows or duplicate success audits.
- `resilience`: stopped/silent database and API with recovery; uncertain writes retry the original key.
- `functional`, `responsive`, `accessibility`: full console journey, screen states, keyboard and every
  width; compare against the final reference components, not customer-mode/mobile layouts.
- `build`: absence of all secret keys/environment files from standalone artifacts, covered by
  developer tests run by the listed gates. No payment/load/mobile/real-provider or local TLS scope.

## Verification commands

The developer gates run from the repository root. Record evidence before handoff; stop/restart
local runtime artifacts as the environment document requires. These gates are not feature QA.

```bash
cd apps/api && ./mvnw -q verify
pnpm -r lint
pnpm -r typecheck
pnpm -r test
pnpm --filter @salon/web build
pnpm contracts:check
```

## Done criteria

- The developer has checked every acceptance criterion on the running product and recorded it in
  the handoff, with synthetic data identity, runtime commands and evidence paths; no private values.
- All verification commands pass and the privilege/atomic rollback seam is demonstrated on real
  PostgreSQL, with the normal tenant connection left unchanged.
- The console journey and reference-component visual comparison are recorded at the width matrix,
  including keyboard, long values, empty/error states and both operator roles.
- The lead updates the environment/identity/web documents with actual operator variables, new
  role/pool provisioning, bootstrap/hook commands, routes and secret-handling behavior from handoff.
- No commercial pricing/policy decision, tenant impersonation or customer web capability is inferred.
- Stage closure is on developer gates; independent QA verifies the whole F03 once all four stages
  close. Cases, QA automation and pass evidence are not produced by this planning stage.
