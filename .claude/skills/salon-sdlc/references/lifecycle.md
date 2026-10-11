# Lifecycle commands

The contract of every command: who runs it, what it reads, when it refuses, what it writes and the
status it leaves behind. Each section stands alone; read only the one you are running.

## Contents

- Status model and shared mechanics
- `feature-new`
- `stage-plan`
- `stage-build`
- `bug-fix`
- `stage-verify` (and re-verifying fixes)
- `stage-close`
- `feature-verify`
- `feature-close`
- `change-request`
- `release-prepare`
- `release-verify`
- `release-cut`
- Bugs found after a stage is closed
- Rules across all commands

## Status model and shared mechanics

```
feature: backlog -> planned -> in-progress -> in-qa -> complete -> released
stage:   outlined -> planned -> in-progress -> in-qa -> complete -> released
qa_status (stage and feature): not-run | failing | blocked | pass
```

Whose turn it is, read from the files (`qa/scripts/sdlc status` prints it):

| Stage state | Turn |
| --- | --- |
| `outlined` | Lead: `stage-plan` |
| `planned`, `in-progress` | Developer: `stage-build` |
| `in-qa`, `qa_status: not-run` | QA: `stage-verify` |
| `in-qa`, `failing`, a bug is `open` or `reopened` | Developer: `bug-fix` |
| `in-qa`, `failing`, no bug is `open` or `reopened` | QA: `stage-verify` (finish the pass, or re-verify the fixes) |
| `in-qa`, `blocked` | Lead: remove what blocks QA (`blocked_reason` says what) |
| `in-qa`, `pass` | Lead: `stage-close` |

A feature in QA follows the same table with `feature-verify`, `bug-fix` and `feature-close`.

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
entry is run in its own shell from the repository root, in order, and must exit zero.

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
- A feature it `depends_on` is not `complete` or `released` (the portfolio shows "after F01" for
  these), unless the owner has said to start it in parallel. Say which dependency is unfinished.
- The feature belongs to a later milestone and an earlier milestone is not yet released (the
  portfolio shows "after M1-web is released"). The order of milestones is in `docs/ROADMAP.md`;
  only the owner changes it. When the owner does say to start a feature early, record their
  instruction in the feature's `started_early` field; the validator refuses the feature otherwise.

**Do:**
1. Divide the feature into stages. A good stage:
   - can be run and tested by QA on its own, without waiting for a later stage;
   - gives QA something a person can use or call (a screen, an endpoint, a job with a visible result);
   - is built in dependency order, with the riskiest part of the feature early;
   - is large enough to be worth a full QA pass. Do not make a stage out of a small change.
2. Create one folder per stage, `stages/S01-<slug>/`, each with a `stage.yaml` copied from
   `assets/stage.yaml`: `status: outlined`, title, sequence, surfaces, `depends_on`, and the
   requirement ids it delivers. Every requirement of the feature must belong to at least one stage.
3. Write the feature `README.md` from `assets/feature-README.md`.
4. Set the feature's `status: planned`.
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
4. Under "Risks and test focus", list the test types this stage needs and why. This is the lead's
   minimum; QA adds to it.
5. Under "Verification commands", list the exact developer gate commands in one `bash` block, and
   copy them into `stage.yaml` `verification.commands`. The validator fails if the two differ.
6. Set the stage's `status: planned`. If this is the feature's first planned stage, set the
   feature's `status: in-progress`.
7. Create the stage's branch (see `git-workflow.md`) and commit the spec there:
   `docs(<F-S>): plan <title>`.

**Does not:** write code, cases or automation.

---

## `stage-build <F-S>`

**Role:** Developer. Load `salon-dev`.

**Reads, and only these:** `features/README.md`; this stage's `spec.md` and `stage.yaml`; every
document its "Read first" section names, in full, and any other that `docs/README.md` lists for a
part of the product the stage touches; the source the stage changes. Not other stages' folders.

**Refuse if:**
- The status is `outlined`: there is no spec yet.
- The status is `in-qa`: the stage is handed over. If bugs are open, the command is `bug-fix`.
- The status is `complete` or `released`: finished work changes only through `change-request`.
- A stage in `depends_on` is not `complete` or `released`, and does not carry `reopened_by` (a
  dependency reopened by a change request was complete once, and building on it may continue).
  Name the dependency and stop.

**Do:**
1. Create or switch to the stage branch (see `git-workflow.md`).
2. Set `status: in-progress` before writing any code, so an interrupted session leaves an honest
   status. Commit it with your first commit.
3. Implement the spec with unit and integration tests. Follow `salon-dev` and the project documents. Commit as often as is
   useful: `feat(<F-S>): ...`.
4. Run every command in `verification.commands` until all are green. If a command names a script
   the spec says this stage creates, create it; never remove a command to get past it.
5. Check each acceptance criterion yourself against the running product. Do not hand over something
   you have not seen work.
6. Fill in `handoff` in `stage.yaml`:
   - `run`: the commands that start what QA needs;
   - `data`: the salons, accounts and seed data that exist;
   - `notes`: anything deliberately left out, any acceptance criterion you could not check and why,
     and anything that turned out different from a document under `docs/` (the lead corrects the
     document; you do not edit project documents or skills).
   Facts only. Do not explain how the code works.
7. Set `status: in-qa` and `qa_status: not-run`, generate and validate, run
   `python3 qa/scripts/guard.py --role dev --diff`, and commit: `chore(<F-S>): hand over to QA`.
   It is now QA's turn.

**Stop and ask the lead** when a prerequisite is missing from the machine (a runtime, an emulator),
when the spec cannot be met as written, or when a decision the BRD lists as open blocks the work.
Do not install system software or change the spec on your own.

**Does not:** write or edit anything under `qa/`, `cases.md`, `review.md` or `spec.md`; touch any
field of `stage.yaml` other than `status`, `qa_status: not-run` at handoff, and `handoff`; close
the stage.

---

## `bug-fix <F-S>` or `bug-fix <F>`

**Role:** Developer. Fixes the bugs QA filed against a stage (`<stage>/bugs/`) or a feature
(`features/<feature>/bugs/`).

**Refuse if:** no bug in that scope is `open` or `reopened`.

**Do, for each such bug:**
1. Reproduce it from the bug's own steps.
2. Add a unit or integration test that fails because of it, then fix the cause.
3. Run the `verification.commands` of the stage the code belongs to.
4. On the bug file set `status: fixed`, `fixed_in: <commit>` and a one-line `fix_note`.
5. If you believe the product is right and the test or the expectation is wrong, change nothing
   under `qa/`. Write your reasoning in `developer_note`, leave the status as it is, and tell the lead.
6. Guard, then commit: `fix(<F-S>): ...` with `Fixes BUG-...` in the body.

When every bug in the scope is `fixed` or disputed, it is QA's turn again. Do not change `qa_status`.

---

## `stage-verify <F-S>`

**Role:** QA. Load `salon-qa` and follow its stage checklist; this section is the contract around it.

**Reads:** this stage's `spec.md` and `stage.yaml` (including `handoff`); the text of its
requirements; the documents its "Read first" section names; `docs/qa/`; its existing `cases.md` and
`bugs/`; the running product. You may read application
code to find a selector or an endpoint, but expected results come from the spec and the
requirements, never from what the code happens to do.

**Refuse if:**
- The status is not `in-qa`. Nothing has been handed over.
- You are the agent that built this stage in this same context.

**Do, on the first pass (`qa_status: not-run`):**
1. Preflight the environment using `handoff`. If the product cannot be started, that is the first bug.
2. Choose the test types for this stage (`docs/qa/test-types.md`) and write the
   cases: declared under `cases:` in `stage.yaml`, described in `cases.md`.
3. Automate every case that can be automated. Register each automation file under
   `verification.automation`, with the cases it covers.
4. Run `qa/scripts/sdlc run --stage <F-S>`.
5. Triage every failure as a product defect, an environment fault or a test defect
   (`references/bug-loop.md` in `salon-qa`). File one bug per product defect.
6. Explore the stage as a user and write `review.md` (`references/product-review.md` in `salon-qa`).
7. Record `evidence.last_run` (the run's folder, `qa/reports/<folder>`) and `evidence.last_result`,
   set `qa_status`, generate and validate, run `python3 qa/scripts/guard.py --role qa --diff`, and
   commit: `test(<F-S>): ...`.

**Do, when re-verifying (bugs are `fixed`):**
1. Rerun the cases of each fixed bug, then the whole stage: `qa/scripts/sdlc run --stage <F-S>`.
2. Set each bug to `verified` or `reopened` with the evidence.
3. Update the evidence and `qa_status`, generate and validate, guard, commit.

**Which `qa_status` to set:**

| Status | When |
| --- | --- |
| `pass` | Every case is automated, or manual with a reason and a `performed_on` date, or deferred with a reason and what unblocks it; the last stage run passed on the code as it is now; no `blocker` or `major` bug is unresolved; `review.md` exists; `qa/scripts/sdlc validate` reports nothing for this stage |
| `failing` | Anything above is not yet true and the next step is the developer's (a bug is open) or yours |
| `blocked` | The stage could be tested, but this environment lacks something you cannot provide yourself: no emulator, a tool that needs installing, a credential nobody has given you. Set `blocked_reason` and tell the lead. Do not defer the cases to get round it |

**Does not:** edit application code; run any other stage's or feature's automation; set the status
to `complete`.

---

## `stage-close <F-S>`

**Role:** Lead. This command's job is to refuse. `complete` is what every later release trusts
without checking again.

**Before running it**:
- give every suggestion in the stage's `review.md` a disposition in its Disposition column (see
  `change-requests.md`, "Suggestions from QA");
- correct the documents under `docs/` for everything the developer reported in `handoff.notes`, and
  QA in `review.md`, as different from them, and record any decision in the decision log;
- do whatever else the spec's "Done criteria" assign to the lead.

**Gates, in order. Stop at the first that fails, print why, and change nothing:**
1. The status is `in-qa` and `qa_status` is `pass`.
2. Every stage in `depends_on` is `complete` or `released`. A reopened dependency must be closed
   again first.
3. Every command in `verification.commands` exits zero, run now.
4. `qa/scripts/sdlc run --stage <F-S>` passes, run now. This stage only.
5. `qa/scripts/sdlc validate` reports nothing.
6. No suggestion in `review.md` is left without a disposition.
7. Every item under the spec's "Done criteria" holds. An item that cannot hold yet must already be
   recorded as a deferred case with what unblocks it.

**Only if every gate passed:**
1. Set `status: complete`. Change nothing else in the file.
2. `qa/scripts/sdlc generate`, then `qa/scripts/sdlc validate` again. If it now fails, revert the
   status and stop.
3. Commit: `chore(<F-S>): close stage - <title>`.
4. Report to the owner: what was delivered, the QA result, the bugs found and fixed, what is manual
   or deferred, and the suggestions waiting for a decision. If the stage changed code that earlier
   stages rely on, say so; do not run their automation unless the owner asks. Push or open the pull
   request only if the owner asks.

**Does not:** fix a failing gate by editing `stage.yaml` by hand; run other stages' automation.

---

## `feature-verify <F>`

**Role:** QA. The feature-wide QA pass, once every stage is done.

**Reads:** every stage folder of this feature (this command is an exception to the one-stage rule),
and the text of the feature's requirements.

**Refuse if:** any stage of the feature is not `complete`.

**Do:**
1. Set the feature's `status: in-qa`.
2. Write the feature's journey cases in `features/<feature>/cases.md`, declared under `cases:` in
   `feature.yaml` with ids like `TC-F07-001`. A journey crosses stages and surfaces the way a real
   person would (for example: find a service on the web, hold a time, pay the deposit, see the
   booking in the app, see it on the salon's calendar). Each case names the requirements it covers.
3. Automate the journeys and register them under the feature's `verification.automation`.
4. Decide suite membership for every automation entry of the feature and its stages: add
   `suites: [regression]` to what must keep working, and `smoke` to the few that prove the feature
   is alive.
5. Run `qa/scripts/sdlc run --feature <F>`. It runs every stage's automation of this feature and
   then the journeys. No other feature.
6. Triage and file bugs under `features/<feature>/bugs/` with ids like `BUG-F07-001`. The developer
   fixes them with `bug-fix <F>`; QA re-verifies.
7. Go through the deferred cases of the feature's stages (`qa/coverage.md` lists them). Run each
   one whose unblocking stage or event has now happened, and move it out of `deferred`.
8. Write the feature `review.md`: the whole flow judged as a product, against competing products.
9. Record the evidence and `qa_status` exactly as for a stage; generate, validate, guard, commit.

---

## `feature-close <F>`

**Role:** Lead.

**Before running it**, give every suggestion in the feature `review.md` a disposition.

**Gates:** every stage is `complete`; `qa_status` is `pass`; `qa/scripts/sdlc run --feature <F>`
passes, run now; `qa/scripts/sdlc validate` reports nothing; no suggestion is without a disposition.

**Then:** set `status: complete`; generate and validate; commit
`chore(<F>): close feature - <title>`; tell the owner the feature is ready to be released.

---

## `change-request <title>`

**Role:** Lead. Follow [change-requests.md](change-requests.md). In short: record the request,
analyse what it touches, get the owner's decision, then update the register, the specs, the cases
and the automation together, and reopen the stages it affects.

---

## `release-prepare <vX.Y.Z>`

**Role:** Lead, with QA reviewing the suites. Assembles the release; runs nothing.

**Refuse if:** `qa/releases/<vX.Y.Z>/` exists; the version is not `vN.N.N`; no feature is
`complete` and unreleased; `qa/scripts/sdlc validate` fails.

**Do:**
1. Scope: the features that are `complete` with no `released_in`, and their stages. Confirm the
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
4. On a failure, follow "Bugs found after a stage is closed" below. After the fix, run this command
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

## Bugs found after a stage is closed

| Found | File the bug | What happens to status |
| --- | --- | --- |
| In `feature-verify` | `features/<feature>/bugs/` | The feature is already `in-qa`; set `qa_status: failing` |
| In `release-verify`, or any time before release | `features/<feature>/bugs/` of the feature that owns the failing case | The lead sets that feature back to `status: in-qa`, `qa_status: failing`. Its stages stay `complete` |
| After release, in a shipped version | `features/<feature>/bugs/`, with `found_in_release: <version>` | Statuses stay `released`. Fixed on a `hotfix` branch and shipped as a PATCH release |

In every case the developer fixes with `bug-fix <F>`, QA re-verifies with the bug's case and
`qa/scripts/sdlc run --feature <F>`, and the lead runs `feature-close <F>` again where the feature
was set back.

---

## Rules across all commands

- A command changes only the status fields it is named for. Evidence is written by the command that
  produced it, never back-filled.
- A command that refuses changes nothing.
- One stage folder per session is the rule for building and verifying a stage. `feature-verify`,
  `change-request` and the release commands read across stages because their job requires it.
- A finding from `qa/scripts/sdlc validate` is fixed at its source: the yaml, the spec, the case or
  the code. Never by loosening the validator in the same change.
- When the tooling itself is wrong, fix it under `qa/scripts/` with a test in `qa/scripts/tests/`,
  as the lead, in its own commit.
