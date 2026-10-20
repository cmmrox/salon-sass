# F03-S03 Salon lifecycle, export and retention

## Objective

An Admin can suspend and reactivate a salon, and close it for good. A closed salon disappears from
its hostname, its Owner receives a full export of its data, and its tenant data is deleted 30 days
after closure while the platform keeps its billing and audit records.

## Read first

- `docs/brd/requirements.yaml`: PLT-06, and NFR-09 for export and deletion.
- `docs/architecture/operator-console.md`: "Suspension, closure, export and retention (F03-S03)",
  and the S01/S02 rules it builds on.
- `docs/architecture/multi-tenancy.md`: access states, the tenant registry, platform data, and the
  two-tenant tests.
- `docs/architecture/identity-and-access.md`: sessions, secret material that must not be exported,
  and the existing customer erasure path.
- `docs/engineering/backend.md`: modules, jobs, outbox, clock, transactions and contract.
- `docs/engineering/web.md`: BFF rules and "Styling" (CR-016, CR-017).
- `docs/design/ui-ux-standards.md` and `docs/design/prototype/index.html`: Salon-mode cards, sheets
  and confirmation grammar.
- `docs/decisions/DECISION_LOG.md`: decisions 027, 032 and 049.
- `docs/qa/test-plan.md`: test clock and fake providers.
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| PLT-06 | Tenant lifecycle: suspend, reactivate, and close. On closure the salon receives a full export of its data, and the data is deleted after the agreed retention period. | Full, for the tenant data that exists; later features add their exporters and erasers. |

## Scope

- API (operator): suspend, reactivate and close a salon; its lifecycle history; its export status
  and archive download.
- Platform: lifecycle states on the registry; the export job and module exporters for every
  existing tenant table; the deletion job and module erasers; the export-ready email; the
  platform-host export download page and endpoint.
- Web: console lifecycle actions on the salon detail with confirmation sheets, lifecycle history,
  export status and download; the platform-host download page.
- Local hooks (`operator-console.md`, "Local test hooks"): running the export and deletion jobs now
  and interrupting either part-way; a salon's background state; a local reset to the synthetic
  baseline; the export email through S02's email capture.

## Out of scope

- Reopening a closed salon; restoring deleted data; reusing a retired slug.
- Object storage: the archive is stored in a platform table (decision 049).
- Customer-requested exports and erasure: already in F02. Support sessions: F03-S04.

## Acceptance criteria

- AC-1: An Admin suspends an active, past-due or read-only salon with a reason; on its next request
  every salon endpoint answers 403 `salon_suspended`, the workspace shows "temporarily unavailable",
  sessions are refused but not revoked, and another salon is unaffected. (PLT-06)
- AC-2: Reactivation with a reason restores the access state billing implies: `active`, or
  `read_only` when an invoice is 14 or more days overdue; existing unexpired sessions work again.
  Suspending a suspended salon or reactivating a non-suspended one is 409 `invalid_state`. (PLT-06)
- AC-3: While suspended, invoices are still issued and payments recorded; customer messages wait
  unattempted and tenant jobs are skipped, then resume on reactivation, as observed through the
  salon background-state hook. (PLT-06)
- AC-4: Closing requires the exact slug typed as confirmation and a reason; a wrong slug is a 400
  field error on `confirmSlug` and closing a closed salon is 409 `invalid_state`, without change. After closure the salon's hostname answers 404 as an
  unknown salon, its sessions and pending messages are cancelled, its jobs stop, no further invoice
  is issued, and its slug cannot be used for a new salon. (PLT-06)
- AC-5: Closure schedules one export; the job produces a ZIP whose manifest lists every tenant
  table with its row count, the salon profile and the export instant, and whose JSON files contain
  exactly that salon's rows and no other salon's. Password hashes, MFA secrets, token and challenge
  hashes and encrypted delivery payloads are absent, and the manifest says they are excluded. (PLT-06)
- AC-6: When the export is ready the Owner contact receives one email with a download link; the
  link downloads the archive with a matching checksum until deletion, each download is audited,
  and a wrong, altered or expired token returns 404 without revealing whether the salon exists. (PLT-06)
- AC-7: An Admin can see export status and download the same archive from the console; Support,
  salon staff, customers and other hosts are refused. (PLT-06)
- AC-8: When the test clock reaches 30 days after closure, the deletion job removes every tenant row
  of that salon (identity, customers, brand, outbox, jobs, audit) and the archive, leaves a
  `deleted` tombstone with the retired slug, keeps invoices, payments and platform audit, and
  deletes nothing of any other salon. One second earlier nothing is deleted. (PLT-06)
- AC-9: A deletion or export interrupted part-way resumes on the next run to the same final result,
  and running either job twice or concurrently produces one archive and one deletion. (PLT-06)
- AC-10: Support operators, salon staff roles and the customer principal are refused suspend,
  reactivate, close and export actions; lifecycle routes on any host but the console are 404
  before side effects; denied actions change nothing. (PLT-06)
- AC-11: Suspension, reactivation, closure, export creation and download, email and deletion are
  recorded in the platform audit with actor or `system`, reason where given, time and trace id;
  no archive content, token or personal field appears in audit, logs or errors. (PLT-06)
- AC-12: In the console the lifecycle actions, confirmation sheets, history, export status and
  download, and the platform-host download page, provide loading, validation, conflict,
  unavailable and success states at every documented width, keyboard-only use with focus
  containment and return, and accessible labels; closure is visibly irreversible. (PLT-06)

## Design notes

Each tenant module registers an exporter and an eraser with a public platform facade, so platform
never reads another module's tables. Export runs as a job with a bounded transaction per table and
the tenant set for that salon only; deletion runs per module in bounded batches. The access state
precedence is `closed` and `deleted` over `suspended` over `read_only` over billing states. The
existing suspended behaviour (decisions 027 and 032) stays as it is.

## API and data changes

Operator routes are under `/api/v1/operator`, console-host-only.

| Method/path | Request / result |
| --- | --- |
| `POST /salons/{id}/suspension` | `{version,reason}` -> 200 salon detail |
| `DELETE /salons/{id}/suspension` | `{version,reason}` -> 200 salon detail |
| `POST /salons/{id}/closure` | `{version,confirmSlug,reason}` -> 200 salon detail |
| `GET /salons/{id}/lifecycle` | Paginated lifecycle events: kind, reason, actor, time |
| `GET /salons/{id}/export` | `{status,createdAt,readyAt,deleteAt,sizeBytes,sha256}` |
| `GET /salons/{id}/export/archive` | The ZIP, `Content-Disposition: attachment`, no-store |

Platform host: `GET /api/v1/exports/{token}` returns the archive or 404. Salon detail gains
`status` values `suspended`, `closed`, `deleted` and `closedAt`, `deleteAt`. Append migrations for
lifecycle events, the export archive (encrypted bytes, checksum, token hash) and the registry's
states and retired-slug guard; grants to the platform role only.

## UI and UX

No lifecycle screen exists in the prototype. Use the shadcn/ui component map (CR-016, CR-017): Card
for status and export, Alert Dialog or Sheet for confirmations, Field with Textarea for reasons and
Input for slug confirmation, Item rows for history, Badge for states, Button, Spinner, Alert.

| Screen | Route | Behaviour |
| --- | --- | --- |
| Salon lifecycle | `/console/salons/{id}` | Status Badge; Suspend, Reactivate, Close by state; history list |
| Close confirmation | Sheet from the detail | States consequences and the 30-day deletion date; requires the slug and a reason |
| Export | `/console/salons/{id}` | Status, size, checksum, deletion date, Download |
| Owner download | `/exports#<token>` on the platform host | Explains the archive and its deletion date; Download; unknown/expired message |

The download page reads the token from the fragment and calls the platform-host BFF, so the token
stays out of server logs. Messages go through next-intl in `en`, `si` and `ta`.

## Risks and test focus

- `data-integrity`: export completeness and exactness, no secrets, deletion leaving nothing and
  touching no other salon, resumable jobs.
- `tenant-isolation`: export and deletion bound to one salon; a closed hostname reveals nothing.
- `time`: 30-day boundary with the test clock; billing during suspension.
- `security` and `permissions`: download token, operator roles, host binding, audit hygiene.
- `functional`, `responsive`, `accessibility`: console lifecycle screens and the download page.

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

- The developer has checked every acceptance criterion on the running product with synthetic
  salons and the test clock, and recorded it in the handoff.
- The verification commands are green.
- Every existing tenant table has a registered exporter and eraser, proved by a test that fails when
  a tenant table has neither.
- The handoff names the export key variable and local hooks so the lead can update the environment
  document.
