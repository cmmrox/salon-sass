# F03-S04 Audited support sessions

## Objective

A support operator can see a salon's workspace as its Owner sees it, without its password, through
a session with a recorded reason and an expiry. The salon's Owner sees each session and everything
done in it in the audit log, and can end it.

## Read first

- `docs/brd/requirements.yaml`: PLT-07, IAM-11 and NFR-20.
- `docs/architecture/operator-console.md`: "Support sessions (F03-S04)" and the S01 rules on
  operator authority, hostname, cookies and audit.
- `docs/architecture/multi-tenancy.md`: platform data and operator access, two-tenant tests.
- `docs/architecture/identity-and-access.md`: staff permissions, workspace sessions, BFF cookies,
  Origin checks and the audit list.
- `docs/engineering/backend.md` and `docs/engineering/web.md` (including "Styling", CR-016, CR-017).
- `docs/design/ui-ux-standards.md` and `docs/design/prototype/index.html`: workspace shell, Alert,
  Item rows and sheets.
- `docs/decisions/DECISION_LOG.md`: decision 049.
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| PLT-07 | Operator support staff can enter a salon's workspace only through a time-limited session with a recorded reason. The session is visible to the salon owner in the audit log. | Full |
| NFR-20 | Support staff can reproduce what a salon or customer sees without asking for passwords. | Part: the salon workspace; the customer app view waits for F15. |

## Scope

- API (operator): start, end, list and revoke support sessions; mint a single-use entry code.
- API (salon): exchange an entry code for a support credential; support principal on the existing
  staff read endpoints; refusal of every mutation; per-request audit; Owner's list and revocation of
  the salon's support sessions.
- Web: console Start support session sheet and session list; workspace support entry page,
  persistent support banner with End session, Support access page for the Owner; support entries
  in the existing audit list.

S04 grants Support `operator.salon.read`; this supersedes S01 AC-3's denial of salon reads to
Support, which holds only until S04 is built. Feature QA tests the final behaviour.

## Out of scope

- Acting as the salon (any mutation) during support; seeing the customer app (F15).
- Operator account administration; platform audit browsing beyond support sessions.

## Acceptance criteria

- AC-1: A Support operator starts a session for an active, past-due or read-only salon with a reason
  of 10–500 characters and a duration of 5–120 minutes (default 30); out-of-range values are field
  errors, a suspended or closed salon is 409 `invalid_state`, and a second active session by the same operator
  for the same salon is 409 `support_session_active`. Admin cannot start one. (PLT-07)
- AC-2: The entry code opens the salon's workspace in the support view once, within 60 seconds; a
  reused, expired, altered or other-salon code is 401 `invalid_code`, a missing or foreign Origin is
  refused as for every BFF mutation, and a code presented on another host is 404; none issues anything. The code never appears in server logs or a request URL. (PLT-07, NFR-20)
- AC-3: In the support view the operator sees the workspace pages and data the Owner can read,
  with a persistent banner naming the session's end time; every mutation through the API or the UI
  answers 403 `support_read_only` and changes nothing, and no password, MFA material or token is
  shown. (PLT-07, NFR-20)
- AC-4: The support credential is refused by operator endpoints, customer endpoints, every other
  salon and the console; a salon staff credential cannot start or use support sessions, and an
  operator credential alone cannot open any workspace. (PLT-07)
- AC-5: At expiry, or when the operator ends the session, an Admin revokes it in the console or the
  Owner revokes it in the workspace, the next support request fails with 401 `invalid_session` and the workspace
  leaves the support view; an ended session cannot be renewed or reopened with its code. (PLT-07)
- AC-6: The salon's audit list shows the session start (operator display name, reason, expiry),
  every API request under it (method, route template, outcome, time) and its end with how it ended,
  to the Owner and Manager and no other role, and never in another salon; no operator email or
  secret appears. (PLT-07)
- AC-7: The Owner's Support access page lists active and past sessions with reason, operator name,
  start, end and state, and revokes an active one; other roles get 403. (PLT-07)
- AC-8: Support operators can find salons in the console directory and detail; Admin sees every
  support session with its salon, operator, reason and state and can revoke active ones; the
  platform audit records start, end and revocation. (PLT-07)
- AC-9: Two operators' simultaneous sessions on one salon, and one operator's sessions on two
  salons, stay separate: each credential sees only its salon, and ending one leaves the other. (PLT-07)
- AC-10: The console sheet and list, the workspace entry page, banner and Support access page provide
  loading, validation, refused, expired, unavailable and success states at every documented width,
  keyboard-only use, accessible labels, and the support banner is announced and never hidden. (PLT-07)

Refusals not named above: Support ending, or requesting a code for, another operator's session is
403 `permission_denied`; a new entry code for an ended session is 409 `invalid_state`; any support
credential on a salon that becomes suspended gets 403 `salon_suspended` like every salon request,
and on a closed salon 404. The banner shows the end time in the salon's time zone, as `HH:MM`, like
every workspace time. Audit action names are those recorded in the stage handoffs.

## Design notes

Support grants and entry codes are platform records; the support credential is minted by platform
and verified by the tenant filter like a staff credential, then mapped to a read-only principal
holding the Owner's read permissions. The read-only refusal is a single rule on the principal kind,
not a check in each endpoint. Per-request audit entries are written in the tenant's audit trail in
the request's transaction or, for reads, in a separate bounded write that never fails the read.

## API and data changes

| Method/path | Request / result |
| --- | --- |
| `POST /api/v1/operator/salons/{id}/support-sessions` | `{reason,durationMinutes}` -> 201 `{id,expiresAt,entryCode,entryUrl}`; Support only |
| `POST /api/v1/operator/support-sessions/{id}/entry-codes` | -> 201 a fresh code for the operator's own active session |
| `DELETE /api/v1/operator/support-sessions/{id}` | Own session (Support) or any (Admin) -> 204 |
| `GET /api/v1/operator/support-sessions` | Paginated; Admin all, Support own; filter by salon and state |
| `POST /api/v1/support/entry` | Salon host; `{code}` -> 200 support credential to the BFF only |
| `GET /api/v1/salon/support-sessions`, `DELETE .../{id}` | Owner only, salon host; DELETE -> 204 |

`GET /api/v1/identity/me` returns kind `support` with the session's expiry for a support principal.
Register `support_read_only` and `support_session_active`. Append migrations for support sessions
and entry codes (hash only); grants to the platform role; the tenant audit gains actor kind
`support` with its check constraint.

## UI and UX

Use the shadcn/ui component map (CR-016, CR-017): Sheet with Field, Textarea and Select for starting
a session; Item rows and Badge for lists; a non-dismissable Alert for the support banner with an
End session Button; Alert Dialog to confirm revocation; Empty, Pagination, Spinner.

| Screen | Route | Behaviour |
| --- | --- | --- |
| Start support session | Sheet from `/console/salons/{id}` | Reason, duration; then Open workspace |
| Support sessions | `/console/support` | Admin all, Support own; state, expiry; End or Revoke |
| Support entry | `/workspace/support#<code>` | Exchanges the code; refused/expired message |
| Support banner | every workspace page in support view | "Support view, read only, ends at HH:MM"; End session |
| Support access | `/workspace/support-access` | Owner: sessions and Revoke |

Final console navigation (superseding S01's "only Salons, Plans and Sign out"): Admin sees Salons,
Plans, Support sessions and Sign out; Support sees Salons, Support sessions and Sign out.

In the support view, mutating actions are disabled with the reason. Messages through next-intl.

## Risks and test focus

- `security`: entry-code single use, binding to salon and host, Origin checks, credential scope,
  expiry and revocation on the next request, no secret exposure.
- `permissions`: Support/Admin split, staff roles, Owner-only revocation, audit visibility.
- `tenant-isolation`: support credential confined to one salon; audit entries never cross salons.
- `concurrency`: simultaneous sessions and revocation during a request.
- `functional`, `responsive`, `accessibility`: support journey end to end.

## Verification commands

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
  the handoff.
- The verification commands are green.
- The handoff names the support credential variables and cookie names so the lead can update the
  identity and environment documents.
