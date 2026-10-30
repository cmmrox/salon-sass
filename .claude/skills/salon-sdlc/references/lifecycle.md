# Lifecycle commands

The contract of every command: who runs it, what it reads, when it refuses, what it writes and the
status it leaves behind. Each section stands alone; read only the one you are running.

## Contents

- Status model and shared mechanics
- `feature-new`
- `stage-plan`
- `stage-build`
- `stage-close`
- QA preparation during a build
- `feature-verify` (and re-verifying fixes)
- `bug-fix`
- `feature-close`
- `feature-submit`
- `feature-integrate`
- `stage-verify` (only when the owner asks)
- `change-request`
- `release-prepare`
- `release-verify`
- `release-cut`
- Where a bug is filed
- Rules across all commands

## Status model and shared mechanics

```
feature: backlog -> planned -> in-progress -> in-qa -> complete -> released
stage:   outlined -> planned -> in-progress -> built -> complete -> released
qa_status (a feature; a stage only when the owner asked for a stage pass): not-run | failing | blocked | pass
integration (feature.yaml): branch, pull_request, qa_revision, merged_commit, merged_at
```

For a feature, `complete` means QA-passed and closed, ready for the feature PR. For a stage it
means closed on the developer's gates; it does not imply a stage QA pass. Only the owner's confirmed
GitHub merge permits the next feature to start. Deployment is a separate, optional action.

**QA happens once, on the whole feature.** A stage is built, handed over and closed on the
developer's gates; nobody from QA tests it then. When every stage of the feature is closed, QA
verifies the feature: every stage's acceptance criteria and the journeys across them, in one pass,
and the developer fixes everything that pass finds. A full QA pass on each stage was tried and cost
more time than the defects it caught early were worth. The owner may still ask for a pass on one
stage (`stage-verify`); nothing else starts one.

Whose turn it is, read from the files (`qa/scripts/sdlc status` prints it):

| Stage state | Turn |
| --- | --- |
| `outlined` | Lead: `stage-plan` |
| `planned`, `in-progress` | Developer: `stage-build` |
| `built`, `qa_status: not-run` | Lead: `stage-close` |
| `built`, any other `qa_status` | The owner asked for a stage pass: the feature table below, with `stage-verify`, `bug-fix <F-S>` and `stage-close` |

| Feature state | Turn |
| --- | --- |
| A stage is not `complete` | Whoever that stage's row says |
| Every stage `complete`, `qa_status: not-run` | QA: `feature-verify` |
| `failing`, a bug of the feature or of one of its stages is `open` or `reopened` | Developer: `bug-fix <F>` |
| `failing`, no such bug | QA: `feature-verify` (finish the pass, or re-verify the fixes) |
| `blocked` | Lead: remove what blocks QA (`blocked_reason` says what) |
| `pass` | Lead: `feature-close` |
| `complete`, no PR | Lead: `feature-submit` |
| `complete`, PR not yet confirmed merged | Owner merges; lead: `feature-integrate` |

**Which `qa_status` to set**, on the feature at `feature-verify` (on the stage at `stage-verify`):

| Status | When |
| --- | --- |
| `pass` | Every acceptance criterion of every stage has a case; every case is automated, or manual with a reason and a `performed_on` date, or deferred with a reason and what unblocks it; the last run of the scope passed on the code as it is now; no `blocker` or `major` bug of the feature or its stages is unresolved; `review.md` exists; `qa/scripts/sdlc validate` reports nothing for the scope |
| `failing` | Anything above is not yet true and the next step is the developer's (a bug is open) or yours |
| `blocked` | The work could be tested, but this environment lacks something you cannot provide yourself: no emulator, a tool that needs installing, a credential nobody has given you. Set `blocked_reason` and tell the lead. Do not defer the cases to get round it |

**Finding a stage's folder from its id:**

```bash
STAGE_YAML="$(grep -rl "^id: F07-S02$" features/*/stages/*/stage.yaml)"
STAGE_DIR="$(dirname "$STAGE_YAML")"
```

If that finds nothing, refuse: `no stage.yaml declares id: F07-S02`. Never guess a folder by name.

**After any edit to a `feature.yaml` or `stage.yaml`:**

```bash
qa/scripts/sdlc generate && qa/scripts/sdlc validate
```

Fix every finding before going on. `features/README.md` and `qa/coverage.md` are rewritten by
`generate`; commit them together with the yaml change. Every role may commit them, because they are
tool output.

**Verification commands** (`verification.commands` in `stage.yaml`) are the developer's gates. Each
required execution runs in its own shell from the repository root, in order, and must exit zero.
Apply the shared execution/reuse rule in `references/progress.md` at every handoff and closure.

**Before the first commit of a session**, check the repository has a history: if `git log` shows no
commits, stop and tell the lead. The first commit is the lead's, made on `main` at the owner's request.

---

## `feature-new <F>`

**Role:** Lead. Turns a backlog feature into a planned one with its stages outlined. A brand-new
feature (one not in `features/`) is created the same way, but only from an approved change request.

**Reads:** `features/README.md`; the feature's `feature.yaml`; the text of each of its requirements
(`grep -A9 "id: BKG-04$" docs/brd/requirements.yaml`); the matching screens in the design
prototype; the roadmap; the architecture; the parts of the requirements document that cover them.
`docs/README.md` says where each of these is.

**Refuse if:**
- The feature's status is not `backlog`. It has already been planned.
- A feature it `depends_on` has not passed QA and been merged into GitHub `main`. Say which
  dependency is awaiting closure or the owner's merge.
- `qa/scripts/sdlc start-check <F>` fails. It checks earlier portfolio features and dependencies
  live, and refuses an unmerged PR or stale/missing merge evidence. A recorded `started_early`
  owner exception may waive portfolio/milestone order, never a dependency's merge.
- The feature belongs to a later milestone and an earlier milestone is not yet released (the
  portfolio shows "after M1-web is released"). The order of milestones is in `docs/ROADMAP.md`;
  only the owner changes it. When the owner does say to start a feature early, record their
  instruction in the feature's `started_early` field; the validator refuses the feature otherwise.

**Do:**
1. Divide the feature into stages. A good stage:
   - can be started and shown working on its own, without waiting for a later stage;
   - ends in something a person can use or call (a screen, an endpoint, a job with a visible
     result), so the developer can check its acceptance criteria from outside before handing over;
   - is built in dependency order, with the riskiest part of the feature early;
   - is large enough to be worth planning and closing on its own. Do not make a stage out of a
     small change.
2. Create one folder per stage, `stages/S01-<slug>/`, each with a `stage.yaml` copied from
   `assets/stage.yaml`: `status: outlined`, title, sequence, surfaces, `depends_on`, and the
   requirement ids it delivers. Every requirement of the feature must belong to at least one stage.
3. Write the feature `README.md` from `assets/feature-README.md`.
4. On its integration branch from updated `origin/main`, record `integration.branch`, run
   `start-check <F>` and set the feature's `status: planned`. The preceding feature's merge
   bookkeeping may be committed here before the feature is planned; see `git-workflow.md`.
5. Show the owner the stage outline and wait for approval before planning the first stage.

**Does not:** write any `spec.md`, code or test case.

---

## `stage-plan <F-S>`

**Role:** Lead. Writes the stage's specification.

**Reads:** the feature `README.md` and `feature.yaml`; this stage's `stage.yaml`; the text of its
requirements; the prototype screens it touches; the project documents for the parts of the product
it touches (`docs/README.md` says which); `docs/qa/test-types.md`, to name the kinds of testing the
stage will need.

**Refuse if:**
- The stage's status is not `outlined`. A planned stage that has not started may be re-planned on the
  owner's request; anything further along changes through `change-request`.
- The feature is still `backlog`. Run `feature-new` first.

**Do:**
1. Write `spec.md` from `assets/spec.md`. Keep every heading; under one that does not apply write
   `Not applicable for this stage`.
2. Under "Read first", name the project documents that hold the rules for this stage, by path. The
   spec states what to build; it links to the rules and does not restate them. If the stage needs a
   rule that no document holds, write the document first.
3. Write acceptance criteria as `- AC-1: ...` lines. Each one is a single observable outcome that a
   test can pass or fail from outside the code, names the requirement it comes from, and includes
   the negative and boundary cases that matter (wrong owner, wrong role, simultaneous requests,
   bad input). A criterion QA could only check by editing application code is written wrongly.
4. Under "Risks and test focus", list what is most likely to go wrong and the test types it calls
   for. The developer tests against it now; QA plans the feature pass from it later.
5. Under "Verification commands", list the exact developer gate commands in one `bash` block, and
   copy them into `stage.yaml` `verification.commands`. The validator fails if the two differ.
6. Set the stage's `status: planned`. If this is the feature's first planned stage, set the
   feature's `status: in-progress`.
7. Create the stage's branch from the feature integration branch (see `git-workflow.md`) and commit the spec there:
   `docs(<F-S>): plan <title>`.

**Does not:** write code, cases or automation.

---

## `stage-build <F-S>`

**Role:** Developer. Load `salon-dev`.

**Reads:** `features/README.md`; this stage's `spec.md` and `stage.yaml`; its named project rules
and other rules for the affected product areas; the source it changes. Apply the context-reading
rule in `references/progress.md`. Do not explore unrelated stages.

**Refuse if:**
- The status is `outlined`: there is no spec yet.
- The status is `built`: the stage is handed over. If bugs are open, the command is `bug-fix`.
- The status is `complete` or `released`: finished work changes only through `change-request`.
- A stage in `depends_on` is not `complete` or `released`, and does not carry `reopened_by` (a
  dependency reopened by a change request was complete once, and building on it may continue).
  Name the dependency and stop.

**Do:**
1. Create or switch to the stage branch from the feature integration branch (see `git-workflow.md`).
   Run `qa/scripts/sdlc start-check <F>` before building a newly started feature. Existing work
   covered by the transition in `docs/operations/git-delivery.md` is preserved.
2. Set `status: in-progress` before writing any code, so an interrupted session leaves an honest
   status. Commit it with your first commit.
3. Implement the spec with unit and integration tests. Follow `salon-dev` and the project documents. Commit as often as is
   useful: `feat(<F-S>): ...`.
4. Satisfy every command in `verification.commands` under the shared execution/reuse rule. If a
   command names a script the spec says this stage creates, create it; never remove a gate to pass.
5. Check each acceptance criterion yourself against the running product, from outside: the screen,
   the endpoint, the command. QA does not see this stage until the whole feature is built, so this
   is the only check between your code and the stages built on it. Do not hand over something you
   have not seen work.
6. Fill in `handoff` in `stage.yaml`:
   - `run`: the commands that start the product and reach what this stage built;
   - `data`: the accounts and seed data that exist;
   - `notes`: for each acceptance criterion, that you checked it on the running product, or that
     you could not and why; anything deliberately left out; and anything that turned out different
     from a document under `docs/` (the lead corrects the document; you do not edit project
     documents or skills).
   Include the developer-gate record required by `docs/operations/verification-evidence.md`.
   Prefer concise criterion-to-result/evidence rows. Do not explain how the code works.
7. Set `status: built`, generate and validate, run
   `python3 qa/scripts/guard.py --role dev --diff`, and commit: `chore(<F-S>): hand over`.
   It is now the lead's turn.

**Stop and ask the lead** when a prerequisite is missing from the machine (a runtime, an emulator),
when the spec cannot be met as written, or when a decision the BRD lists as open blocks the work.
Do not install system software or change the spec on your own.

**Does not:** write or edit anything under `qa/`, `cases.md`, `review.md` or `spec.md`; touch any
field of `stage.yaml` other than `status` and `handoff`; close the stage.

---

## `stage-close <F-S>`

**Role:** Lead. Closes a stage on the developer's gates. QA has not tested it, and later stages are
about to be built on it, so this command's job is to refuse work that is not whole.

**Before running it**, correct the documents under `docs/` for everything the developer reported in
`handoff.notes` as different from them, record any decision in the decision log, and do whatever
else the spec's "Done criteria" assign to the lead.

**Gates, in order. Stop at the first unresolved failure, print why, and leave status unchanged.
Gather missing gate evidence and record the assessment as described below:**
1. The status is `built`.
2. `qa_status` is `not-run`, or `pass` when the owner asked for a stage pass. `failing` or `blocked`
   means a stage pass was started and has not finished: it finishes first.
3. Every stage in `depends_on` is `complete` or `released`. A reopened dependency must be closed
   again first.
4. `handoff` is filled in, and `handoff.notes` accounts for every acceptance criterion in the spec:
   checked on the running product, or not checked for a reason you accept. A criterion that is
   missing from the notes sends the stage back to the developer.
5. Every command in `verification.commands` has complete passing evidence for the current relevant
   source and conditions. Inspect the handoff record under `docs/operations/verification-evidence.md`
   and the shared reuse rule. Execute missing or invalidated gates only; unresolved failures refuse closure.
6. `qa/scripts/sdlc validate` reports nothing.
7. Every item under the spec's "Done criteria" holds.

**Only if every gate passed:**
1. Set `status: complete`. Change nothing else in the file.
2. `qa/scripts/sdlc generate`, then `qa/scripts/sdlc validate` again. If it now fails, revert the
   status and stop.
3. Commit: `chore(<F-S>): close stage - <title>`.
   The lead integrates the closed stage into the feature integration branch with a merge commit.
4. Report to the owner: what was delivered, the result of the developer's gates, any criterion the
   developer could not check, any bug left open, and that QA will test it with the rest of the
   feature. If this was the feature's last stage, say that `feature-verify` is next. Push or open
   the feature PR only after every stage closes and full feature QA passes.

**Does not:** run QA automation or ask QA for a pass; fix a failing gate by editing `stage.yaml` by
hand.

---

## QA preparation during a build

During an authorised feature build, the lead may delegate preparation to an independent QA agent
against approved stage specs and contracts. QA can draft cases, plan risk coverage and prepare
reusable fixtures/automation without waiting for all stages to close. No extra lifecycle status
or command is needed. Do not derive expected behavior from unfinished implementation.

Use the feature's QA branch and explicit path ownership; preserve developer edits and serialize
shared yaml updates through the lead. Exercise test helpers on isolated synthetic fixtures where
available, not as an unrequested acceptance pass on a stage. Do not run stage/feature automation,
mark cases performed, set `in-qa`/`pass` or block stage closure on this preparation. Report uncertain
contracts and missing test hooks early to the lead. Unfinished implementation is not itself a bug.

Keep runtime-mutating checks (shared clock, service stops, database resets) separate from concurrent
developer work unless their environments are isolated. Once all stages close, integrate the finished
source into the QA branch, reconcile prepared cases with final specs, and begin `feature-verify`.

---

## `feature-verify <F>`

**Role:** QA. The one QA pass of a feature: every stage's acceptance criteria, and the journeys
across the stages. Load `salon-qa` and follow its feature checklist; this section is the contract
around it.

**Reads:** the feature `README.md` and `feature.yaml`; every stage folder of the feature (`spec.md`,
`stage.yaml` with its `handoff`, any existing `cases.md` and `bugs/`); the text of the feature's
requirements; the documents the specs name under "Read first"; `docs/qa/`; the running product. You
may read application code to find a selector or an endpoint, but expected results come from the
specs and the requirements, never from what the code happens to do.

**Refuse if:**
- Any stage of the feature is not `complete`.
- You are the agent that built any stage of this feature in this same context.

**Before execution:** apply `references/progress.md`'s saved-evidence check. A resumed session or
stale status label does not require another pass when complete relevant evidence remains valid.

**Do, on the first pass (`qa_status: not-run`, with no reusable complete pass):**
1. Create or update the QA branch from the feature integration branch containing every closed stage;
   preserve prepared cases/fixtures and reconcile them with the final specs. Set `status: in-qa`.
2. Preflight the environment from the stages' `handoff`. If the product cannot be started, that is
   the first bug.
3. Choose the test types for the feature (`docs/qa/test-types.md`), starting from each spec's
   "Risks and test focus".
4. Write the cases, within the limits in `references/test-design.md` in `salon-qa`:
   - the cases that prove a stage's acceptance criteria go in that stage's `cases.md`, declared
     under `cases:` in its `stage.yaml`, with ids like `TC-F07-S02-001`;
   - the journeys that only exist across stages go in `features/<feature>/cases.md`, declared in
     `feature.yaml`, with ids like `TC-F07-001`, each naming the requirements it covers. A journey
     crosses stages and surfaces the way a real person would (for example: find a service on the
     web, hold a time, pay the deposit, see the booking on the salon's calendar). A few, not one
     per combination.
5. Automate cases according to the automated/manual/deferred rules in `salon-qa`'s test design.
   Reuse compatible helpers and cases; register each file once, under
   `verification.automation` of the stage or the feature whose cases it covers.
6. Decide suite membership for every automation entry: add `suites: [regression]` to what must keep
   working, and `smoke` to the few that prove the feature is alive.
7. Commit the cases and automation, with role guard and validation, before the final run. Record
   `git rev-parse HEAD` as the tested revision. Prepare the current environment and evidence context
   as `docs/operations/verification-evidence.md` describes. Run the full feature with that context.
   `qa/scripts/sdlc run --feature <F>` runs every stage's automation of this feature and
   then the journeys. No other feature.
8. Triage every failure as a product defect, an environment fault or a test defect
   (`references/bug-loop.md` in `salon-qa`). File one bug per product defect under
   `features/<feature>/bugs/`, with ids like `BUG-F07-001`. Bugs already open in a stage's `bugs/`
   are part of this pass: confirm each one still happens.
9. Go through the deferred cases of the feature's stages (`qa/coverage.md` lists them). Run each
   one whose unblocking stage or event has now happened, and move it out of `deferred`.
10. Explore the feature as a user and write its `review.md`
    (`references/product-review.md` in `salon-qa`). One review for the feature, none per stage.
11. Record `integration.qa_revision` (the tested full SHA), `evidence.last_run` (the run's folder,
    `qa/reports/<folder>`) and `evidence.last_result`
    on the feature and set its `qa_status` from the table under "Status model". The stages'
    `qa_status` is left as it is. Generate and validate, run
    `python3 qa/scripts/guard.py --role qa --diff`, and commit: `test(<F>): ...`.

**Do, when re-verifying (bugs are `fixed`):**
1. Apply the saved-evidence rule in `references/progress.md`. Independently verify each fixed bug
   and its affected dependencies; retain unaffected passing results. Record an impact-based reason
   before selecting a whole-feature rerun.
2. Set each bug to `verified` or `reopened` with the evidence.
3. Update the evidence and `qa_status`, generate and validate, guard, commit.
   Commit automation edits before execution. Record actual tested revisions and scope following
   `docs/operations/verification-evidence.md`; never replace a base full-pass revision with a
   targeted-pass revision as if the whole feature ran. The lead integrates QA and verified fixes.

**Does not:** edit application code; run any other feature's automation, regression or smoke; set
the status to `complete`.

---

## `bug-fix <F>` or `bug-fix <F-S>`

**Role:** Developer. `bug-fix <F>` fixes the bugs filed against a feature: those in
`features/<feature>/bugs/` and those in the `bugs/` of its stages. `bug-fix <F-S>` fixes one
stage's, during a stage pass the owner asked for.

**Refuse if:** no bug in that scope is `open` or `reopened`.

**Do, for each such bug:**
1. Reproduce it from the bug's own steps.
2. Add a unit or integration test that fails because of it, then fix the cause.
3. Run the focused fix test and record its result; broader gates follow the completed fix batch.
4. On the bug file set `status: fixed`, `fixed_in: <commit>` and a one-line `fix_note`.
5. If you believe the product is right and the test or the expectation is wrong, change nothing
   under `qa/`. Write your reasoning in `developer_note`, leave the status as it is, and tell the lead.
6. Guard, then commit: `fix(<F>): ...` (or the stage id, when the fix belongs to one stage) with
   `Fixes BUG-...` in the body.

Before returning the batch to QA, satisfy the affected stages' `verification.commands` under the
shared execution/reuse rule in `references/progress.md`. Deduplicate identical commands and record
complete results for the final relevant source. A focused test alone does not satisfy a broader
declared gate. When every bug is `fixed` or disputed and gates hold, it is QA's turn again.
Do not change `qa_status`.

---

## `feature-close <F>`

**Role:** Lead. This command's job is to refuse. `complete` is what every later release trusts
without checking again.

**Before running it**, give every suggestion in the feature `review.md` a disposition in its
Disposition column (see `change-requests.md`, "Suggestions from QA"), and correct the documents
under `docs/` for everything QA reported in the review as different from them.

Run from the feature integration branch after integrating the QA pass and all verified fixes.
The product and automation must match `integration.qa_revision`; the path list is in
`docs/architecture/repository-structure.md`. A change there requires QA re-verification.

**Gates, in order. Stop at the first that fails, print why, and change nothing:**
1. Every stage is `complete`, and the feature's `qa_status` is `pass`.
2. Confirm the current environment/build and fixture prerequisites, then validate recorded
   developer gates and independent full-feature QA with the closure evidence command in
   `docs/operations/verification-evidence.md`. Evidence must cover every deduplicated stage
   command and the exact full feature, and match current certified source, criteria, command
   definitions and environment context. Legacy, incomplete, changed or unavailable evidence
   requires assessment of the affected proof under `references/progress.md`; never rerun an
   unchanged product merely to satisfy an overbroad tooling fingerprint. Follow the evidence
   document's explicit limitations and scoped-handoff procedure rather than claiming a false pass.
3. Confirm every required manual case was performed and every deferred case has an explicit
   reason and unblocking event. Reused automation evidence does not imply a manual check.
4. `qa/scripts/sdlc validate` reports nothing.
5. No suggestion in `review.md` is left without a disposition.

**Then:** set `status: complete`; generate and validate; commit
`chore(<F>): close feature - <title>`; report to the owner what was delivered, the QA result, the
bugs found and fixed, what is manual or deferred, the minor bugs left open and the suggestions
waiting for a decision; say the feature is ready for its feature PR. If the feature changed code that
earlier features rely on, say so; do not run their automation unless the owner asks.

The feature is ready for `feature-submit`, not yet merged or deployed. When the project policy
authorises pushes and PR creation, submit it as the next step without another permission prompt.

**Does not:** fix a failing gate by editing a yaml by hand; run other features' automation.

---

## `feature-submit <F>`

**Role:** Lead. Read `git-workflow.md` and `docs/operations/git-delivery.md`.

**Refuse if:** the feature is not `complete` with QA `pass`; the integration branch is not clean;
`qa/scripts/sdlc pr-check <F>` fails; or pushes/PR creation are outside the owner's authorisation.

**Do:** push the feature integration branch, create one PR into `main` with the QA summary,
tested revision and explicit manual/deferred cases; record `integration.pull_request`, generate,
validate and push its metadata commit before waiting on CI. Batch remaining delivery metadata;
use the PR body for progress updates rather than repeated bookkeeping commits. Attach the created
PR to the chat and reuse an existing feature PR. Required CI must pass on the final PR head before the owner's merge.
The owner merges it; the next feature waits. Nothing deploys.

---

## `feature-integrate <F>`

**Role:** Lead. Follow `git-workflow.md`, "Confirming a merge and cleanup".

**Refuse if:** `qa/scripts/sdlc merge-sync <F> --check` cannot verify the owner's GitHub merge.

**Do:** update retained `main`, preview and apply cleanup for only the feature's related merged
branches/worktrees, preserving QA evidence. On a bookkeeping, release or next-feature branch,
run `qa/scripts/sdlc merge-sync <F>` and commit the generated merge record. Never write it directly
to `main`. Run `start-check` before the next feature starts. Report any cleanup target retained
and its concrete reason. A staging deployment is optional and is never triggered by this command.

---

## `stage-verify <F-S>`

**Role:** QA. A QA pass on one stage, ahead of its feature. **Runs only when the owner asks for
it**, for example on a stage the rest of the feature will be built on. It is never the next step by
default, and finishing a stage is not a request for it.

**Refuse if:**
- The request does not say the owner asked for a pass on this stage.
- The status is not `built`. Nothing has been handed over, or the stage is already closed (a closed
  stage is tested with its feature).
- You are the agent that built this stage in this same context.

**Do:** what `feature-verify` does, for this one stage: its cases in its `cases.md` and
`stage.yaml`, its automation, `qa/scripts/sdlc run --stage <F-S>`, bugs under the stage's `bugs/`
with ids like `BUG-F07-S02-001`, a short `review.md` in the stage folder, and `evidence` and
`qa_status` on the stage. No journeys, and no suite membership. Commit: `test(<F-S>): ...`.

A stage pass that has started is finished: `stage-close` refuses the stage while its `qa_status` is
`failing` or `blocked`. A `minor` or `trivial` bug may stay open at a `pass`; it is carried to the
feature pass and settled there. The cases and automation written here are the stage's cases at
`feature-verify`; QA does not write them twice.

**Does not:** edit application code; run any other stage's or feature's automation; set the status
to `complete`.

---

## `change-request <title>`

**Role:** Lead. Follow [change-requests.md](change-requests.md). In short: record the request,
analyse what it touches, get the owner's decision, then update the register, the specs, the cases
and the automation together, and reopen the stages it affects.

---

## `release-prepare <vX.Y.Z>`

**Role:** Lead, with QA reviewing the suites. Assembles the release; runs nothing.

**Refuse if:** `qa/releases/<vX.Y.Z>/` exists; the version is not `vN.N.N`; no feature is
`complete`, merged and unreleased; `qa/scripts/sdlc validate` fails.

**Do:**
1. Scope: the features that are `complete`, confirmed merged with no `released_in`, and their stages.
   Record any outstanding merge evidence on the release branch using `merge-sync`. Confirm the
   scope and the version with the owner (`releases.md` and
   `docs/operations/environments-and-releases.md` have the version rules).
2. QA reviews suite membership across the scope. Regression is everything that must keep working.
   Smoke is the shortest set that proves each feature in the product is alive, a few minutes in
   total. A journey that crosses features belongs to the feature that finishes it.
3. On a branch `{username}/chore/release-vX-Y-Z`, write `qa/releases/<vX.Y.Z>/release.yaml` from
   `assets/release.yaml`. The command gates are the `verification.commands` of the stages in scope,
   with duplicates removed; then `smoke`; then `regression`. Record the version of any separately
   versioned client the release will be verified with.
4. Go through the deferred cases of the stages in scope (`qa/coverage.md` lists them). Each one is
   either run now, because what it waited for has happened, or copied into the manifest's
   `deferred` list so the release report says it is not certified.
5. Preview: `qa/scripts/sdlc run --release <vX.Y.Z> --dry-run`. Show the owner the gate list.

---

## `release-verify <vX.Y.Z>`

**Role:** QA. **Runs only when the owner asks for it.** When the owner says "run the regression and
smoke" and a prepared release is waiting, this is the command. With no release prepared, run the
two suites directly: `qa/scripts/sdlc run --suite smoke`, then `--suite regression`.

**Do:**
1. Make sure the working tree is clean, then set `source_revision` in the manifest to
   `git rev-parse HEAD`: the revision being certified.
2. `qa/scripts/sdlc run --release <vX.Y.Z>`. It runs every gate in order, stops at the first
   failure, and writes `RESULTS.md` itself.
3. Commit the manifest and `RESULTS.md`: `test(release): verify <vX.Y.Z>`.
4. On a failure, follow "Where a bug is filed" below. After the fix, run this command
   again from step 1. A release certifies one revision of the product, so changed product code is
   verified again in full.

---

## `release-cut <vX.Y.Z>`

**Role:** Lead.

**Refuse if:**
- `RESULTS.md` is missing or shows any gate other than `PASS`.
- The product has changed since it was verified. This must print nothing, where `<paths>` is the
  list under "What a release certifies" in `docs/architecture/repository-structure.md`:

  ```bash
  git diff --stat <source_revision> HEAD -- <paths>
  ```

**Do:**
1. For every stage and feature in the manifest's scope set `status: released` and
   `released_in: <vX.Y.Z>`.
2. Generate and validate.
3. Commit `chore(release): cut <vX.Y.Z>`.
4. Report the results and everything under "Deferred, not certified".
5. When the owner asks: open the pull request, and once it is merged tag the merge commit on `main`
   with `<vX.Y.Z>`. Deploy only when asked, following
   `docs/operations/environments-and-releases.md`.

---

## Where a bug is filed

| Found | File the bug | What happens to status |
| --- | --- | --- |
| In `feature-verify` | `features/<feature>/bugs/` | The feature is `in-qa`; set `qa_status: failing` |
| In a stage pass the owner asked for | `<stage>/bugs/` | The stage stays `built`, `qa_status: failing`. A bug left open when the stage closes is settled at the feature pass |
| In `release-verify`, or any time before release, in a feature not yet merged | `features/<feature>/bugs/` of the feature that owns the failing case | The lead sets that feature back to `status: in-qa`, `qa_status: failing`. Its stages stay `complete` |
| After the feature's merge, before release (regression, smoke) | `features/<feature>/bugs/`, with `found_after_merge: <date>` | Statuses stay as they are. The fix ships in a later PR, which carries the bug to `verified` |
| After release, in a shipped version | `features/<feature>/bugs/`, with `found_in_release: <version>` | Statuses stay `released`. Fixed on a `hotfix` branch and shipped as a PATCH release |

In every case but a stage pass, the developer fixes with `bug-fix <F>`, QA re-verifies the affected
scope under `references/progress.md`, and the lead runs `feature-close <F>` again
where the feature was set back.

---

## Rules across all commands

- A command changes only the status fields it is named for. Evidence is written by the command that
  produced it, never back-filled.
- A command that refuses changes nothing.
- One stage folder per session is the rule for building and closing a stage. `feature-verify`,
  `change-request` and the release commands read across stages because their job requires it.
- A finding from `qa/scripts/sdlc validate` is fixed at its source: the yaml, the spec, the case or
  the code. Never by loosening the validator in the same change.
- When the tooling itself is wrong, fix it under `qa/scripts/` with a test in `qa/scripts/tests/`,
  as the lead, in its own commit.
