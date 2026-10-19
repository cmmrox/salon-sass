# F03 Operator console: salons, plans and billing: product review

Reviewed as: the operator, setting up a new salon and working out what went wrong at one (Admin and
Support, at a 1280-1440px desk and on a 360-390px phone); the salon owner at the end of the day
(Plan and billing, the billing banner, Support access, the activity log); a receptionist on a
768px tablet meeting the read-only and support banners; the Owner of a closed salon opening the
export link. Local synthetic data only.
Compared with: the supplied prototype's Salon-mode grammar (`docs/design/prototype/index.html`;
no operator, billing, lifecycle or support screen exists in it) and the earlier F02 workspace.
Competitor operator consoles are not public, and no competitor pages were researched in this pass
(see "Compared with other products").

## Verdict

The operator can provision, bill, suspend, close and support a salon end to end, and the hard
parts are right: tenant isolation held through every transition, money is exact and paid at most
once, closure is unmistakably irreversible, and support access is short, owner-visible and truly
read-only. It is not ready to pass yet: six defects are filed, four of them major (the
contract's nullability, the console's outage deadline and two console accessibility faults) and
two minor (an intermittent missing "session ended" notice, and plan pickers capped at 100). The one change that would most
improve it for the operator is showing each legacy salon's real name in the directory instead of
three identical "Not configured" rows.

## What works well

- Provisioning answers with exactly what the operator needs next: the hostname, the assigned plan
  and the Owner's next sign-in step; the initial password is cleared and never echoed.
- The close sheet states the consequences, the deletion date and the retired slug, and demands the
  exact slug: a person cannot close the wrong salon by accident.
- Support access: the code lives in the URL fragment and leaves the address bar at once; the banner
  is announced, cannot be dismissed, stays under the header when scrolled and shows the end time in
  the salon's zone; mutating controls are disabled with a plain reason; the Owner sees every request
  in the activity log and can revoke from a page of their own.
- Billing state reaches every staff member as one banner with the date that matters (trial end,
  due date, read-only from), and money reads as "Rs. 2,500.50" in console and workspace alike.
- Recovery: after a database or API outage every console and workspace page recovered without a
  restart within seconds of the dependency returning.

## Suggestions

Ranked, most valuable first. Findings that break a requirement are filed as bugs, not listed here.

| Id | Class | Suggestion | Disposition |
| --- | --- | --- | --- |
| SUG-F03-01 | should | Show a legacy salon's published name instead of "Not configured" in the salon directory and detail | |
| SUG-F03-02 | should | Disable or hide the whole add-staff form in the support view and in a read-only salon, not only its submit button | |
| SUG-F03-03 | should | Let operators pick time zone (and country/currency) from a searchable list rather than typing IANA names | |
| SUG-F03-04 | idea | Let an Admin archive plans so the plan directory and the plan picker list only plans still offered | |
| SUG-F03-05 | idea | Cap a renewed operator token's `accessExpiresAt` at the session's absolute end | |
| SUG-F03-06 | idea | Compact the stacked support and billing banners on phones | |
| SUG-F03-07 | idea | Show the close sheet's deletion date from the server, not the browser's clock | |

### SUG-F03-01 Show a legacy salon's published name instead of "Not configured"

- **Observed:** `/console/salons` lists alpha, beta and gamma as three rows titled "Not configured",
  told apart only by their hostnames; their workspaces show "Alpha Studio" and the others. The
  platform profile name is null for salons provisioned before S01, and the spec asks for "not
  configured" rather than fabricated values.
- **Why it matters:** the operator working out what went wrong at a salon searches and scans by
  the name the salon calls itself; three identical titles invite opening the wrong one, and
  suspension or closure starts from that row.
- **Instead:** for a null platform name, show the salon's published brand name with a "from the
  salon's branding" hint (or backfill the platform name once from branding); keep "Not configured"
  for the fields that truly have no value.
- **Evidence:** `qa/reports/f03-manual/console-salons-360.png`.

### SUG-F03-02 Disable the whole add-staff form in the support view and a read-only salon

- **Observed:** in the support view, Staff access shows "Support view is read only..." and disables
  Role, Remove and "Add staff account", but the form's Email, Mobile, Initial password and Role
  fields stay editable.
- **Why it matters:** a support operator reproducing a salon's problem can type a whole new staff
  member before discovering the button never works; it also looks as if a password could be
  entered on the salon's behalf.
- **Instead:** render the form inside a disabled `fieldset` (or collapse it behind the reason) when
  the principal is support or the salon is read-only.
- **Evidence:** support view of `/workspace/staff` at 1280 (QA diagnostic capture of TC-F03-S04-007).

### SUG-F03-03 Searchable time zone and country/currency pickers

- **Observed:** Create salon takes country, currency and time zone as free text (decision 050);
  the defaults LK, LKR and Asia/Colombo cover the launch market, and a typo is a field error only
  after submitting.
- **Why it matters:** the operator setting up a salon abroad must know `Europe/London`-style names
  by heart; a wrong zone silently shifts every booking and invoice boundary until noticed.
- **Instead:** a shadcn/ui Combobox with search over the supported codes and zones, defaulting as
  today.
- **Evidence:** `qa/reports/f03-manual/console-create-salon-sheet-360.png`.

### SUG-F03-04 Archive plans

- **Observed:** plans are immutable and cannot be removed (S01 out of scope), and every plan ever
  defined stays in the directory and in the Create salon plan picker; local QA alone left dozens.
- **Why it matters:** as plans are revised the operator picks from an ever-longer list and can
  assign a superseded plan.
- **Instead:** an "archived" flag that hides a plan from new assignments while keeping existing ones.
- **Evidence:** `qa/reports/f03-manual/console-plans-1280.png`.

### SUG-F03-05 Cap a renewed token at the session's end

- **Observed:** a renewal one minute before the seven-day absolute end returns `accessExpiresAt`
  four minutes after it; the token is refused (401) once the session ends.
- **Why it matters:** the console's BFF trusts that expiry; near the end it keeps a token it thinks
  is valid and only learns otherwise from a refused request.
- **Instead:** return `min(now + 5 min, session end)`.
- **Evidence:** QA diagnostic of operator renewal across the absolute end (S01 AC-5 still holds).

### SUG-F03-06 Compact the stacked support and billing banners on phones

- **Observed:** at 390px a support view of a read-only salon opens with the support banner and the
  read-only notice stacked, about 430px of an 844px screen, before any page content.
- **Why it matters:** the support operator reproducing a problem on a phone, and a receptionist on
  a read-only salon, scroll past the same notices on every page.
- **Instead:** on narrow screens keep one line per banner with a "details" disclosure, or merge the
  two into one Alert when both apply.
- **Evidence:** `qa/reports/f03-explore/support-view-390.png`, `support-billing-390.png`.

### SUG-F03-07 Server-provided deletion date on the close sheet

- **Observed:** with the application clock at July 2027 the close sheet said the data would be
  deleted "on Nov 18, 2026", 30 days from the browser's date; the closure itself used the
  application clock correctly.
- **Why it matters:** the date is the operator's last check before an irreversible action; a
  device whose clock is wrong shows the wrong date.
- **Instead:** compute the preview from the API's current time (or return it with the salon detail).
- **Evidence:** `qa/reports/f03-explore/console-close-sheet-390.png`.

## Verification in this pass

- Tested revision `c1d6e73` (product source identical to the closed stages' `8ef1649`); final
  recorded run `qa/reports/20261019-093648-F03-09df7b0ccb75`: 21 automation entries, 15 pass and
  6 fail; 111 tests, 104 pass and 7 fail, none skipped. Every failure is one of the bugs below.
- Earlier recorded runs at `1af62a2` and `0ce4ca1` (14 and 15 of 21 entries) each exposed test
  defects, repaired and committed before the final run; their reports are kept.
- Manual: TC-F03-S01-036, the reference comparison, performed on 2026-10-19. Deferred:
  TC-F03-S01-042, until BUG-F03-006 is fixed.
- Passing throughout: operator sign-in, MFA, sessions and ceilings; host binding; provisioning
  atomicity and idempotency; the database boundary; the build artifact scan; billing cycle,
  events, transfers, read-only and seats; suspension, closure, export and deletion; support
  sessions; and all three journeys, including the second-salon isolation journey.

## Compared with other products

Not compared in this pass. The operator console is internal and the comparison products publish
no equivalent screens; the owner-facing parts (subscription state, support access, closure
export) were not checked against Fresha's, Booksy's or Vagaro's current public help pages, so no
claim is made about how they handle them. What ours does, for a later comparison: the Owner sees
every support session and each request made in it and can revoke it; an overdue salon becomes
read-only while keeping sign-in, reads and customer privacy rights; a closed salon's Owner gets an
export link valid for the 30 days before deletion.

## Notes for the lead (documents)

- The S01 handoff lists platform audit actions as `sign_in_failed`, `signed_in` and so on; the
  product records them as `operator.sign_in_failed`, `operator.signed_in`, `operator.signed_out`,
  `operator.mfa_*`, `operator.session_renewed`, `operator.refresh_replayed`. The S04 spec says audit
  action names are "those recorded in the stage handoffs".
- `operator-console.md` says the console opens `https://<slug>.<platform>/workspace/support#<code>`;
  that is `SALON_SUPPORT_LINK_ORIGIN`'s default, and locally it is `http://<slug>.localhost:3100`.
- The S04 spec's API table lists the start response as `{id,expiresAt,entryCode,entryUrl}`; the
  product adds `codeExpiresAt` (named in the handoff, not in the spec).
- `docs/qa/test-plan.md` could record that the shared identity fixture resets the fixed salons'
  staff around every Playwright test, so a shell check needing staff must seed its own.

## Bugs filed from this review

- BUG-F03-001 (major) The generated contract forbids the nulls the API returns and documents 200
  for the support routes' 201 and 204.
- BUG-F03-002 (major) Console pages report unavailable after 5.4 to 6.4 s, beyond the 5 s deadline.
- BUG-F03-003 (major) Console item lists have a list role without list items.
- BUG-F03-004 (major) Console help and empty-state text has 4.25:1 contrast.
- BUG-F03-005 (minor) The next page after support access ends sometimes shows the staff sign-in
  instead of the "session ended" notice.
- BUG-F03-006 (minor) The plan pickers offer only the 100 newest plans.
All six were found by cases or while exercising them; none came from the review alone.
