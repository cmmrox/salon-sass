# F02-S03 Customer profile, data export and account deletion

## Objective

An authenticated customer can update their own profile/preferences, download a copy of their personal data and delete their salon account. Each action is scoped to that customer and salon, with immediate credential revocation after deletion.

## Read first

- `docs/architecture/identity-and-access.md`
- `docs/architecture/multi-tenancy.md`
- `docs/architecture/tech-stack.md`
- `docs/architecture/repository-structure.md`
- `docs/engineering/backend.md`
- `docs/operations/environments-and-releases.md`
- `docs/qa/test-plan.md`

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| IAM-03 | Customer identity belongs to one salon | profile and privacy boundaries |
| IAM-04 | Name, verified mobile, optional email/birthday, language, notifications and marketing consent | full API; web in S04 |
| IAM-05 | Customer can delete account and request a data copy | web-compatible API in M1; native UI F15 |
| IAM-11 | Sensitive actions audit who, what and when | profile/export/deletion actions |

## Scope

Own-profile read/update with optimistic version, validation and read-only verified mobile, allowlisted data export, confirmed account deletion, session/challenge/outbox privacy cleanup, secret-free immutable rights audit, generated contracts and developer isolation/negative tests.

## Out of scope

Mobile-number change/reverification, other customers' records, staff customer CRM (F12), booking/payment retention for future records, operator tenant closure (F03), mobile UI (F15), web screens (S04), deployment and earlier-feature QA.

## Acceptance criteria

- AC-1: An authenticated customer can read only their own salon profile, including verified mobile and explicit defaults for language/preferences/marketing; newly verified customers have an empty name pending completion, and staff/other customers/foreign-salon or forged tokens cannot access that profile. (IAM-03, IAM-04)
- AC-2: Updating name, optional email/birthday, supported preferred language, channel preferences and explicit marketing consent persists the normalized values and version. Blank/overlong name, invalid email/future (relative to current salon-local Clock date) or malformed birthday, unsupported language, wrong JSON types or attempted mobile/id/tenant injection changes nothing and reports field errors. (IAM-03, IAM-04)
- AC-3: A stale version cannot overwrite a newer profile; concurrent updates with the same version produce one success and one conflict without losing the successful data. All profile records are isolated at both persistence layers and through pooled concurrent requests. (IAM-03, IAM-04)
- AC-4: Requesting one's data copy returns a no-store JSON download with generated time, own profile/preferences and own device metadata only. It includes no credential/hash/MFA/challenge/code/internal audit or other person/salon data, and the export writes a rights audit entry. (IAM-03, IAM-05, IAM-11)
- AC-5: Confirmed account deletion removes identifying profile, all sessions/refresh history and associated encrypted code/challenge/outbox material in one transaction; invalid confirmation changes nothing. All old access/refresh/device credentials immediately fail, another salon's same-number account stays unchanged, and a new verification creates a new id with no previous profile. Deletion retains hashed anti-abuse counters until their windows expire, cancels queued messages and clears local captures; in-flight delivery cannot capture or resurrect deleted state. (IAM-03, IAM-05)
- AC-6: Profile changes, export and deletion record immutable actor/time/salon/subject actions without stored profile values or identifiers beyond opaque ids. Audit retained after deletion has no deleted phone/name/email/birthday, and every API error remains a safe stable problem response. (IAM-03, IAM-05, IAM-11)

## Design notes

Identity owns authenticated customer profiles in F02, exposed through its facade when F12 needs customer records; do not have another module read identity tables. Follow the identity document's privacy allowlist and deletion semantics. Use optimistic concurrency at update and transaction/locking for deletion versus verification/refresh; clear local captures through an after-commit module event/public seam without importing local hooks into core. Remove encrypted associated outbox payloads through the shared Outbox facade, whose cancellation/finalization guards prevent late provider callbacks recreating rows. Rights do not require staff export permission; they require the customer's own authenticated session. Immediate deletion needs all token/challenge paths to fail closed.

## API and data changes

`GET /api/v1/identity/customers/me` returns id/version/name/mobile/email/birthday/preferredLanguage/notificationPreferences/marketingConsent. `PUT /api/v1/identity/customers/me` accepts mutable fields and version only. `GET /api/v1/identity/customers/me/export` returns JSON with download filename and no-store headers. `DELETE /api/v1/identity/customers/me` requires `{ "confirm": true }` and returns 204 after transactional cleanup. None accepts another customer id or tenant selector. Use S01/S02 entities; append migrations if profile columns/version are missing. Register conflict and confirmation errors; generate public DTOs and client.

## UI and UX

Not applicable for this stage. S04 provides profile, explicit consent, download and irreversible-action confirmation screens.

## Risks and test focus

Security, tenant isolation and ownership per endpoint, stale-version concurrency, export field allowlist and file headers, transactional deletion/reverification races, database resilience, immutable audit and erasure data-integrity. Verify with two salons sharing the same synthetic mobile and with two different customers in one salon.

## Verification commands

```bash
cd apps/api && ./mvnw -q verify
pnpm contracts:check
```

## Done criteria

- The developer checks every acceptance criterion against the running product and records it in handoff.
- Every verification command passes; generated contracts/client are current and committed.
- Lead records actual endpoint/hook/configuration shapes and any documented discrepancies before closure.
- No application secrets, real data, paid sending or unverified completion enters the handoff.
