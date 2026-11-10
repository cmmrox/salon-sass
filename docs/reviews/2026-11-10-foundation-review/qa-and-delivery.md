# Independent review: QA automation, lifecycle tooling, CI and delivery history

Repository: `the repository root`, `main` at `e5ccf3aa` (2026-11-10).
Read-only review. Nothing was run except `git`, `gh` (read), `qa/scripts/sdlc validate|status|run --dry-run`.

How to read the numbers:

- Times are commit timestamps (+05:30) and `qa/reports/*/summary.json` (UTC). They measure elapsed time, not agent effort.
- Commit classes come from the Conventional Commit prefix and the paths each commit touches.
- Cause classes for fix commits are a keyword classification of subjects, so they are approximate.
- "Era A" is F01–F03 (10-12 to 10-20), "Era B" the CR-020/modular refactor (10-21 to 10-31), "Era C" F04–F07 (11-01 to 11-10).

---

## Part A. Delivery history

### A1. Totals

- 1,093 commits (924 non-merge) on 16 working days between 2026-10-12 and 2026-11-10. 26 PRs, 25 merged.
- 38 bug files: 27 major, 11 minor, no blocker. 27 change requests. 112 decisions.
- 144 recorded QA runs: 82 pass, 58 fail, 2 interrupted, 1 abandoned as "running". 14.3 hours of runner time. 117 of 1,175 entry executions failed.
- 81 CI runs: 61 success, 16 cancelled, 4 failure.

### A2. Per feature

| | F01 | F02 | F03 | F04 | F05 | F06 | F07 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Stages / acceptance criteria | 5 / 66 | 4 / 38 | 4 / 58 | 4 / 45 | 3 / 46 | 3 / 52 | 3 / 39 |
| First commit to first PR merge | 38.4 h | 19.3 h | 117.8 h | 14.8 h | 6.8 h | 171.6 h (about 19 h active) | 16.3 h (8.6 h to PR) |
| Commits before / after first merge | 123 / 76 | 136 / 128 | 111 / 5 | 65 / 2 | 53 / 18 | 47 / 11 | 82 / 0 |
| feat / fix / test | 24 / 28 / 49 | 9 / 35 / 81 | 13 / 21 / 38 | 9 / 22 / 14 | 9 / 15 / 22 | 13 / 4 / 15 | 8 / 16 / 36 |
| docs / chore / merge | 28 / 51 / 16 | 38 / 80 / 20 | 15 / 11 / 17 | 6 / 8 / 7 | 7 / 10 / 8 | 6 / 10 / 10 | 7 / 6 / 8 |
| Bugs filed (major / minor) | 9 (8 / 1) | 15 (11 / 4) | 8 (5 / 3) | 0 | 1 (0 / 1) | 0 | 5 (3 / 2) |
| Bugs found after merge | 3 | 2 | 2 | 0 | 0 | 0 | 0 |
| Automated PR review findings | 4 (PR 3) | 3 (PR 5, 11) | 5 (PR 13, unanswered) | 3 (PR 18) | 3 (PR 19) | 0 | 4 (PR 26) |
| First CI run on the feature PR | pass | pass | pass | fail | fail | fail | pass |
| Times closed / later re-verifications | 3 | 3 | 1 | 1 / 2 | 1 / 2 | 1 / 2 | 2 / 1 |
| Whole-feature QA runs until first pass | 5+ | 4+ | 6 (5 failed) | 3 (2 failed) | 2 (1 failed) | 2 (1 failed) | 1 failed, then stage scope |
| Change requests against it | 001–005, shares 007, 010, 016, 017 | 006, 012–015, 018, shares 007, 010, 016, 017 | 019 (004 targets it) | 024 | 025 (PR 20) | 026 (PR 24, 25) | 027 |

Notes on the table:

- **F06's 171.6 h** includes a six-day stop (11-02 06:47 to 11-08 13:51). It waited for the "fixed local accounts and QA instance" refactor (PR 21) and process fixes (PR 22). That refactor exists because QA's identity fixture kept deleting the owner's manual-test accounts.
- **F07's 8.6 h to PR** splits as: planning 0.8 h, S01 (API) 1.4 h, S02 (API) 1.6 h, S03 (web) 3.6 h, feature QA with one bug loop 1.1 h. The PR-review loop then added 1.3 h active and 5.8 h idle.
- **The modular refactor** (PR 15, scoped "F23" in commits) is 125 commits, 47,616 added and 23,678 deleted lines in 1,095 files, over two days.
- **Feature PRs are single PRs of 31k to 51k added lines** (F04 51,235 in 449 files; F05 41,497; F06 44,252; F07 31,307).

### A3. Where the defects were found

| Found by | Era A (F01–F03) | Era C (F04–F07) |
| --- | --- | --- |
| QA automation case | 26 bugs | 1 bug (BUG-F07-001) |
| Automated PR review (Codex) | 4 bugs filed (F02-006, -007, -013; F03-007 first flagged there), 8 more findings | 4 bugs filed (F07-002..005) plus 6 findings fixed without a bug file (F04 ×3, F05 ×3) |
| QA exploratory | 1 (F02-014) | 1 (F05-001) |
| Developer on the running product | 1 (F03-008, found during F06) | many, fixed in-stage as `fix(...)` commits before handoff |
| CI | 0 | 3 first-run failures, all `pnpm -r test` timing on the slower runner (F04, F05, F06) |

In Era C QA wrote 295 API tests and 154 browser tests (about 18,800 lines of specs) for F04–F07. That automation found one product defect. The automated PR review found ten, all accepted and fixed, and all after the feature had been closed.

### A4. Recurring defect classes and the cheapest point to catch each

| Class | Evidence | Cheapest catch |
| --- | --- | --- |
| **Web client stale state and late answers** (an answer applied after the form, sheet or page moved on; lists and navigation not refreshed; form not reset) | BUG-F02-003, -006, -007; F03-005; F07-001, -002, -003, -004; PR 18 finding 3; PR 19 finding 1; commit `45d83282`. About 11 defects, 7 found only by the PR review. 12 of 57 Era C fix commits are this class | Stage build: one shared request-sequencing helper and a "late answer" unit test per data hook. Otherwise a diff review at stage close |
| **Design fidelity and shadcn/ui conformance** | Owner CRs 007, 012, 013, 016, 017 (PRs 5, 10, 11, 12, about 18k added lines; F01 and F02 closed three times each). PR 18 P1 (hand-built rows instead of Item). 6 Era C fix commits "as the prototype does", one after handoff (`1b495b59`) | Stage build: a lint rule. `apps/web/eslint.config.mjs` has no rule against raw controls or CSS Modules; the rule lives only in AGENTS.md and one F02 QA spec |
| **Contract drift** (nullability, status codes) | BUG-F02-001, -005; F03-001 | Closed. Since F07 the developer's integration tests validate every response against `openapi.json`. No recurrence in Era C |
| **Accessibility contrast and roles** | BUG-F01-003, F02-013, F03-003, F03-004 | Mostly closed by the `test:a11y` developer gate since F04. That gate is not in CI (see B4) |
| **Spec reading and missed requirements** | CR-010 (customer website not wanted: PR 7, 2,094 added and 1,357 deleted lines); PR 18 finding 2 (holiday years); PR 19 finding 2 and BUG-F05-001 (currency); BUG-F07-005 | Stage close. BUG-F07-005 was written down by the developer in the F07-S01 handoff ("Most booked ... future included", note 11). Decision 109 accepted choices (a) to (i) and did not rule on it |
| **Resilience under a silent dependency** | BUG-F02-012, F02-015, F03-002 | Developer integration tests (`DatabaseFrozenIntegrationTest` now exists) |
| **Infrastructure, deploy, image CVEs** | BUG-F01-001, -002, -005 to -009; F03-007. Five surfaced only on 10-30, when the regression and release suites ran for the first time in 13 days | CI: image scan and production-like stack on a schedule, not only on request |
| **Tenant isolation** | No escaped defect | The current double proof (developer RLS-alone and filter-alone tests, plus QA isolation cases) works |
| **Test flakiness and timing** | Three CI failures on unit-test timeouts; TC-F03-S02-006 and TC-F04-S03-032 intermittent (PR 21 body); TC-F07-S01-026 raced the hold-expiry job; smoke failed then passed two minutes apart on 11-09 | The single global test clock and background jobs are the common root (B2) |
| **Environment** | QA wiped the owner's accounts (PR 21, five days); a pattern kill took down Docker on 11-02; evidence prose records PIDs and build times by hand | One redeploy command and a build-revision check (B5) |

### A5. Change requests by cause

| Cause | CRs | Count |
| --- | --- | --- |
| QA usability suggestions | 004, 006, 014, 015, 018, 019, 024, 025, 026, 027 | 10 |
| Owner: UI design drift or stack conformance | 007, 012, 013, 016, 017 | 5 |
| Process | 008, 009, 011 | 3 |
| QA review: spec gap | 001, 002, 003 | 3 |
| Owner: architecture | 021, 022 | 2 |
| Owner: scope growth (new prototype) | 020, 023 | 2 |
| Owner: missed requirement (audience) | 010 | 1 |
| Owner: environment scope | 005 | 1 |

- Eight CRs are still `proposed` with no decision: 004, 006, 014, 015, 018, 019, 024, 027. The oldest is from 10-13.
- The two QA-suggestion CRs that were implemented (025, 026) each became a separate follow-up PR with its own CI and re-verification (PRs 20, 24, 25).

### A6. Process churn

| Commits (non-merge) | Era A | Era B | Era C |
| --- | --- | --- | --- |
| Total | 490 | 164 | 270 |
| Touch application code or developer tests | 107 (22%) | 80 (49%) | 110 (41%) |
| QA automation (with records) | 181 (37%) | 42 (26%) | 110 (41%) |
| Records, documents, process only | 185 (38%) | 34 (21%) | 46 (17%) |
| Infrastructure | 17 (3%) | 8 (5%) | 4 (1%) |

- The skills changed in 15 commits. Six rewrites landed in the first four days (10-12 to 10-15: "test a feature once", feature QA and owner PRs, design comparison, progress and evidence, reuse of passed QA, streamlining). One large simplification followed on 10-30 (`f856a3a4`: 636 added, 1,173 deleted lines), then two small edits.
- `qa/scripts` changed in 23 commits. Eleven of those were in the first four days, building and then repairing the receipt and scoped-evidence machinery that decision 068 later retired.
- Records are heavy. Each feature carries about 7k–12k words of specs and 9k–15k words of `stage.yaml`. The F07-S01 handoff alone is about 150 lines and restates endpoint shapes that `openapi.json` already holds. `docs/decisions/DECISION_LOG.md` is 14,599 words in 112 single-line table rows.

### A7. Rerun and waste signals

- **The first whole-feature run failed in 4 of 4 Era C features.** Three of those were test defects only (F04 two runs, F05, F06). QA writes cases from the spec before the build, then repairs them: 13 "repair / fill the build's names / align" commits in Era C.
- **Eight developer fix-up commits in Era C** came from hand-kept lists or formatting that fail only at the end of a gate: the migration list (V27, V30, V33), the problem-code list (twice), the contract fixture (twice), and formatting (twice). Each costs another gate cycle. `./mvnw -q verify` takes 635 s locally (F07-S01 handoff: 2,181 tests in 238 classes).
- **Regression has passed once in four runs.** Its duration grew from 29 min (19 entries) to 36–64 min (47–48) to 78.5 min (66 entries, 11-08).
- **Evidence is never pruned:** `qa/reports` 504 MB, `.local/archive` 631 MB, `.git/sdlc-evidence` 614 MB.

---

## Part B. Tooling

### B1. Lifecycle tooling (`qa/scripts`)

Size: 2,119 lines of Python and shell, with 117 unit tests (1,322 lines) that run in CI as `sdlc selftest`. `validate` takes 0.47 s and currently reports "OK 216 requirements, 22 features, 45 stages, 3 skills".

**What `validate` checks.** Ids and folder names; status vocabularies; requirement ownership (each Release 1 requirement owned once); stage dependencies and cycles; spec headings in order with criteria and "Read first" documents that exist; `verification.commands` equal to the spec's bash block; every criterion named by a case title; every case automated, manual or deferred with a reason; no open blocker or major bug behind a pass; a `review.md` behind a pass; the shape of the integration record; CR references; skill frontmatter and mirror; the docs index; generated files current.

**What it does not enforce.**

| Gap | Where | Consequence |
| --- | --- | --- |
| `validate` is not in CI; there is no git hook | `.github/workflows/ci.yml` runs only `sdlc selftest` (line 81) | "Run validate before committing" depends on the agent |
| A case counts as coverage if its id appears in a title or a comment | `qa/scripts/sdlc_lib/model.py:19-22` | A `// TC-... AC-2:` comment, a `test.skip(...)`, a `test.fixme(...)` or an empty test body all count. Verified against the regex in memory. Nothing in the repository uses this today (0 skips, 0 comment-only ids) |
| A QA pass is a string, not a checked fact | `validate.py:252-254` | `evidence.last_run` need not exist, be a pass, or match `qa_revision`. Reports are git-ignored, so CI could not check it anyway |
| The runner records `HEAD` but not a dirty tree | `runner.py:152-158` | F07's evidence needs a prose paragraph explaining that repaired tests were uncommitted during the counted run |
| Nothing checks the running build against the tested commit | `qa/shared/global-setup.ts:18-34` checks health only; `docs/operations/verification-evidence.md:38-40` says so | A pass can be recorded against a stale API or web build |
| `guard.py --diff` sees only uncommitted changes | `guard.py:127-137` | Once committed, a path violation is invisible. The edit hook covers only Edit and Write (`.claude/agents/salon-dev.md` frontmatter), not shell writes. CI does not check path ownership |
| Suite budgets are not enforced | `docs/qa/test-plan.md:192-197` | "Going over budget is a defect in the suite" is checked by nobody |
| No per-entry time limit | `runner.py:300-305` | A hung entry waits forever. One F01 run on 10-14 is still marked `running` |
| The narrowest runnable scope is a stage | `__main__.py:109-115` | Decision 069 asks for only failed and affected checks, but there is no `--entry`, `--case` or rerun-failed option. Re-verifying one F07-S03 case costs the whole stage, 6.4–7.2 min |
| Tool vocabulary mismatch | `schema.py:31` omits `command`; `runner.py:71` handles it | Dead branch, or a validator that rejects a tool the runner supports |
| Automated review findings have no state | lifecycle | PR 13's five findings were never answered (B6) |

### B2. QA automation

**Size.** 725 Playwright tests in 102 spec files (26,220 lines), 9,870 lines of shared helpers, 35 shell and node checks (3,344 lines), 6 k6 scripts.

| | F01 | F02 | F03 | F04 | F05 | F06 | F07 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| API tests | 43 | 61 | 79 | 77 | 66 | 100 | 52 |
| Browser tests | 34 | 31 | 28 | 43 | 34 | 53 | 24 |
| Regression entries | 11 | 15 | 21 | 12 | 10 | 21 | 17 |
| Smoke entries | 3 | 1 | 3 | 1 | 1 | 1 | 1 |
| Release entries | 6 | 1 | 0 | 0 | 0 | 0 | 0 |

**Budget against reality.**

| Suite | Budget (`test-plan.md:192-194`) | Measured |
| --- | --- | --- |
| Smoke, 11 entries | under 5 min | 2.1–4.0 min. Within budget |
| Regression, 107 entries | under 20 min, "fully parallel across workers" | 78.5 min at 66 entries, serial. About two hours projected at 107 entries. Last run 11-08, failed 12 of 66. Not run since F06 and F07 merged |
| Release, 7 entries | none | Run once (10-30), failed 2 of 7. It holds nothing from F03–F07; F07's availability load case TC-F07-S02-018 is deferred and unregistered |

**Why it is serial.**

- `qa/playwright.config.ts:24-25` sets `workers: 1` and `fullyParallel: false`.
- `runner.py:284` runs one Playwright process per entry, one after another.
- The cause is in the product's test hooks. There is one application clock for every salon ("tests that set it cannot run in parallel", `test-plan.md:96`), 68 of 102 spec files move it, and some tests stop and start services.

**Regression is red on main and nobody is scheduled to fix it.**

- PR 21's body records 10 stale expectations from before F04/F05 (SalonBrand fields, navigation entries, permission lists, holiday routes) "to be brought up to date in a separate QA change". No commit since has touched the F01–F03 tests.
- The same PR records two intermittent failures with no bug file or backlog entry: TC-F03-S02-006 (an invoice exists one second early) and TC-F04-S03-032 (HTTP 500 on staff sign-in after a clock move).

**Assertion quality is high.** I read ten specs across F01–F07. They assert behaviour and state, not only status codes. Examples from `qa/api/F07/S01-isolation.spec.ts`:

- It first proves the other salon has data to leak.
- It compares the refusal with an unknown id's answer byte for byte.
- It snapshots the other salon before and after.
- It checks row-level security at the database, and the exclusion constraint's definition.

QA reviews also record that new critical checks were made to fail first.

**Weak spots.**

- **Hard waits are rare:** 4 `waitForTimeout` (three at `qa/web/F05/S01-services-screens.spec.ts:715,738,756`; one deliberate 5 s observation at `qa/web/F07/S03-access-and-quality.spec.ts:121`) and one raw 1.5 s sleep (`qa/api/F06/S01-seats-and-races.spec.ts:338`).
- **Timeouts are loose:** 165 per-test overrides; 80 tests allow 180–300 s, one allows 30 min. Slow tests are accepted, not fixed. `F05-S01 catalogue-api` took 742 s in the last regression.
- **Triage is poor:** `trace: 'off'`, screenshot only (`playwright.config.ts:30-33`). Tests are long multi-step journeys. F02 and F03 specs are written many statements to a line (339 lines over 200 characters), F04–F07 have 410 such lines.
- **One browser:** Desktop Chrome only. Narrow widths are tested by resizing.
- **QA code is not type-checked or linted in CI.** `qa/` is outside the pnpm workspace (`pnpm-workspace.yaml:1-5`); its `typecheck` script runs nowhere.

**Duplicated coverage.** The developer's API suite is 1,386 test methods (2,181 executions) in 370 files, 51,805 lines against 58,844 lines of main code. Per module the QA API specs mirror it almost file for file at the same layer (HTTP against PostgreSQL):

| Developer (`apps/api/src/test/.../booking`) | QA (`qa/api/F07`) |
| --- | --- |
| BookingAccess, BookingRulesAccess | S01-access |
| BookingConcurrency, BookingOverlap | S01-overlaps, S01-guards |
| BookingIsolation, RowLevelSecurityAlone, HibernateFilterAlone | S01-isolation |
| BookingReads, BookingStates, Holds | S01-reads, S01-states, S01-holds |
| Availability, NoPreferenceHolds, HoldAndAssignRules | S02-availability, S02-no-preference, S02-rules |

The developer also does a scripted walk-through of every criterion on the running product (F07-S01: a 22 kB throwaway `walk.py`, 52 checks). So each API criterion is proved three times. In Era C the QA API layer found no product defect (A3).

### B3. Developer gates

- **Per stage:** `./mvnw -q verify` (API stages), `pnpm -r lint`, `typecheck`, `test`, `test:a11y` (web stages since F04), web build, `contracts:check`. All run on committed source and are recorded with logs in `handoff.notes`, so the lead closes without rerunning. That reuse works.
- **Cost:** `mvnw verify` is 635 s locally and 1,129 s in CI, with no parallel forks configured in `apps/api/pom.xml`. `pnpm -r test` is 94 s in CI (1,303 web tests).
- **Spotless runs at the end:** the check is bound to Maven's default phase (`pom.xml:337-343`), so a formatting slip is reported after the full test run (`b3ba03ef`, `258fb705`).
- **Ungated tools:** `format:check` and `knip` exist (`package.json:13-15`) and `docs/engineering/web.md:114` says knip fails the build. Neither is in any gate or in CI.

### B4. CI (`.github/workflows/ci.yml`)

- **Shape:** one serial job on every PR and every push to `main`.
- **Duration** grew from 3.4 min to 24 min. `mvnw verify` is 1,129 s of the 1,440 s (79%) and runs first.
- **Slow feedback:** the three Era C failures were all in `pnpm -r test`, the fifth step, so each was reported after 12–19 minutes.
- **Runs twice:** the full job runs on the PR and again on the push to `main`. Branch protection is strict (up to date with `main`), requires the "Verification commands" check, requires conversation resolution, and applies to admins.
- **Present:** build, lint, type-check, unit and integration tests, contract check, image build, workflow lint, tooling tests.
- **Absent:**
  - `test:a11y`. Twelve stages list it as a gate. `qa/checks/F01/S01-ci-workflow.sh` compares CI only with F01's stage list, so the drift is unseen.
  - `sdlc validate`.
  - Any QA suite. Smoke runs only when an agent runs it locally.
  - Dependency audit and secret scanning.
  - `format:check`, `knip`, and QA type-check.
- **Image scanning** runs only after merge, in `delivery.yml`. BUG-F01-006 and -008 were image CVEs.
- **Automated review timing:** the Codex review is the only code review. It runs on the feature PR, after feature QA has passed and the feature is closed.

### B5. Local environment

- **Good:** `infra/scripts/local-up.sh` is one idempotent command with a lock, PID files and prerequisite checks. Since decision 105 there is a separate QA instance (ports 3200, 8190, 5540). `env.ts`, `checks.sh` and the runner all refuse the default instance.
- **Missing:** a redeploy at a given commit. `start_api` and `start_web` (`infra/scripts/lib/common.sh:630-680`) leave a running service alone. To test a new commit an agent must stop the API and web and start them again, then check `/api/v1/meta` by hand. F07's evidence records PIDs and build times in prose for this reason.
- **Cost per restart:** every start rebuilds the jar (`mvnw -DskipTests package`) and the Next.js build. The QA instance was rebuilt at least three times during F07.
- **Known traps** (from history and the owner's notes): killing by pattern; login-shell pnpm; the Rosetta shell; QA fixtures on the owner's database (fixed by PR 21).

### B6. Unanswered review findings (a quality gap found during this review)

The owner merged PR 13 (F03) five seconds before the automated review arrived. Its five findings, three of them P1, have no reply, no bug file and no backlog entry.

- **One resurfaced.** The bootstrap `RESOLVED_JAVA_HOME` finding became BUG-F03-007 ten days later.
- **Deleted salon keeps personal data (P1).** The tombstone update still changes only lifecycle fields: `apps/api/src/main/java/com/salonplatform/platform/repository/SalonLifecycleStore.java:123-127`. I did not check this against the retention requirement.
- **Possible multiple charges (P1).** Charge attempts are unique per invoice and occasion (`.../repository/InvoiceStore.java:137-146`). That matches the review's description of several outstanding attempts with different idempotency keys. I did not trace the caller for a guard.

Both need the lead's triage.

---

## Part C. Recommendations, ranked

Effort: S is under half a day, M is one to two days, L is more.

| # | Change | Evidence | Effort |
| --- | --- | --- | --- |
| 1 | **Move the independent code review before feature QA.** Open the feature PR as a draft when the first stage merges, so the automated review and CI run on every stage push. Or add a diff-review step at stage close. Give the reviewer the three recurring targets: late answers, list and page state after a change, boundaries | 10 of 10 review findings in Era C were accepted, all after feature close. F04, F05 and F07 were each re-verified or re-closed for them. QA automation found 1 of the 11 Era C defects. 3 of 4 feature PRs failed their first CI on unit-test timing that a per-stage CI run would have shown hours earlier | S |
| 2 | **Fix the stale-state class at its source.** One shared helper for "ignore or release an answer that is no longer current" in the web data hooks, and a required unit test per hook: answer after change, after close, after unmount, and an emptied page | About 11 defects across F02, F03, F04, F05 and F07; 7 escaped to PR review; 12 of 57 Era C fix commits | M |
| 3 | **Triage PR 13's unanswered findings now, and add a rule that a PR is not merged before its review has arrived and each finding has a recorded answer** | B6: two P1 findings appear still present; one already cost a bug ten days later | S |
| 4 | **Repair regression and keep it green.** (a) Bring the 10 stale F01–F03 expectations up to date and file bugs for TC-F03-S02-006 and TC-F04-S03-032. (b) Run regression at every feature close, in the background while the PR waits (PR waits were 1.8–7.7 h). (c) Shard entries across several QA instances as a first step; make the test clock per salon to allow real parallelism later | Red since F04/F05; not run since 11-08; 78.5 min at 66 entries against a 20-min budget and a plan that says "fully parallel"; 5 post-merge bugs surfaced only on 10-30 after 13 days without a run | (a) S, (b) S, (c) M then L |
| 5 | **Restructure CI.** Parallel jobs with the fast ones first (lint, type-check, web tests, contract) and Maven tests sharded by module. Add `test:a11y`, `sdlc validate`, `format:check`, `knip`, QA type-check and a dependency audit. Skip the duplicate full run on push to `main` when the merge commit's tree was already tested. Add smoke against a started stack | 24 min, 79% of it one serial step; failures reported after 12–19 min; `test:a11y` is a gate in 12 stages and absent from CI; no audit or secret scan | M |
| 6 | **Remove the gate's self-inflicted failures.** Derive the migration list and problem-code list in tests from the sources. Bind Spotless to the `validate` phase or format before commit. Allow module-scoped Maven tests plus architecture and contract tests while iterating in a stage, with the full `verify` once on the final commit | 8 fix-up commits in Era C, each a repeat of a 635 s run | S |
| 7 | **Cut the QA API layer to what independence buys.** Keep tenant isolation, the role matrix, concurrency invariants, contract and journeys. For other criteria QA audits the developer's criterion-to-test map and spot-checks by mutation. Spend the time on browser state and timing exploration. This trades some independence for speed, so it is the owner's decision | 295 QA API tests in Era C mirror developer integration classes one for one and found no product defect; 13 test-repair commits; first feature run failed on test defects in 3 of 4 features | S to decide, saves about a third of QA authoring per feature |
| 8 | **Make shadcn/ui and prototype conformance a lint rule and a walk-through artefact.** ESLint `no-restricted-syntax` for raw `button`, `input`, `select`, `textarea`, `table` and list rows outside `components/ui`, and for CSS Module imports. Screenshot comparison with the prototype at 360 and 1280 in the developer walk-through | 5 owner CRs and about 18k lines of rework in Era A; PR 18 P1; 6 "as the prototype does" fix commits in Era C; no rule in `apps/web/eslint.config.mjs` | S for lint, M for screenshots |
| 9 | **One `qa-redeploy` command and a build check.** Stop by recorded PID, build at `HEAD`, start, and assert `/api/v1/meta` equals `HEAD`. `global-setup.ts` refuses a build that does not match. The runner records a dirty tree | Prose evidence of PIDs and build times; `verification-evidence.md:38-40`; the 11-02 pattern-kill incident | S |
| 10 | **Runner options the rules already assume:** `--entry` or `--case` and `--rerun-failed <report>` (decision 069); a per-entry time limit; a budget warning in the summary; commit `summary.json` so `validate` and CI can check that a pass exists at `qa_revision` | B1; one case re-verified costs a whole stage run | S |
| 11 | **Close the coverage loophole.** Count a case only from a real `test(` call, never from a comment, `skip` or `fixme`. Flag titles that claim more than two or three criteria | `model.py:19-22`; latent today | S |
| 12 | **Decide QA suggestions before the feature closes.** The lead brings QA's "should" items to the owner while the PR is open, so approved ones ride in the feature PR | 8 CRs undecided for up to four weeks; CR-025 and CR-026 cost three extra PRs | S |
| 13 | **Rule on every item of a handoff's "differences and choices" list at stage close** | BUG-F07-005 was item 11 of the F07-S01 list; decision 109 ruled on nine other items | S |
| 14 | **Lighten the records.** Handoff as a table of criterion, test and result; endpoint shapes by pointer to `openapi.json`; split the decision log by area | A6: 9k–15k words of `stage.yaml` per feature; 17% of Era C commits are records only | S |

### Quality gaps that no gate catches today

1. Regression is red on `main` and has not run since F06 and F07 merged. Two intermittent failures have no bug.
2. Two P1 review findings on F03 (personal data kept after deletion; possible multiple charges) were never triaged.
3. BUG-F01-009 (rolling deploy drops requests) is `fixed` with `verified_on: null`. The release suite has never passed.
4. There are no load or production-like checks for F03–F07. F07's availability timing criterion is deferred.
5. No dependency audit or secret scan anywhere. Image CVEs are seen only after merge.
6. `test:a11y`, `knip`, `format:check` and QA type-checking are not in CI.
7. A QA pass can be recorded against a stale running build or with uncommitted test changes.
8. Path ownership is unchecked once a change is committed or made through the shell.
9. Coverage is claimed by titles; a skipped or commented case counts.
10. One browser engine, one viewport family.
11. Feature PRs of 31k–51k lines get no human-scale review. The automated review returns 3–5 findings every time, which looks like a cap on findings rather than everything it could find (my inference).
