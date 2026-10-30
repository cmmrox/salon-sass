# Lifecycle commands

The contract of every command: who runs it, what it reads, when it refuses, what it writes and the
status it leaves behind. Each section stands alone; read only the one you are running.

## Contents

- Status model and shared mechanics
- Refactors and upgrades
- `feature-new`
- `stage-plan`
- `stage-build`
- `stage-close`
- QA during the build
- `feature-verify` (and re-verifying fixes)
- `bug-fix`
- `feature-close`
- `feature-submit` and `feature-integrate`
- `stage-verify` (only when the owner asks)
- `change-request`
- `release-prepare`, `release-verify`, `release-cut`
- Where a bug is filed
- Rules across all commands

## Status model and shared mechanics

```
feature: backlog -> planned -> in-progress -> in-qa -> complete -> released
stage:   outlined -> planned -> in-progress -> built -> complete -> released
qa_status (a feature; a stage only when the owner asked for a stage pass): not-run | failing | blocked | pass
integration (feature.yaml): branch, pull_request, qa_revision, merged_commit, merged_at
```

A stage is `complete` when closed on the developer's gates; a feature is `complete` when QA passed
it. Only the owner's confirmed GitHub merge lets the next feature start. Deployment is separate.

**QA happens once, on the whole feature,** after every stage is closed. A full QA pass per stage
was tried and cost more than the defects it caught early. QA prepares and dry-runs its automation
during the build, so the feature pass starts with working tests.

`qa/scripts/sdlc status` prints whose turn it is:

| State | Turn |
| --- | --- |
| Stage `outlined` / `planned` or `in-progress` / `built` | Lead `stage-plan` / developer `stage-build` / lead `stage-close` |
| Every stage `complete`, feature `qa_status: not-run` | QA: `feature-verify` |
| `failing` with an `open` or `reopened` bug | Developer: `bug-fix <F>` |
| `failing` with every bug fixed | QA: `feature-verify` (re-verify) |
| `blocked` | Lead: remove what blocks QA (`blocked_reason`) |
| `pass` / `complete` without a PR / PR open | Lead: `feature-close` / `feature-submit` / owner merges, then `feature-integrate` |

**Which `qa_status` to set** at `feature-verify` (or `stage-verify`):

| Status | When |
| --- | --- |
| `pass` | Every acceptance criterion has a case; every case is automated, manual with a reason and `performed_on`, or deferred with a reason and what unblocks it; the last run passed on the code as it is now; no `blocker` or `major` bug is unresolved; `review.md` exists; `qa/scripts/sdlc validate` is clean |
| `failing` | Anything above is not yet true and the next step is the developer's or yours |
| `blocked` | This environment lacks something you cannot provide (an emulator, a tool, a credential). Set `blocked_reason` and tell the lead; never defer cases to get round it |

**Find a stage's folder from its id**, never by guessing a name:
`dirname "$(grep -rl "^id: F07-S02$" features/*/stages/*/stage.yaml)"`. Nothing found: refuse.

**After any edit to a `feature.yaml` or `stage.yaml`:** `qa/scripts/sdlc generate && qa/scripts/sdlc
validate`, fix every finding, and commit the generated files with the change.

**Verification commands** (`verification.commands`) are the developer's gates: each runs from the
repository root and must exit zero. Proof and its reuse: `docs/operations/verification-evidence.md`.

---

## Refactors and upgrades

Work that changes how the product is built and gives customers nothing new (architecture, code
organisation, a framework or dependency upgrade, tooling) is not a feature, has no stages and no QA
cases. The lead records the decision in `docs/decisions/DECISION_LOG.md` and updates the documents
it changes; the developer builds it on a `refactor/` (or `chore/`) branch and proves it with the
gates: the build, the architecture rules (each with a fixture showing it fails) and every existing
test with its assertions unchanged. The lead reviews the whole diff. Smoke runs; when delivered
behaviour could be affected, the lead also runs regression without waiting to be asked. One PR.

---

## `feature-new <F>`

**Role:** Lead. Turns a backlog feature into a planned one with its stages outlined. A brand-new
feature is created the same way, only from an approved change request.

**Reads:** `features/README.md`; the feature's `feature.yaml`; the text of its requirements
(`grep -A9 "id: BKG-04$" docs/brd/requirements.yaml`); the matching prototype screens; the roadmap
and architecture (`docs/README.md` says where).

**Refuse if:**
- The work gives customers nothing they can use. Ask "what can a salon, its staff, a customer or the
  operator do afterwards that they could not before?" If the answer is "nothing, by design", it is
  a refactor or upgrade (section above). The architecture rework of 2026-10 was planned as feature
  F23 with a full QA pass until the owner stopped it: QA was writing cases for behaviour the
  developer's gates already proved unchanged (decision 066).
- The status is not `backlog`.
- `qa/scripts/sdlc start-check <F>` fails: an earlier feature or a dependency is not merged into
  GitHub `main`. A recorded `started_early` owner exception may waive order, never a dependency.
- An earlier milestone is not released. Only the owner changes milestone order (`docs/ROADMAP.md`);
  record an early start in `started_early`.

**Do:**
1. Divide the feature into stages. A good stage can be started and shown working on its own, ends
   in something a person can use or call (a screen, an endpoint, a job with a visible result), is
   built in dependency order with the riskiest part early, and is worth planning on its own.
2. One folder per stage, `stages/S01-<slug>/`, with `stage.yaml` from `assets/stage.yaml`:
   `status: outlined`, title, sequence, surfaces, `depends_on`, its requirement ids. Every
   requirement of the feature belongs to a stage.
3. Write the feature `README.md` from `assets/feature-README.md`.
4. On the feature branch from updated `origin/main`, record `integration.branch`, run
   `start-check <F>`, set `status: planned`.
5. **Confirm the design first.** For a feature with screens, show the owner the reference screens it
   will follow and get their agreement before any UI stage is planned. Designs changed after build
   cost F01 and F02 about 40 hours of repair.
6. Show the owner the stage outline and wait for approval.

**Does not:** write a spec, code or a test.

---

## `stage-plan <F-S>`

**Role:** Lead. **Reads:** the feature README and yaml, this `stage.yaml`, its requirements, the
prototype screens it touches, the project documents for the areas it touches, `docs/qa/test-types.md`.

**Refuse if:** the status is not `outlined` (a planned, unstarted stage may be re-planned on the
owner's request; later changes go through `change-request`); the feature is `backlog`; the stage
has screens and the owner has not confirmed their design.

**Do:**
1. Write `spec.md` from `assets/spec.md`, keeping every heading (`Not applicable for this stage`
   where one does not apply). Under "Read first", name the documents holding the rules; link, do not
   restate. A rule no document holds is written into a document first.
2. Acceptance criteria as `- AC-1: ...`: one observable outcome each, testable from outside the
   code, naming its requirement, with the negative and boundary cases that matter (wrong owner,
   wrong role, simultaneous requests, bad input).
3. "Risks and test focus": what is most likely to go wrong and the test types it calls for.
4. "Verification commands": the exact gates in one `bash` block, copied into `verification.commands`.
   A stage with screens includes the accessibility check and a stage with endpoints the contract
   check, as `docs/engineering/web.md` and `backend.md` ("Tests") name them.
5. Set `status: planned` (and the feature `in-progress` on its first planned stage). Commit on the
   stage branch: `docs(<F-S>): plan <title>`.

---

## `stage-build <F-S>`

**Role:** Developer; load `salon-dev`. **Reads:** this stage's `spec.md` and `stage.yaml`, its named
rules, the source it changes. Not other stages.

**Refuse if:** the status is `outlined` (no spec), `built` (handed over; open bugs mean `bug-fix`),
or `complete`/`released` (changes go through `change-request`); or a `depends_on` stage is not
`complete`/`released` and carries no `reopened_by`.

**Do:**
1. Branch from the feature branch (`git-workflow.md`); set `status: in-progress` with your first commit.
2. Implement with unit and integration tests: `feat(<F-S>): ...`.
3. Satisfy every `verification.commands` gate. Never remove a gate to pass.
4. Check each acceptance criterion on the running product, from outside. QA does not see this stage
   until the whole feature is built, so this is the only check before later stages build on it.
5. Fill `handoff`: `run` (how to start and reach it), `data` (accounts and seed), `notes` (per
   criterion: checked, or not and why; what was left out; anything different from `docs/`; the gate
   table of `docs/operations/verification-evidence.md`). Facts, not how the code works.
6. Set `status: built`, generate, validate, guard, and commit with your last change. It is the lead's turn.

**Stop and ask the lead** when a machine prerequisite is missing, the spec cannot be met as
written, or an open BRD decision blocks the work.

**Does not:** write under `qa/`, edit a spec, or touch `stage.yaml` beyond `status` and `handoff`.

---

## `stage-close <F-S>`

**Role:** Lead. A light check: QA tests the stage with its feature, but later stages are built on
it now, so refuse work that is not whole.

**Refuse if:** the status is not `built`; a stage pass the owner asked for is `failing`/`blocked`;
a `depends_on` stage is not closed; `handoff.notes` misses an acceptance criterion; a gate has no
passing record for the current source (`docs/operations/verification-evidence.md`; run only the
missing or invalidated ones); `sdlc validate` reports anything; a "Done criteria" item fails.

**Do:** correct `docs/` for every difference the handoff reports and record any decision; set
`status: complete`; generate and validate; merge the stage branch into the feature branch with
that change in the merge commit: `merge(<F-S>): <title>`. Report what was delivered, the gate
results, any criterion not checked, and, after the last stage, that `feature-verify` is next.

---

## QA during the build

While stages are built, QA prepares the feature pass against the approved specs: risk-based test
choice, tests named by case (`salon-qa`), fixtures and helpers. After each stage closes, QA
**dry-runs** its prepared tests against the feature branch and repairs its own test defects. A dry
run certifies nothing, records nothing in yaml and files no bug; a product defect it reveals is
reported to the lead, who decides whether the developer fixes it now. Unfinished behaviour of a
stage not yet closed is not a bug. Keep clock, service and database changes out of an environment a
developer is using. On F03 most of the first four hours of feature QA went on repairing tests run
for the first time; the dry run moves that work into the build.

---

## `feature-verify <F>`

**Role:** QA; load `salon-qa`, whose checklist this wraps. **Reads:** the feature README and yaml,
every stage's `spec.md` and `stage.yaml` (with `handoff`), the requirements, the specs' "Read first"
documents, `docs/qa/`, the running product. Code only to find a selector or endpoint; expectations
come from the specs and requirements.

**Refuse if:** a stage is not `complete`; you built a stage of this feature in this context.

**Do, on the first pass:**
1. On the feature branch, set `status: in-qa`. Start the product from the handoffs; a product that
   will not start is bug number one.
2. Finish the tests for every criterion and the few journeys across stages (`salon-qa`); set each
   entry's `suites` (`smoke`, `regression`, `release`).
3. Commit the tests, then run `qa/scripts/sdlc run --feature <F>`: every stage's automation, then the
   journeys. No other feature.
4. Triage every failure (`salon-qa` `references/bug-loop.md`); file one bug per product defect under
   `features/<feature>/bugs/` (`BUG-F07-001`); confirm stage bugs still open.
5. Run each deferred case whose unblocking event has happened.
6. Explore as a user and write the feature's `review.md` (`references/product-review.md`).
7. Record `integration.qa_revision`, `evidence.last_run`, `evidence.last_result` and `qa_status`;
   generate, validate, guard, commit `test(<F>): ...`.

**Do, when re-verifying fixed bugs:** verify each fix and what depends on it, not the whole feature
unless the change is broad; set each bug `verified` or `reopened` with evidence; update evidence
and `qa_status`. A targeted run never replaces the full pass's recorded revision.

**Does not:** edit application code; run another feature's automation; set `complete`.

---

## `bug-fix <F>` or `bug-fix <F-S>`

**Role:** Developer. Fixes the `open` or `reopened` bugs of a feature (its own and its stages') or,
during an owner-requested stage pass, one stage's. **Refuse if:** none is open.

**For each bug:** reproduce it; add a test that fails because of it; fix the cause; set `status:
fixed`, `fixed_in` and a one-line `fix_note`; commit `fix(<F>): ...` with `Fixes BUG-...`. If you
believe the expectation is wrong, change nothing under `qa/`: write `developer_note` and tell the lead.

After the batch, run the affected stages' gates once, deduplicated. Do not change `qa_status`.

---

## `feature-close <F>`

**Role:** Lead. `complete` is what every later release trusts, so this command refuses.

**Before it:** give each `review.md` suggestion a disposition (`change-requests.md`), and correct
`docs/` for what the review reports as different.

**Refuse if:** a stage is not `complete` or `qa_status` is not `pass`; the product changed since
`integration.qa_revision` (`docs/operations/verification-evidence.md`; verify the affected scope
first); a required manual case has no `performed_on`, or a deferred case no reason and unblocking
event; `sdlc validate` reports anything; a suggestion has no disposition.

**Do:** set `status: complete`; generate, validate, commit `chore(<F>): close feature - <title>`.
Report what was delivered, the QA result, bugs found and fixed, manual and deferred cases, minor bugs
left open and suggestions awaiting the owner. Then `feature-submit` where the git policy allows it.

---

## `feature-submit <F>` and `feature-integrate <F>`

**Role:** Lead. Read `git-workflow.md` and `docs/operations/git-delivery.md`.

**Submit.** Refuse if the feature is not `complete` with QA `pass`, the branch is not clean,
`qa/scripts/sdlc pr-check <F>` fails, or pushing is outside the owner's authorisation. Push, open
one PR into `main` with the QA summary, tested revision and manual/deferred cases, record
`integration.pull_request` in one metadata commit, and wait for CI on the final head. Smoke runs on
the PR. The owner merges; nothing deploys.

**Integrate.** Refuse if `qa/scripts/sdlc merge-sync <F> --check` cannot verify the merge. Update
`main`; clean up only the feature's merged branches and worktrees (`sdlc cleanup`, preview first);
on the next feature's or a bookkeeping branch run `merge-sync <F>` and commit the record (never on
`main`). Run smoke on the merged `main`, then `start-check` before the next feature.

---

## `stage-verify <F-S>`

**Role:** QA, **only when the owner asks** for a pass on one stage. Refuse otherwise, if the status
is not `built`, or if you built it in this context. Do what `feature-verify` does for this stage:
`qa/scripts/sdlc run --stage <F-S>`, bugs in the stage's `bugs/` (`BUG-F07-S02-001`), a short
`review.md`, `evidence` and `qa_status` on the stage. No journeys. `stage-close` refuses while it is
`failing` or `blocked`; minor bugs carry to the feature pass. The tests written here are the stage's
tests at `feature-verify`.

---

## `change-request <title>`

**Role:** Lead. Follow [change-requests.md](change-requests.md): record, analyse, get the owner's
decision, then update the register, specs and tests together and reopen the affected stages.

---

## `release-prepare`, `release-verify`, `release-cut`

**Prepare (lead, with QA).** Refuse if `qa/releases/<v>/` exists, the version is not `vN.N.N`, no
feature is complete, merged and unreleased, or validate fails. Scope the complete, merged,
unreleased features and confirm it and the version with the owner (`releases.md`). On
`{username}/chore/release-vX-Y-Z`, write `qa/releases/<v>/release.yaml` from `assets/release.yaml`:
the deduplicated stage gates, then the `smoke`, `regression` and `release` suites. Run or list as
deferred every deferred case in scope. Preview with `qa/scripts/sdlc run --release <v> --dry-run`.

**Verify (QA, when the owner asks).** On a clean tree set `source_revision` to `git rev-parse
HEAD`, run `qa/scripts/sdlc run --release <v>` (stops at the first failure, writes `RESULTS.md`),
commit `test(release): verify <v>`. After a fix, verify again from the start.

**Cut (lead).** Refuse unless every gate in `RESULTS.md` is `PASS` and `git diff --stat
<source_revision> HEAD -- <paths>` (the paths under "What a release certifies" in
`docs/architecture/repository-structure.md`) prints nothing. Set scope stages and features
`released` with `released_in`; generate, validate, commit `chore(release): cut <v>`; report the
deferred items. Tag and deploy only when the owner asks (`docs/operations/environments-and-releases.md`).

---

## Where a bug is filed

| Found | File the bug | Status |
| --- | --- | --- |
| In `feature-verify` | `features/<feature>/bugs/` | Feature `in-qa`, `qa_status: failing` |
| In an owner-requested stage pass | `<stage>/bugs/` | Stage stays `built`, `qa_status: failing`; open minor bugs carry to the feature pass |
| Before merge, outside the feature pass (`release-verify` of an unmerged feature) | The owning feature's `bugs/` | The lead sets that feature back to `in-qa`, `failing` |
| After the feature's merge, before release (smoke, regression, a dry run) | The owning feature's `bugs/`, with `found_after_merge: <date>` | Statuses stay; the fix ships in a later PR, which takes the bug to `verified` |
| In a released version | The owning feature's `bugs/`, with `found_in_release: <version>` | Statuses stay `released`; fixed on a `hotfix` branch as a PATCH release |

The developer fixes with `bug-fix`, QA re-verifies the affected scope, and the lead closes again
where a feature was set back.

---

## Rules across all commands

- A command changes only the status fields it names; evidence is written by the command that
  produced it, never back-filled. A command that refuses changes nothing.
- Build and close one stage folder per session; `feature-verify`, `change-request` and the release
  commands read across stages because their job requires it.
- A `sdlc validate` finding is fixed at its source, never by loosening the validator in the same
  change. When the tooling is wrong, the lead fixes it under `qa/scripts/` with a test, in its own commit.
