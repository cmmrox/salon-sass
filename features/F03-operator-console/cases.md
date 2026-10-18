# F03 Operator console: salons, plans and billing: test cases

Prepared during the F03 build (lifecycle "QA preparation during a build") from the feature README,
the four approved stage specs, `docs/architecture/operator-console.md` (including "Local test
hooks"), decisions 042 and 049, PLT-01 and PLT-04 to PLT-07, NFR-20 and `docs/qa/`. Expected
results come from those documents only. Nothing has been run and no case is performed; F03 QA
runs once, through `qa/scripts/sdlc run --feature F03`, after all four stages close.

## Test approach

| Test type | Why it applies |
| --- | --- |
| `api` | Every stage adds operator or salon endpoints: contract fields, statuses, stable error codes, field errors, paging and idempotency keys |
| `security` | Operator sign-in, MFA, sessions, rate ceilings, host binding, cookies and Origin checks, signed provider events, export tokens, support entry codes, audit hygiene and the build's secret material |
| `tenant-isolation` | Provisioning, billing, export, deletion and support sessions all hold or return salon data; per endpoint and record kind, once at the database as the runtime roles, and one journey keeping a second salon untouched throughout |
| `permissions` | Admin and Support differ on every operator action; five staff roles and the customer are checked against every protected action; read-only and support-read-only states gate tenant mutations |
| `concurrency` | One invariant each: one slug, one plan key, one receipt, one MFA step, one refresh family, one invoice per period, one payment per invoice, one last seat, one archive and deletion, one entry-code exchange |
| `payment-integrity` | Subscriptions take and record money through a local fake provider and recorded bank transfers: exact amounts, at most one payment, signed events only |
| `data-integrity` | Additive platform migrations, atomic provisioning rollback, invoice numbering and immutability, month anchors, state boundaries, export exactness and retention deletion |
| `resilience` | Database and API down and silent for the console and billing screens; interrupted billing, export and deletion jobs resume |
| `functional`, `responsive`, `accessibility` | New console screens in every stage, the workspace billing page and banners, the export download page and the support view, at the full width matrix |
| `build` | S01 AC-20 is about the built web artifacts: no environment file or key value |

Not applied, and why: `branding`, because the console and support banner use the neutral theme and
no stage changes a salon's brand presentation (the support view shows the salon workspace as it
already is; provisioning's neutral brand is a routing prerequisite, asserted for name and zone in
TC-F03-S01-019); `load`, because no F03 endpoint has a response-time target in the requirements and
none is on the booking path; `mobile`, because the customer app is milestone M2 (NFR-20's customer
half waits for F15). Staging, real payment providers, real messages and public TLS are outside
local QA and are not certified (README).

Limits kept (`references/test-design.md`): every acceptance criterion has a case and a negative
case where it can be violated; input variants are table rows; a rule proved through the API is not
proved again in the browser; beyond the criteria only critical cases are added. Totals: S01 40,
S02 28, S03 17, S04 17, journeys 3.

Environment: local only, synthetic data. alpha, beta and gamma are never provisioned over, billed,
suspended or closed: closure is irreversible and retires the slug, so every salon whose lifecycle
changes is provisioned by its test with a unique slug, or created by the unbilled legacy salon hook.
Operator accounts come from the `/local/operator` fixture hooks with credentials generated in
memory. Time moves only through the test clock, and scheduled work runs through the run-job hook at
the instant under test; clock-moving tests run serially and restore real time.

Preparation notes for feature-verify:

- F02's clock fixture guard (`qa/shared/identity-clock-cleanup.ts`) requires exactly the three fixed
  salons, so F03 clock cases use their own clock helper, and every F03 test ends with the S03 local
  reset hook: it removes salons created after the synthetic baseline, resets hook-created operators,
  returns the clock to real time and reschedules job occurrences pushed ahead, so F01/F02 guards and
  regression run unchanged afterwards.
- Hook paths, signature header, BFF action names and test ids are taken from the stage handoffs;
  until then the shared helpers keep them in one map each, marked as awaiting the handoff.

## Cases

Every case id here is declared under `cases:` in the yaml beside it, and the reverse.

### TC-F03-001 An operator takes one salon from creation through billing, suspension, support and closure in the console

- **Type:** functional
- **Priority:** critical
- **Covers:** PLT-01, PLT-04, PLT-05, PLT-06, PLT-07
- **Performed as:** Synthetic Admin and Support in the console; the provisioned Owner in the workspace
- **Preconditions:** Operator fixtures; test clock under QA control; fake provider and email capture
- **Steps:**
  1. Admin defines a plan with two staff seats and provisions a bank-transfer salon on it; the Owner signs in and enrolls MFA
  2. The Owner creates staff up to the limit and sees the next refused; Plan and billing shows the plan's limits and usage
  3. Cross trial end: the invoice appears in the console and the workspace; Admin records the transfer; the subscription is active
  4. Admin suspends and reactivates the salon; Support starts a session, enters through the code, sees the Owner's pages under the banner and ends it; the Owner sees the session in the audit list
  5. Admin closes the salon; the Owner's email link downloads the export; at deletion the hostname stays unknown and the console shows the tombstone
- **Expected:** Values pass between stages unchanged: the plan defined in S01 is the one billed and enforced in S02, the invoice and state read in S03's lifecycle card match S02, the support view in S04 shows S01's profile and S02's billing page, and the export holds the staff and audit created along the way. Each step's outcome is checked once more through the API; the journey does not repeat stage rules.
- **Automation:** `qa/web/F03/journey-operator-lifecycle.spec.ts` (planned; written at feature-verify once all four stage handoffs name their hooks and test ids)

### TC-F03-002 A second salon stays isolated and unaffected through every transition of the first

- **Type:** tenant-isolation
- **Priority:** critical
- **Covers:** PLT-01, PLT-04, PLT-05, PLT-06, PLT-07
- **Performed as:** Owners of two provisioned salons; alpha Owner; synthetic Admin and Support
- **Preconditions:** Salon B (active, with staff, audit and an invoice) and alpha snapshots taken through the API and the database
- **Steps:**
  1. Take salon A through provisioning, seat exhaustion, read-only, suspension, a support session, closure, export and deletion
  2. After each transition, B's and alpha's Owners read staff, audit, entitlements, invoices and perform one mutation; compare database counts per tenant
  3. Check A's export and the support credential for any B or alpha record
- **Expected:** B and alpha behave and read exactly as before at every step (their counts show records existed first); no export file, audit list, entitlement or support response contains a B or alpha record; A's deletion removes no B or alpha row.
- **Automation:** `qa/api/F03/journey-second-salon-isolation.spec.ts` (planned; written at feature-verify once all four stage handoffs name their hooks and test ids)

### TC-F03-003 Access states combine in the documented precedence across billing, lifecycle and support

- **Type:** data-integrity
- **Priority:** critical
- **Covers:** PLT-05, PLT-06, PLT-07
- **Performed as:** Synthetic Admin and Support; the salon's Owner
- **Preconditions:** A provisioned salon driven to `read_only` by billing
- **Steps:**
  1. Support starts a session on the read-only salon and reads; then Admin suspends the salon and the support credential is used again
  2. Admin reactivates: the salon returns to `read_only`; the Owner pays every overdue invoice and the salon is `active`
  3. Support starts a new session; Admin closes the salon; the support credential and the Owner's session are used again
- **Expected:** Read-only allows the support session and its reads; suspension refuses the support credential with 403 `salon_suspended` and refuses new sessions; reactivation restores `read_only`, payment restores `active`; after closure every credential gets the unknown-salon 404 and no session can be started. `closed` over `suspended` over `read_only` over billing states holds at each step.
- **Automation:** `qa/api/F03/journey-access-state-precedence.spec.ts` (planned; written at feature-verify once all four stage handoffs name their hooks and test ids)
