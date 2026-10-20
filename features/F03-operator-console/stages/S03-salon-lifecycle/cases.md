# F03-S03 Salon lifecycle, export and retention: test cases

Prepared during the F03 build from the approved spec, `docs/architecture/operator-console.md`
("Suspension, closure, export and retention" and "Local test hooks"), decisions 027, 032 and 049,
PLT-06, NFR-09 and `docs/qa/`. Reconciled with the final spec, decisions 049-053 and the stage
handoff at feature-verify.

Environment: local only. Closure is irreversible and retires the slug, so every suspended or closed
salon is provisioned by the test; alpha, beta and gamma are never suspended or closed and serve as
the other salons whose data must stay untouched. Each closed salon is given data in every existing
tenant table first (staff, sessions, audit, a customer, outbox messages through the probe) so that
export and deletion have rows to prove. The export and deletion jobs run through the run-job hook
at the test-clock instant under test; the export email is read through S02's email capture.

## Tables

| Lifecycle action | Admin | Support | Staff roles x5, customer | Non-console host |
| --- | --- | --- | --- | --- |
| `POST`/`DELETE /salons/{id}/suspension`, `POST /salons/{id}/closure` | allow | 403 `permission_denied` | 401 `invalid_session` | 404 `not_found` |
| `GET /salons/{id}/lifecycle` | allow | allow (`operator.salon.read`, from S04) | 401 `invalid_session` | 404 `not_found` |
| `GET /salons/{id}/export`, `GET /salons/{id}/export/archive` | allow | 403 `permission_denied` | 401 `invalid_session` | 404 `not_found` |

Tenant tables are enumerated from the database catalogue (every table with a `tenant_id` column),
not from the product's exporter registry, so a table without an exporter is found.

## Cases

Every case id here is declared under `cases:` in the yaml beside it, and the reverse.

### TC-F03-S03-001 Suspension refuses every salon endpoint on the next request without revoking sessions

- **Type:** functional
- **Priority:** critical
- **Covers:** AC-1
- **Performed as:** Synthetic Admin; the salon's Owner and a customer with live sessions
- **Preconditions:** Three provisioned salons: active, past due, read-only
- **Steps:**
  1. Suspend each with the current version and a reason of 10 characters
  2. Owner and customer call identity and salon endpoints with their existing credentials
  3. Suspend with reasons of 9 and 501 characters on another salon
  4. alpha Owner performs a request
- **Expected:** Each suspension 200 with `status:suspended`; every salon endpoint answers 403 `salon_suspended` with nothing of the salon, meta and health still answer; sessions remain unrevoked (proved in TC-F03-S03-003); bad reasons are 400 naming `reason`; alpha is unaffected.
- **Automation:** `qa/api/F03/S03-lifecycle.spec.ts`

### TC-F03-S03-002 A suspended salon's workspace shows temporarily unavailable

- **Type:** functional
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** Owner in the browser
- **Preconditions:** A suspended provisioned salon
- **Steps:**
  1. Open the workspace sign-in and a workspace page with an existing session
- **Expected:** The temporarily unavailable page with status 503 and no salon data.
- **Automation:** `qa/web/F03/S03-lifecycle-screens.spec.ts`

### TC-F03-S03-003 Reactivation restores the billing-implied state and the same sessions work again

- **Type:** api
- **Priority:** critical
- **Covers:** AC-2
- **Performed as:** Synthetic Admin; Owner with the session from before suspension
- **Preconditions:** One suspended salon with no overdue invoice and one with an invoice 14 or more days overdue
- **Steps:**
  1. Reactivate each with a reason
  2. Owner repeats the earlier request with its pre-suspension credentials
  3. Suspend a suspended salon; reactivate an active salon; reactivate with a stale version
- **Expected:** States become `active` and `read_only` respectively; the old unexpired session works; the invalid transitions are 409 `invalid_state` and the stale version 409 `conflict`, each with no change and no lifecycle event.
- **Automation:** `qa/api/F03/S03-lifecycle.spec.ts`

### TC-F03-S03-004 While suspended, billing continues and messages and tenant jobs wait until reactivation

- **Type:** functional
- **Priority:** high
- **Covers:** AC-3
- **Performed as:** Run-job, email-capture and salon background-state hooks; synthetic Admin
- **Preconditions:** A suspended salon at an invoice issue instant; a pending customer message created before suspension
- **Steps:**
  1. Run the billing job; record a transfer
  2. Advance past the message's due time; read the salon background state
  3. Reactivate, advance again and read the background state
- **Expected:** The invoice is issued and the payment recorded while suspended; the background state shows the message waiting (not delivered) and the due tenant jobs skipped, none run; after reactivation the message is delivered and the jobs run.
- **Automation:** `qa/api/F03/S03-lifecycle.spec.ts`

### TC-F03-S03-005 Closure needs the exact slug and a reason and then removes the salon from its hostname

- **Type:** api
- **Priority:** critical
- **Covers:** AC-4
- **Performed as:** Synthetic Admin; the salon's Owner
- **Preconditions:** A provisioned salon with a live Owner session and a pending message
- **Steps:**
  1. Close with a wrong slug, the slug in upper case, no reason
  2. Close with the exact slug and a reason; close again
  3. Call the salon's hostname with and without the Owner's credential; compare with an unknown slug's answer
  4. Advance past the next invoice instant and run the billing job; create a salon with the retired slug
- **Expected:** The wrong and upper-case slugs are 400 naming `confirmSlug`, the missing reason 400 naming `reason`, each with no change; closure answers 200 `status:closed` with `closedAt` and `deleteAt` = closedAt + 30 days; a second close is 409 `invalid_state`; the hostname answers exactly as an unknown salon (404 `not_found`); the session and pending message are cancelled; no new invoice; the slug is 409 `slug_taken`.
- **Automation:** `qa/api/F03/S03-lifecycle.spec.ts`

### TC-F03-S03-006 The export contains every tenant table with exactly that salon's rows

- **Type:** data-integrity
- **Priority:** critical
- **Covers:** AC-5
- **Performed as:** Run-job hook; database observer
- **Preconditions:** Two provisioned salons with rows in every tenant table; one is closed
- **Steps:**
  1. Run the export job; download the archive as Admin
  2. Compare the manifest's tables and counts with the catalogue and per-tenant counts
  3. Compare each JSON file's ids with the closed salon's rows and with the other salon's rows
- **Expected:** The manifest lists every tenant table with the right row count, the salon profile and the export instant; each file holds exactly the closed salon's rows and none of the other salon's.
- **Automation:** `qa/api/F03/S03-export-retention.spec.ts`

### TC-F03-S03-007 The export excludes every secret and says so

- **Type:** security
- **Priority:** critical
- **Covers:** AC-5
- **Performed as:** Database observer
- **Preconditions:** The archive of TC-F03-S03-006
- **Steps:**
  1. Search every file for the password hashes, MFA secrets, token and challenge hashes and encrypted delivery payloads read from the database, and for their column names
  2. Read the manifest's exclusion list
- **Expected:** None of the values appears; the manifest names each excluded kind.
- **Automation:** `qa/api/F03/S03-export-retention.spec.ts`

### TC-F03-S03-008 The Owner's email link downloads the archive until deletion and bad tokens reveal nothing

- **Type:** security
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** Anonymous browser and API caller on the platform host
- **Preconditions:** A ready export; the captured email
- **Steps:**
  1. Read the Owner contact's captured emails
  2. Open the link; download twice; compare the SHA-256 with the export status
  3. Request a wrong, altered and (after deletion) expired token; request a token on a salon host
  4. Search API and web logs for the token
- **Expected:** Exactly one export email; downloads match the checksum and each is audited; every bad token is 404 with the same body as an unknown one; the token appears in no log.
- **Automation:** `qa/api/F03/S03-export-retention.spec.ts`

### TC-F03-S03-009 Only an Admin on the console sees export status and downloads the archive

- **Type:** permissions
- **Priority:** critical
- **Covers:** AC-7
- **Performed as:** Admin, Support, each staff role, a customer, and Admin on non-console hosts
- **Preconditions:** A ready export
- **Steps:**
  1. Read export status and download as each actor
- **Expected:** Admin gets `{status,createdAt,readyAt,deleteAt,sizeBytes,sha256}` and an attachment, no-store, whose SHA-256 matches; every other actor matches the table.
- **Automation:** `qa/api/F03/S03-export-retention.spec.ts`

### TC-F03-S03-010 Thirty days after closure the salon's tenant data is deleted and nothing else

- **Type:** data-integrity
- **Priority:** critical
- **Covers:** AC-8
- **Performed as:** Run-job hook; database observer
- **Preconditions:** A closed salon with rows in every tenant table and invoices; another provisioned salon and alpha/beta/gamma counts taken
- **Steps:**
  1. Run the deletion job at deleteAt - 1 s; count rows
  2. Run it at deleteAt; count rows; read the registry row, invoices, payments, platform audit; read the archive
- **Expected:** Nothing deleted at - 1 s; at deleteAt every tenant row of the salon and its archive are gone, the registry row is a `deleted` tombstone with the retired slug, invoices, payments and platform audit remain, and every other salon's counts are unchanged.
- **Automation:** `qa/api/F03/S03-export-retention.spec.ts`

### TC-F03-S03-011 An interrupted export or deletion resumes to the same final result

- **Type:** resilience
- **Priority:** critical
- **Covers:** AC-9
- **Performed as:** Run-job and interrupt hooks
- **Preconditions:** Two closed salons; interrupts armed after N export tables and after N deletion batches
- **Steps:**
  1. Run each job (fails at the armed point); inspect
  2. Run again; compare with an uninterrupted salon's outcome
- **Expected:** No ready archive and no partial deletion is presented as finished after the failure; the next run produces the same archive contents and the same complete deletion as the uninterrupted case.
- **Automation:** `qa/api/F03/S03-export-retention.spec.ts`

### TC-F03-S03-012 Running export or deletion twice or concurrently produces one archive and one deletion

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-9
- **Performed as:** Run-job hook
- **Preconditions:** A closed salon
- **Steps:**
  1. Run the export job eight times at once; then twice more
  2. At deleteAt run the deletion job eight times at once
- **Expected:** One archive (one checksum, one export email); one deletion with one set of audit records; no error from the losing runs leaves partial state.
- **Automation:** `qa/api/F03/S03-export-retention.spec.ts`

### TC-F03-S03-013 Support, staff and customers are refused every lifecycle and export action on any host

- **Type:** permissions
- **Priority:** critical
- **Covers:** AC-10
- **Performed as:** Actors of the table
- **Preconditions:** An active provisioned salon
- **Steps:**
  1. Call every lifecycle and export action as each actor
  2. Read the salon detail and lifecycle as Admin before and after
- **Expected:** Each cell matches the table and nothing changes.
- **Automation:** `qa/api/F03/S03-lifecycle.spec.ts`

### TC-F03-S03-014 Lifecycle, export, email and deletion are audited without content, tokens or personal fields

- **Type:** security
- **Priority:** critical
- **Covers:** AC-11
- **Performed as:** Database and log observer
- **Preconditions:** A salon taken through suspension, reactivation, closure, export, two downloads, email and deletion
- **Steps:**
  1. Read platform audit and lifecycle history
  2. Search audit, logs and error bodies for archive bytes, the token and the salon's personal values
- **Expected:** One audit row per event with actor or `system`, reason where given, time and trace id; history lists kind, reason, actor and time; none of the searched values appears.
- **Automation:** `qa/api/F03/S03-export-retention.spec.ts`

### TC-F03-S03-015 Console lifecycle actions and the download page show every state and make closure visibly irreversible

- **Type:** functional
- **Priority:** high
- **Covers:** AC-12
- **Performed as:** Admin in the console; anonymous browser on the platform host
- **Preconditions:** Provisioned salons in each state
- **Steps:**
  1. Suspend, reactivate and close through the sheets with invalid and valid input; trigger a conflict and an unavailable API
  2. Read history and export status; download
  3. Open `/exports#<token>` with a good and an unknown token
- **Expected:** Actions shown by state; the close sheet states the consequences and the 30-day deletion date and requires slug and reason; loading, validation, conflict, unavailable and success states; the token leaves the address bar at once and the page explains the archive and its deletion date or the unknown/expired message.
- **Automation:** `qa/web/F03/S03-lifecycle-screens.spec.ts`

### TC-F03-S03-016 Lifecycle screens and the download page fit every width

- **Type:** responsive
- **Priority:** high
- **Covers:** AC-12
- **Performed as:** Admin; anonymous
- **Preconditions:** A long history list
- **Steps:**
  1. At each width in the matrix open the lifecycle card, sheets, history, export card and download page
- **Expected:** No horizontal scroll, nothing clipped, every action reachable.
- **Automation:** `qa/web/F03/S03-lifecycle-screens.spec.ts`

### TC-F03-S03-017 Lifecycle screens pass the automated scan and keyboard use with focus containment

- **Type:** accessibility
- **Priority:** high
- **Covers:** AC-12
- **Performed as:** Admin, keyboard only
- **Preconditions:** As TC-F03-S03-016
- **Steps:**
  1. Run the axe scan on each view and state
  2. Close a salon by keyboard; Escape a sheet
- **Expected:** No serious or critical violations; labelled fields; focus contained in the sheet and returned to its opener.
- **Automation:** `qa/web/F03/S03-lifecycle-screens.spec.ts`
