# F04 Salon setup: profile, branding, hours and policies: product review

Reviewed as: a salon owner setting up a new salon at the end of the day (desktop at 1280 and a
small Android phone at 360), a Manager who shares the settings with the Owner, a receptionist who
must not change them, and the operator (holiday calendar, support session). The customer only
meets the published brand, profile, hours and booking-rule sentences through the API in R1.
Compared with: Fresha and Booksy (public help-centre pages, read 2026-11-01).

## Test approach

| Type | Applies because | Ruled out because |
| --- | --- | --- |
| `functional` | Four new Settings tabs, the closure sheet, the History sheet and the console's Holiday calendar | - |
| `responsive` | The same screens at the six widths of `docs/design/ui-ux-standards.md` (360, 390, 768, 1024, 1280, 1440), with long content | - |
| `accessibility` | The same screens: axe (WCAG 2.2 AA and best-practice) in each state, accessible names, keyboard-only paths, focus kept in sheets, and the drawn contrast of the date picker's days (axe leaves text on a translucent tint "incomplete") | - |
| `api` | New endpoints in `branding`, `staff`, `platform` (holiday calendar) and `booking` | - |
| `tenant-isolation` | Every new endpoint and table holds salon data: another salon's staff get "not found", a salon's context sees only its rows, with no context none (each stage), requests for two salons at once (S01), and the operator outside a support session (TC-F04-002) | - |
| `permissions` | Only the Owner and Manager change settings: each of the five roles on each settings endpoint, support sessions read-only, suspended and read-only salons | - |
| `concurrency` | Two people save the same draft, hours, holiday acceptance, closure date or booking rules version: one case per invariant (exactly one wins) | - |
| `data-integrity` | Migrations backfill a default branch and brand draft (S01, S02 scratch-database checks); booking-rule versions are immutable and kept in minor units with no rounding (S04) | - |
| `branding` | S02 changes the published brand: the preview and the workspace with a dark and a light colour, drafts never shown, the readable button text rule | - |
| `security` | Image upload (content sniffing, size, decoding limits, metadata stripped), images served only on their salon's host, no field value, image byte or closure reason in audit, logs or errors | - |
| `resilience` | - | No external provider or background dependency is added that a user waits on; the BFF's "unavailable" path is covered as a functional state (aborted requests). The image cleanup job is proved with the test clock (S02 AC-10) |
| `load` | - | No endpoint here has a response-time target in the requirements or carries a booking; the public salon call's cost is a recorded decision (README) |
| `mobile` | - | No customer-app screen in F04 (the app is milestone M2-mobile) |
| `payment-integrity` | - | Deposits are settings only; nothing is charged until F08 |
| `build` | - | No criterion about the build or the tooling |

Deliberate narrowing, as the specs and the catalogue allow: rules proved at the API (every boundary
and every role on every endpoint) are not proved again in the browser; the browser cases check what
a person sees and persistence once. `BookingPolicies` and `OpeningSchedule` have no outside route;
their criteria that ask for integration tests (S03 AC-8, S04 AC-6) are the developer's, and QA
checks from outside what a user can observe (immutable stored versions, public opening information).
TC-F04-S03-031 (affected bookings) stays deferred to F07.

New critical checks were shown to fail on a realistic violation before they were trusted (a temporary
proof file, run once and removed): the "nothing saved" snapshot changes after a real save; the
"exactly one save" count is 8 when the same saves are sent one after another; the row-level count
sees other salons for a role that bypasses RLS; a beta staff label is not in alpha's label set; an
allowed credential answers 200, which the operator refusal check rejects.

## Verdict

Good enough to put in front of salon owners. All four tabs work from empty to published at every
width, each salon sees only its own settings, and the booking-rule summary says exactly what the
prototype says. No bug was filed. The biggest improvement would be letting an owner see *what*
changed in a booking-rules version, not only which rules changed (SUG-F04-01). These rules decide
what a customer pays and keeps, so an owner will be asked about them.

## What works well

- Drafts and publishing behave exactly as the README promises. Profile and Branding are never
  served until published, and then exactly as published. Hours, closures and booking rules are
  served on the next request (TC-F04-001).
- "What customers see before paying" matches the prototype's `ruText` word for word, for every
  option tried, as the form changes. A value that breaks its rule leaves the summary on the last
  valid value instead of showing nonsense.
- Saving the same booking-rules version twice cannot double-save: of 8 saves sent at once, exactly
  one made a version. Versions are written once, and the application role cannot update or delete
  them.
- The conflict, unavailable and leave-guard states are the same on all four tabs, and a Settings
  tab change is now guarded like a navigation link.
- The History sheet says "You", the author's email or "A team member", never anything from another
  salon.

## Suggestions

Ranked, most valuable first. Nothing here breaks an acceptance criterion. These are proposals for
the owner, not requirements: nothing is built from them until the owner approves a change request.

| Id | Class | Suggestion | Disposition |
| --- | --- | --- | --- |
| SUG-F04-01 | should | Show each booking-rules version's values (before and after), not only the names of the rules it changed | CR-024 |
| SUG-F04-02 | should | Keep the selected Settings tab in view on phones, and show that the tab rail scrolls | CR-024 |
| SUG-F04-03 | should | When there is no deposit, stop offering "The deposit" as what a late cancellation or a no-show keeps | CR-024 |
| SUG-F04-04 | should | Make "Free cancellation until N hours" read sensibly when a late cancellation keeps nothing | CR-024 |
| SUG-F04-05 | idea | Let the arrow keys choose the deposit kind, as a radio group's arrows do | backlog |
| SUG-F04-06 | idea | Clear the Add a closure sheet's date after a closure is saved | backlog |
| SUG-F04-07 | idea | Show times in the History sheet on the 24-hour clock, as the Hours tab does | backlog |

### SUG-F04-01 Show each version's values, not only the rule names

- **Observed:** Settings, Booking rules, History lists "Version 4 · Changed: deposit". Nothing on
  the screen says what the deposit was before or after. The API returns `changedFields` only, and the
  public read serves only the current version.
- **Why it matters:** The owner. A customer disputes a kept deposit: "it said 10% when I booked". The
  version id is kept on the booking (F07), but the owner cannot read that version's terms anywhere.
- **Instead:** Each history row expands to the version's own summary sentences (the same `ruText`
  composer) or a before → after line per changed rule. This needs a read of a version by id for
  settings users; `BookingPolicies.version(id)` already exists inside the API.
- **Evidence:** screenshot `rules-history-1280.png` (QA scratchpad, from the hands-on walk). Fresha
  keeps a policy per appointment and shows it on the appointment.

### SUG-F04-02 Keep the selected Settings tab in view on phones

- **Observed:** At 360 px the tab rail shows "Profile", "Hours & closures" and then "Booking r",
  cut by the screen edge. "Branding" is off-screen with no hint that the rail scrolls. The
  prototype's rail does the same, so this is not a defect; it gets worse as F18 adds Business and
  Branches (six tabs).
- **Why it matters:** An owner setting up on a phone does not know Branding exists, and on a deep
  link cannot see which tab is open.
- **Instead:** Scroll the selected tab into view on load, and fade the rail's edge (or show a
  chevron) when more tabs are off-screen.
- **Evidence:** `rules-notsaved-360.png` against `prototype-rules-360.png`. TC-F04-S0x width cases
  now prove each tab is reachable by scrolling the rail.

### SUG-F04-03 No "The deposit" retention without a deposit

- **Observed:** With No deposit chosen, "A later cancellation keeps" and "A no-show keeps" still
  offer "The deposit". The summary then says "nothing is kept", correctly, as the prototype does.
- **Why it matters:** The owner thinks no-shows are covered when they are not. Fewer no-shows is
  why salons pay for this product (`docs/qa/review-context.md`).
- **Instead:** When there is no deposit, offer Nothing / A share of the price, or show a note under
  the field that nothing is kept without a deposit.
- **Evidence:** TC-F04-S04-018 covers the summary's sentence. Fresha ties its no-show fee to a
  percentage of the price, separate from the deposit.

### SUG-F04-04 Free cancellation when nothing is kept

- **Observed:** With a late cancellation keeping nothing (and a deposit), the summary reads
  "Free cancellation until 48 hours before your appointment. After that, nothing is kept."
- **Why it matters:** The customer, at the point of payment. The window suggests a charge after it,
  and the next sentence says there is none.
- **Instead:** When a late cancellation keeps nothing, say "Free cancellation at any time." and hide
  the window, or disable the window select.
- **Evidence:** the prototype's `ruText`. The behaviour matches it, so this would be a change request.

### SUG-F04-05 Arrow keys choose the deposit kind

- **Observed:** The deposit chips are a `radiogroup`. The arrows move focus, and Space or Enter is
  needed to choose. The ARIA radio-group pattern selects as focus moves.
- **Why it matters:** Keyboard and screen-reader users expect radio behaviour. It is not a WCAG
  failure, and AC-10 holds.
- **Instead:** Either have the selection follow focus, or expose the chips as toggle buttons
  (`aria-pressed`, as the prototype draws them).

### SUG-F04-06 Clear the closure sheet after a save (observed in the S03 dry run)

- **Observed:** After a closure is saved, + Add closure opens the sheet with the last chosen date
  still set. Saving it again is refused as a second closure on that date, which is safe but
  puzzling.
- **Instead:** Open the sheet empty, or on the next open day.

### SUG-F04-07 One clock on the Settings screens

- **Observed:** The History sheet shows "Nov 1, 2026, 2:23 PM", while the Hours tab uses 24-hour
  selects (S03's NFR-13 choice).
- **Instead:** Use the same 24-hour time in the shared `formatInstant` for the workspace, or record
  that choice in the design standards.

## Compared with other products

| Aspect of this flow | Ours | Others | Better for our users |
| --- | --- | --- | --- |
| Deposit: percentage or fixed, with a cap | Percentage with a cap, or a fixed amount; per-service override arrives with F05 | Fresha: percentage or fixed, per-service custom deposit ([Set up payment policies](https://www.fresha.com/help-center/knowledge-base/payments/615-set-up-payment-policies)) | Ours: the cap protects customers on expensive bridal services |
| Late cancellation and no-show | One window; late cancellation keeps nothing, the deposit or a share; no-show keeps the deposit or a share | Fresha: "Charge when cancelling less than" window plus a no-show fee as a percentage ([Charge no-show and cancellation fees](https://www.fresha.com/help-center/knowledge-base/payments/617-charge-no-show-and-cancellation-fees)) | Same |
| What the customer is told | A plain summary composed from the rules, previewed live by the owner | Fresha and Booksy show a policy text the business writes ([Booksy: set up my policies](https://support.booksy.com/hc/en-us/articles/33391748628626-How-to-set-up-my-policies)) | Ours: always matches the rules applied |
| Slot interval, notice, horizon | Salon-wide in Settings | Booksy sets the booking interval per service ([service settings](https://support.booksy.com/hc/en-gb/articles/21028092250258-How-do-I-customise-my-service-settings)) | Ours is simpler for a small salon; per-service may be wanted later (idea, not raised) |
| Rescheduling limits | How many times, and up to how long before | Booksy: a rescheduling window in Booking Settings | Ours: the count limit is extra protection |
| Changing a policy | A new version; existing bookings keep theirs | Fresha: a policy applies to bookings made under it | Same; ours has a visible history |

Public help pages only, read 2026-11-01. No account was created.

## Hands-on check and design comparison

Walked as a new salon's Owner at 1280 and 360 on 2026-11-01, build 28e086d: Booking rules not
saved yet, saved, the History sheet, and the Profile, Hours and Branding tabs.

The Booking rules tab was compared with the prototype (`setRules`, opened with `S.stab='rules'`)
at 360 and 1280. Layout, labels, choices, chips, the per-service note and the summary card match.
The summary is above the form under 900 px and on the right from 900 px.

Accepted differences, recorded at close: a History button; this stage's "save at once" intro;
shadcn Selects; Save disabled until something changes. The ink accents are correct: an
operator-provisioned salon has the neutral `#111827` brand. On alpha's pink and gamma's light
brand, the selected chip and Save reach 4.5:1 (TC-F04-S04-026).

Screenshots are in the QA scratchpad and are not committed.

Flakiness: the developer's gate `BrandingSettings.a11y.test.tsx` is not a QA run and was not rerun
here. Across three feature runs and direct reruns, QA's own browser cases showed timing faults that
were all in the tests, and all are fixed:
- two keyboard races (a Select arrow; Space pressed before Radix's roving focus moved on the deposit
  chips and the swatches);
- an axe scan that caught the "Draft saved" Sonner toast mid-fade and reported its contrast.
Every scan now waits for a settled screen. The product itself never failed. The toast's fade-in is
the likely cause of the developer's one-off failure of 8 Branding save-flow cases in the first a11y
run (QA did not rerun that gate, so this is not confirmed). The developer could wait for the toast
to finish appearing before scanning.

## Runs

All runs were on a fresh database (`f04qa_verify`) against product build 28e086d (the API's
`/actuator/info` names it). The product code did not change between runs; only QA's tests did.
- Run 1, `qa/reports/20261101-141721-F04-b4138f708e6a` (tests 0bf2cc6): 9 of 11 entries passed.
  The two failures, TC-F04-S01-022 (a moved message key) and TC-F04-S04-026 (a roving-focus race),
  were test defects.
- Run 2, `qa/reports/20261101-142509-F04-bcd1c63e3536` (tests 3f27f98): 10 of 11 entries passed.
  TC-F04-S02-034 failed on the same race, a test defect. While this run was still going, QA edited
  that test file after its entry had finished; no later entry used the file.
- Run 3, `qa/reports/20261101-143156-F04-fd710b8c3130` (tests 70d452a): 11 of 11 entries, 120 cases,
  0 failed. This is the recorded result.
- Targeted reruns of the repaired cases used Playwright `--grep`, because `sdlc run` selects only
  whole entries. They are not recorded runs.

### Re-verification after the full pass (2026-11-01, revision 93821ab)

PR #18 CI failed on head 1469dbb because a developer unit test,
`apps/web/src/surfaces/workspace/HoursSettings.test.tsx`, ran past Vitest's 5 s timeout. The fix
(3ba611c, merged as 7c28918) changes only that file. `git diff 70d452a 93821ab` over the certified
product paths lists nothing else: no application code, contract, QA automation or spec. No
application file imports the test, so the built product the F04 cases run against is unchanged.
The S03 Hours screen cases exercise the Hours screen component, which did not change. Under decision 069 no QA case
re-runs, and none was run. The changed test was run once at 93821ab as a sanity check (14 of 14
passed, 4.3 s). That is the developer's gate and records no QA result. Reading the diff, the
developer's assertions are kept. The holiday and closure tests now serve an all-closed week, the
open-week block allows 15 s, the accept-then-save test is split in two, and the conflict test makes
its change by toggling Monday instead of picking a time. Run 3 above stays the recorded full pass
(`evidence.last_run`, `evidence.full_pass_revision`), and `integration.qa_revision` moves to 93821ab
so `pr-check` accepts the unchanged product.

## Notes for the lead (documents)

- `docs/operations/git-delivery.md` ("never in place of its `integration.qa_revision`") and
  `feature-verify` in `salon-sdlc` lifecycle.md ("A targeted run never replaces the full pass's
  recorded revision") conflict with `pr-check`. `pr-check` compares only `integration.qa_revision`
  with HEAD, so a targeted re-verification can only clear it by moving that field. At the lead's
  instruction it was moved, and the full pass's revision is kept in `evidence.full_pass_revision`.
  The documents or the tool should say which field holds which revision.

- The F04-S04 handoff lists the new test ids but no case ids. The cases are TC-F04-S04-001 to 026
  in `qa/api/F04/S04-rules.spec.ts` and `qa/web/F04/S04-rules-screens.spec.ts`.
- Only currencies with two fraction digits can be provisioned (`ProvisioningRegions`), so decision
  091's "scaled by fraction digits" can only be shown for LKR and USD on the running product.
  TC-F04-S04-015 uses USD.
- The conflict title moved to a shared ICU message (`settings.notices.conflictTitle`).

## Bugs filed from this review

- None.
