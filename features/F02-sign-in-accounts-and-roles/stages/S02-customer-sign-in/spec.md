# F02-S02 Customer one-time-code sign-in and abuse prevention

## Objective

A customer can prove control of a mobile number and establish a session for one salon. Codes are captured locally through the transactional delivery path, with durable abuse limits and no real messages.

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
| IAM-01 | Customer mobile code sign-in through SMS or WhatsApp; no passwords | full API, local capture; web in S04 |
| IAM-03 | One identity per salon even for the same phone | identity API; profile in S03 |
| IAM-09 | Short-lived access, renewal, devices and revocation | customer API; web in S04 |
| IAM-10 | Number/device/address limits, salon daily ceiling and unusual-volume alerts | full API |
| IAM-11 | Audit who did what and when | customer authentication actions |

## Scope

API customer challenge request/verification, a server-issued opaque device cookie, customer identity creation and reuse, separate customer sessions reusing S01, durable limit counters and alerts, encrypted outbox delivery through controllable SMS/WhatsApp local capture, generated contract/client, developer tests and local hooks.

## Out of scope

Profile/export/deletion (S03), customer web screens (S04), paid provider activation or real message sending, booking verification placement (F07), general notification templates/cost logs (F11), mobile app (F15), deployment and earlier-feature QA.

## Acceptance criteria

- AC-1: A valid normalized mobile and sms/whatsapp request on an active salon returns only challenge id/expiry and a server-issued device cookie, commits one encrypted outbox message and eventually produces the requested code in local capture. The public response reveals neither code nor account existence; invalid mobile/channel/body yields field errors without delivery. (IAM-01, IAM-03)
- AC-2: A correct, unexpired code from the issuing device creates or reuses only that salon's customer and issues five-minute access plus thirty-day absolute refresh credentials. Same number at alpha and beta creates unrelated identities, and staff/customer principals cannot authorize each other's actions. (IAM-01, IAM-03, IAM-09)
- AC-3: Codes expire after five minutes, permit at most five failed guesses, are invalidated by resend and consume once atomically. Wrong, expired, resent, consumed, wrong-device or other-salon challenges issue no session; simultaneous verification of one valid code yields at most one successful session/customer creation. (IAM-01, IAM-03)
- AC-4: Number, server-issued device and trusted remote-address fifteen-minute limits plus the UTC daily salon ceiling hold under concurrent requests and across API restart. Excess returns 429 and Retry-After before enqueueing, and client body/headers/cookies cannot reset trusted address or bypass the number/salon ceilings. (IAM-10)
- AC-5: Reaching 80% of a salon's code-request ceiling records one secret-free unusual-volume alert per UTC day and increments a tenant-tagged meter; denied requests do not enqueue more messages, and crossing midnight resets only the daily window. Owner can see the alert through audit while another salon cannot. (IAM-10, IAM-11)
- AC-6: Customer session listing/revocation/renewal follows S01's one-owner and replay rules, never includes staff sessions or another customer's, and access stops on expiry or revocation. Renewal uses the selected salon hostname; signed customer access can resolve bare-API tenant only after verification. (IAM-03, IAM-09)
- AC-7: Challenge/code/recipient material is encrypted or irreversibly hashed at rest, refresh credentials are hash-only, all new records have forced RLS/Hibernate isolation, missing context sees nothing, and neither logs/audit/public errors nor generated DTOs leak code/full phone/credentials. (IAM-01, IAM-03, IAM-10, IAM-11)
- AC-8: A failed or silent local SMS/WhatsApp provider leaves delivery retryable without a second accepted challenge request, expired codes are not delivered, and the product recovers when the provider returns. Provider capture/control/reset hooks are local-only, loopback-only, tenant-bound and contract-excluded; unconfigured nonlocal delivery returns service-unavailable without pretending to send. (IAM-01, IAM-10)

## Design notes

Follow the identity document. Use transactional outbox, independent committed failure counts and atomic consumption/rate accounting. Obtain client address only from the already-trusted servlet connection; do not add forwarded-header trust. A device cookie is a random server-issued identifier, never a tenant selector. Use the one Clock bean for expiry/window decisions. Local capture is the sender used for all developer/QA checks; configured production adapter choice remains D-07 and is not certified by this local feature.

## API and data changes

Under `/api/v1/identity/customers`: `POST /challenges` takes mobile, channel and deviceLabel; returns challengeId and expiresAt, never the code. `POST /sign-in` takes challengeId and code and the issuing server device cookie; returns customer session credentials in S01's token format. Current principal and sessions reuse S01 endpoints with customer kind enforced. Append customer/challenge/counter migrations, tenant-composite uniqueness and encryption/hash storage. Add registered stable invalid-code/expired/limit/provider error codes with Retry-After for limits. `/local/identity` supplies per-challenge capture, provider failures/delay and synthetic-only reset. The developer records exact public fields, capture wait behavior, cookie names and hook routes at handoff.

## UI and UX

Not applicable for this stage. S04 supplies code request, verification and resend screens. API errors identify invalid fields and retry time without revealing identity existence.

## Risks and test focus

API contract, security and privacy, tenant isolation on every endpoint/record, customer/staff principal separation, code-consumption/customer-create/rate-limit concurrency, durable limits across restarts, data-integrity and provider/database resilience. Check revoked/expired/forged sessions and retry delivery using the local clock/capture.

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
