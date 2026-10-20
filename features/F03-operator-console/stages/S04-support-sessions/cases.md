# F03-S04 Audited support sessions: test cases

Prepared during the F03 build from the approved spec, `docs/architecture/operator-console.md`
("Support sessions" and the S01 authority rules), decision 049, PLT-07, IAM-11, NFR-20 and
`docs/qa/`. Reconciled with the final spec, decisions 049-053 and the stage
handoff at feature-verify.

Environment: local only. Support sessions are opened on salons provisioned by the test and on
alpha (read-only access changes nothing there; the audit entries it adds are asserted against a
baseline). The entry code is exchanged directly with `POST /api/v1/support/entry` for API cases
and through the workspace page for browser cases. Time moves only through the test clock.

## Tables

| Support action | Support (own) | Support (another's) | Admin | Staff roles x5, customer | Non-console host |
| --- | --- | --- | --- | --- | --- |
| `POST /salons/{id}/support-sessions` | allow | - | 403 `permission_denied` | 401 `invalid_session` | 404 `not_found` |
| `POST /support-sessions/{id}/entry-codes` | allow | refused | 403 `permission_denied` | 401 `invalid_session` | 404 `not_found` |
| `DELETE /support-sessions/{id}` | allow (204) | 403 `permission_denied` (S04 spec, "Refusals not named above") | allow, any (204) | 401 `invalid_session` | 404 `not_found` |
| `GET /support-sessions` | own only | - | all | 401 `invalid_session` | 404 `not_found` |

| Salon route | Owner | Manager | Receptionist, Professional, Accountant | Support credential |
| --- | --- | --- | --- | --- |
| `GET /api/v1/salon/support-sessions`, `DELETE .../{id}` | allow (DELETE 204) | 403 `permission_denied` | 403 `permission_denied` | 403 `support_read_only` for DELETE |
| Audit list (support entries) | allow | allow | 403 | as the Owner reads |

## Cases

Every case id here is declared under `cases:` in the yaml beside it, and the reverse.

### TC-F03-S04-001 A Support operator starts a bounded session for an accessible salon

- **Type:** api
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** Synthetic Support operator
- **Preconditions:** Active, past-due and read-only provisioned salons; test clock fixed
- **Steps:**
  1. Start a session on each with a 10-character reason and no duration; with 500 characters and 120 minutes; with 5 minutes
  2. Start with reasons of 9 and 501 characters and durations 4, 121, 30.5 and "30"
- **Expected:** Valid starts are 201 `{id,expiresAt,entryCode,entryUrl,codeExpiresAt}` (the spec's fields and the code's own 60-second expiry, S04 handoff) with expiresAt = now + duration (30 minutes by default) and entryUrl `<SALON_SUPPORT_LINK_ORIGIN with the salon host>/workspace/support#<code>` (`https://<slug>.<platform>` outside local, `http://<slug>.localhost:3100` locally); invalid values are 400 naming the field and start nothing.
- **Automation:** `qa/api/F03/S04-support-sessions.spec.ts`

### TC-F03-S04-002 Suspended or closed salons, a second own session and an Admin cannot start a session

- **Type:** permissions
- **Priority:** high
- **Covers:** AC-1
- **Performed as:** Support operators A and B; Admin
- **Preconditions:** A suspended and a closed provisioned salon; an active salon
- **Steps:**
  1. A starts on the suspended and the closed salon
  2. A starts twice on the active salon; B starts on it
  3. Admin starts on it
- **Expected:** Suspended and closed are 409 `invalid_state` with no session; A's second is 409 `support_session_active`; B's is 201 (per operator); Admin is 403 `permission_denied`.
- **Automation:** `qa/api/F03/S04-support-sessions.spec.ts`

### TC-F03-S04-003 An entry code works once, within 60 seconds, on its own salon's host only

- **Type:** security
- **Priority:** critical
- **Covers:** AC-2
- **Performed as:** Support operator; anonymous exchanger
- **Preconditions:** Sessions on two salons
- **Steps:**
  1. Exchange a code on its salon host; exchange it again
  2. Exchange a fresh code at issue + 61 s
  3. Exchange an altered code, salon A's code on salon B's host, and a code on the console and bare hosts
  4. Through the BFF, exchange with no Origin, a foreign Origin and cross-site fetch metadata
- **Expected:** Only the first exchange issues a support credential (200); a reused, expired, altered or other-salon code is 401 `invalid_code`, a code on the console or bare host is 404 `not_found`, the BFF refuses a missing or foreign Origin as for every BFF mutation; none issues a credential or a `salon_support_*` cookie, and the refused attempts leave the code's single use intact.
- **Automation:** `qa/api/F03/S04-support-sessions.spec.ts` and `qa/web/F03/S04-support-screens.spec.ts`

### TC-F03-S04-004 Simultaneous exchanges of one code issue exactly one support credential

- **Type:** concurrency
- **Priority:** critical
- **Covers:** AC-2
- **Performed as:** Anonymous exchanger
- **Preconditions:** One fresh entry code
- **Steps:**
  1. Send eight exchanges of the code at once
- **Expected:** Exactly one credential; seven 401 `invalid_code` refusals.
- **Automation:** `qa/api/F03/S04-support-sessions.spec.ts`

### TC-F03-S04-005 The entry code never reaches a server log or a request URL

- **Type:** security
- **Priority:** critical
- **Covers:** AC-2
- **Performed as:** Support operator opening the workspace in the browser
- **Preconditions:** Network recording on; API and web log offsets noted
- **Steps:**
  1. Open entryUrl; record every request URL and the address bar after load
  2. Search the API and web logs written since the offset for the code
- **Expected:** The code is only in the fragment and the POST body; it leaves the address bar at once; it appears in no request URL, referrer or log line.
- **Automation:** `qa/web/F03/S04-support-screens.spec.ts`

### TC-F03-S04-006 The support view shows what the Owner can read under a persistent banner and no secrets

- **Type:** functional
- **Priority:** critical
- **Covers:** AC-3
- **Performed as:** Support operator in the browser; the salon's Owner for comparison
- **Preconditions:** A provisioned salon with staff, audit entries and invoices
- **Steps:**
  1. Enter through the code; visit each workspace page the Owner can open
  2. Compare each page's data with the Owner's view through the API
- **Expected:** Every Owner-readable page and its data are shown; a banner reading support view, read only, with the end time is on every page; no password, MFA material or token is displayed; /api/v1/identity/me reports kind `support` with the session expiry.
- **Automation:** `qa/web/F03/S04-support-screens.spec.ts`

### TC-F03-S04-007 Every mutation in the support view answers support_read_only and changes nothing

- **Type:** security
- **Priority:** critical
- **Covers:** AC-3
- **Performed as:** Support credential (API) and support view (browser)
- **Preconditions:** Mutating tenant routes enumerated from docs/api/openapi.json
- **Steps:**
  1. Call every tenant mutation with a valid body using the support credential
  2. In the browser, inspect each mutating control
- **Expected:** Every call is 403 `support_read_only` and the affected records are unchanged; every mutating control is disabled with the reason.
- **Automation:** `qa/api/F03/S04-support-sessions.spec.ts` and `qa/web/F03/S04-support-screens.spec.ts`

### TC-F03-S04-008 The support credential and operator or staff credentials stay inside their own boundaries

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-4
- **Performed as:** Support credential for salon A; A's Owner; a Support operator
- **Preconditions:** Salons A and B; a customer of A
- **Steps:**
  1. Use the support credential on operator routes, customer routes, salon B's host and the console
  2. Use the Owner's staff credential to start, list and exchange support sessions on the console and the support entry
  3. Open A's workspace with only the operator's console cookies
- **Expected:** Support credential: 401 `invalid_session` on operator routes, 403 `permission_denied` on customer routes (the S04 handoff's refusal; AC-4 names no code), 404 on B and on the bare API host; staff credential refused for every support-session action; operator credentials open no workspace.
- **Automation:** `qa/api/F03/S04-support-sessions.spec.ts`

### TC-F03-S04-009 Expiry, operator end, Admin revoke and Owner revoke each end support on the next request

- **Type:** security
- **Priority:** critical
- **Covers:** AC-5
- **Performed as:** Four support sessions; Support, Admin and Owner
- **Preconditions:** Test clock under QA control
- **Steps:**
  1. End one by moving past expiry, one by the operator's DELETE, one by Admin revoke, one by Owner revoke
  2. Use each credential once more; ask for a fresh entry code and exchange an unused earlier code
- **Expected:** Every next support request fails with 401 `invalid_session`; no renewal exists; no new code is issued and earlier codes are refused.
- **Automation:** `qa/api/F03/S04-support-sessions.spec.ts`

### TC-F03-S04-010 The workspace leaves the support view when the session ends

- **Type:** functional
- **Priority:** high
- **Covers:** AC-5
- **Performed as:** Support operator in the browser
- **Preconditions:** An open support view
- **Steps:**
  1. Revoke the session as the Owner; navigate in the support view
  2. Open a new support view and press End session
- **Expected:** The next navigation leaves the support view with the expired message; End session ends it and clears `salon_support_*` cookies.
- **Automation:** `qa/web/F03/S04-support-screens.spec.ts`

### TC-F03-S04-011 The salon audit shows start, every request and end to Owner and Manager only

- **Type:** permissions
- **Priority:** critical
- **Covers:** AC-6
- **Performed as:** Owner, Manager, Receptionist, Professional, Accountant; another salon's Owner
- **Preconditions:** A session with three reads, one refused mutation and an Owner revoke; audit baseline
- **Steps:**
  1. Each role reads the audit list
  2. The other salon's Owner reads its own audit
- **Expected:** Owner and Manager see the start (operator display name, reason, expiry), one entry per request (method, route template, outcome, time) and the end with how it ended; other roles are 403; the other salon shows none of it; no operator email or secret appears.
- **Automation:** `qa/api/F03/S04-support-sessions.spec.ts`

### TC-F03-S04-012 The Owner lists and revokes the salon's support sessions and other roles cannot

- **Type:** permissions
- **Priority:** high
- **Covers:** AC-7
- **Performed as:** Owner and every other role of the salon
- **Preconditions:** One active and one ended session
- **Steps:**
  1. Each role lists `GET /api/v1/salon/support-sessions` and revokes the active one
  2. Owner opens the Support access page
- **Expected:** Owner sees both with reason, operator name, start, end and state and revokes the active one; other roles are 403 and revoke nothing; the page shows the same and offers Revoke only on the active session.
- **Automation:** `qa/api/F03/S04-support-sessions.spec.ts` and `qa/web/F03/S04-support-screens.spec.ts`

### TC-F03-S04-013 Support finds salons; Admin sees and revokes every session; platform audit records each

- **Type:** permissions
- **Priority:** high
- **Covers:** AC-8
- **Performed as:** Support operators A and B; Admin; database observer
- **Preconditions:** Sessions by A and B on two salons
- **Steps:**
  1. A lists salons and opens a detail; A lists support sessions
  2. Admin lists sessions filtered by salon and by state and revokes B's
  3. Read platform audit for the sessions
- **Expected:** A reads the directory and detail and sees only its own sessions; Admin sees all with salon, operator, reason and state and the revoke takes effect; platform audit has start, end and revocation rows.
- **Automation:** `qa/api/F03/S04-support-sessions.spec.ts`

### TC-F03-S04-014 Simultaneous sessions stay separate per operator and per salon

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** AC-9
- **Performed as:** Support operators A and B
- **Preconditions:** A and B each in salon X; A also in salon Y
- **Steps:**
  1. Each credential reads staff of its salon and of the other salon
  2. End A's session on X; use the other three credentials
- **Expected:** Each credential reads only its own salon (404 on the other); ending A on X leaves B on X and A on Y working.
- **Automation:** `qa/api/F03/S04-support-sessions.spec.ts`

### TC-F03-S04-015 Support screens show every state and the banner is announced and never hidden

- **Type:** functional
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** Support operator; Owner
- **Preconditions:** Sessions in each state
- **Steps:**
  1. Use the console start sheet with invalid and valid input, and the session list
  2. Open the entry page with good, used and expired codes; simulate an unavailable API
  3. Try to dismiss the banner; scroll and resize
- **Expected:** Loading, validation, refused, expired, unavailable and success states; the banner has no dismiss control, stays visible on every page and is announced by assistive technology.
- **Automation:** `qa/web/F03/S04-support-screens.spec.ts`

### TC-F03-S04-016 Support screens fit every width without clipping or horizontal scroll

- **Type:** responsive
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** Support operator; Owner
- **Preconditions:** A long session list
- **Steps:**
  1. At each width in the matrix open the start sheet, session list, entry page, banner and Support access page
- **Expected:** No horizontal scroll, nothing clipped, every action reachable, the banner fully visible.
- **Automation:** `qa/web/F03/S04-support-screens.spec.ts`

### TC-F03-S04-017 Support screens pass the automated scan and keyboard-only use

- **Type:** accessibility
- **Priority:** high
- **Covers:** AC-10
- **Performed as:** Support operator and Owner, keyboard only
- **Preconditions:** As TC-F03-S04-016
- **Steps:**
  1. Run the axe scan on each view and state
  2. Start, enter, end and revoke by keyboard
- **Expected:** No serious or critical violations; labelled fields; focus contained in sheets and dialogs and returned.
- **Automation:** `qa/web/F03/S04-support-screens.spec.ts`
