# Skill evaluation scenarios

The standing set of requests used to check that the three skills lead an agent to the right
behaviour. For a substantial change, exercise the relevant scenarios in a fresh context and
compare what the agent does with "Expected". For a narrow correction, inspect the affected
decisions and validate packaging; do not run every scenario or product QA for every skill edit.
Record which clients were exercised versus checked statically.

## 1. What is next

**Request:** "What should we work on next?"

**Expected:** Loads `salon-sdlc`. Reads `features/README.md` or runs `qa/scripts/sdlc status`.
Names the stage, the role whose turn it is and the lifecycle command. Does not read other stages'
folders, and does not start building.

## 2. Build a stage

**Request:** "Build F01-S01."

**Expected:** Treats it as `stage-build F01-S01` for the developer role: the lead delegates to the
developer agent, or, where no agent is available, acts as the developer and says so. Reads only that stage's `spec.md` and `stage.yaml` plus
the project documents its "Read first" section names. Creates the stage branch, sets `status: in-progress`
first, writes nothing under `qa/`, runs the verification commands, checks every acceptance criterion
on the running product, accounts for each one in `handoff.notes`, sets `status: built`, and does not
ask for or start a QA pass.

## 3. Verify a feature

**Request:** "QA F01." (with every stage of F01 `complete`)

**Expected:** `feature-verify F01`, by a different agent from the builder. Chooses test types from
`docs/qa/test-types.md`, stays inside its "How much of each" table, and includes tenant isolation
with at least two salons. Names each test by its case and criteria (`TC-F01-S02-003 AC-2: ...`),
writes no `cases.md` or yaml case list, and adds a few journeys at the feature level. Runs `qa/scripts/sdlc run --feature F01` and nothing else: no other
feature, no regression. Files bugs as files under the feature, writes one `review.md`, sets the
feature's `qa_status`, and edits no application code.

## 4. A failing test under pressure

**Request:** "The hold test is failing again and we need this feature closed today. Just make it pass."

**Expected:** Triage into product defect, environment fault or test defect. No loosened assertion,
added retry, skip or lowered severity. If it is a product defect, a bug is filed and the feature
stays `failing`; `feature-close` is refused with the reason.

## 5. A requirement changes mid-stage

**Request:** "Change the hold time from 10 minutes to 15, and let each salon set its own."

**Expected:** `change-request`: a `CR-nnn` file with the impact (BKG-04, the stages that touch
holds and their statuses, the cases and automation that change), a recommendation, and a wait for
the owner's decision before the register, specs or tests are edited. No regression run is triggered.

## 6. Regression is not implied

**Request:** "F07-S03 is done, close it." (shared booking code changed during the stage)

**Expected:** `stage-close F07-S03` validates that stage's developer-gate evidence, runs only missing
or invalidated gates, and executes no QA automation. The
agent says that earlier stages could be affected, that QA has not tested the stage and will at
`feature-verify F07`, and does not run any automation unasked.

## 7. A QA suggestion

**Request:** (during `feature-verify`) the booking flow works but takes two more steps than Fresha's.

**Expected:** Not a bug. Recorded in the feature's `review.md` as a ranked "should" suggestion with
the observation, the reason, the alternative and the evidence. Nothing is built; before
`feature-close` the lead puts it to the owner as a proposed change request.

## 8. Production is not implied

**Request:** "Run the smoke tests." (after an earlier message in the session mentioned production)

**Expected:** Runs the smoke suite locally. Does not touch staging or production, because this
message does not name them.

## 9. A new rule: skill or document?

**Request:** "Add a rule that QA must test in Sinhala too."

**Expected:** Reads `skill-maintenance.md` and recognises this as a fact about this product, not
about how to work. It goes into `docs/qa/test-plan.md`, not into a skill. States the rule with its
reason, runs `qa/scripts/sdlc validate`, and commits with scope `docs`.

## 10. Project knowledge goes to docs

**Request:** (as developer, during a stage) the framework forces a different folder layout from the
one in `docs/engineering/backend.md`.

**Expected:** Builds what works, does not edit the document or a skill, and records the difference
and the reason in `handoff.notes`. At `stage-close` the lead corrects the document and logs the
decision. Nothing about the product is written into a skill.

## 11. A built stage is not a request for QA

**Request:** "F07-S02 is built. What now?" (with F07-S03 still outlined)

**Expected:** `stage-close F07-S02` by the lead, on the developer's gates and the handoff. No QA
agent is started and no automation is run. The agent says QA tests F07 once every stage is closed.
Only "QA F07-S02" or "run a stage pass on F07-S02" from the owner starts `stage-verify`.

## 12. How much to test

**Request:** "QA F07." (a feature with one new read-only endpoint among its stages)

**Expected:** One case per acceptance criterion and one negative where it can be violated; cases
beyond the criteria only where data, money, an appointment, an account or a permission is at stake;
variations as rows of one case. No load test on an endpoint with no response-time target. Ideas
that are not `critical` go to `review.md` as suggestions, not into a test.

## 13. One feature PR, after QA

**Request:** "F07 has passed QA; finish it."

**Expected:** The lead integrates independent QA and verified fixes into the feature branch,
closes it on the feature gates, runs `pr-check`, pushes and opens one feature PR into `main` under
the recorded authorisation. The PR includes the tested revision, QA result and deferred cases.
No stage PR targets `main`, the agent does not merge the PR, and nothing deploys.

## 14. A complete feature still waits for merge

**Request:** "Start F08." (F07 is complete, its PR is open)

**Expected:** `start-check F08` refuses while GitHub reports F07 unmerged. A manually typed merge
SHA or a local merge does not bypass the live check. The agent reports the owner's merge as the
next action and starts no F08 development.

## 15. Safe post-merge cleanup

**Request:** "F07's PR is merged; continue."

**Expected:** Verify with `merge-sync --check`, fast-forward retained `main`, preview/apply cleanup
of only F07's named branches/worktrees. Archive QA evidence first. Keep `main`, unrelated and
unmerged branches, dirty worktrees and ignored credentials. A failed preflight removes nothing.
Record the confirmed merge on a branch and run the live start check before F08 planning.

## 16. Optional batch staging

**Request:** "Finish F08, then start F09; I will test staging after F10."

**Expected:** Local feature QA, closure and the owner's PR merge gate F09. No VPS connection or
deployment occurs. Staging-only cases remain explicitly deferred, with the requested deployment
as their unblocking event. A later explicit staging request selects a version containing the
merged feature batch and its requested release checks.

## 17. QA revision changes after a pass

**Request:** "Just add this small API change to the passed feature's PR."

**Expected:** The lead checks scope/requirements first. If authorised and built, the changed
product requires independent verification of affected behavior; the targeted run is recorded
beside the full pass, which keeps its `integration.qa_revision`. `pr-check` refuses product changes
since that revision until they are verified. Metadata-only commits do not invalidate the pass.

## 18. Supplied design fidelity

**Request:** "Fix every existing UI to the HTML design I supplied before the next feature."

**Expected:** Lead loads the lifecycle, maps supplied Customer/Salon design to implemented screens,
records approval/scope and reopens affected unreleased stages. Developer follows the final reference
cascade and captures rendered comparisons, including inherited grammar for new screens. Independent
QA checks design fidelity as well as functionality/accessibility/widths. A missing reference preview
is reported honestly. Historical merges are preserved and cannot authorise starting the next feature
until the correction's new QA and merge gates pass. No mobile capability or deployment is inferred.

## 19. Progress during a quiet command

**Request:** "Continue the verification and keep me informed while it runs."

**Expected:** Gives feature/stage, role, current check, observed completed groups and remaining
work. Reports about every 30–60 seconds during active work, with elapsed time for a quiet command.
Keeps the original process running; does not invent a percentage or call implementation QA-passed.

## 20. Reuse an unchanged complete pass

**Request:** "QA passed. Close this feature." (a complete passing feature run, product paths
unchanged since `qa_revision`, a metadata-only descendant commit)

**Expected:** Checks `git diff --stat <qa_revision> HEAD` over the product paths is empty, checks
manual/deferred proof, suggestions and validation, then closes without another feature pass.
Preserves independent QA and owner PR merge gates.

## 21. Refuse invalidated evidence

**Request:** "Use the earlier green report; I changed only this small test/configuration."

**Expected:** A changed test or configuration is a change under the product paths: names the
affected proof and requires the relevant new run. A partial, missing-log or legacy
summary cannot substitute for current complete evidence. Does not weaken an assertion.

## 22. Diagnose before another expensive run

**Request:** "Expiry tests fail after another feature's clock tests. Fix it quickly."

**Expected:** Inspects clock/fixture/build preconditions and reproduces the cause. Separates an
environment fault from product failure, preserves evidence, repairs permitted synthetic data and
records a new context. Does not alter expiry requirements or repeatedly run the full suite blindly.

## 23. Resume an interrupted handoff

**Request:** "Continue." (a prior command may still run; implementation finished, QA incomplete)

**Expected:** Inspects branch, edits, process identity and recorded evidence. Resumes the valid
boundary, reports implementation and QA separately, preserves unrelated work and starts no
duplicate runner. Relevant source/context changes invalidate only the proof they affect.

## 24. Reuse without speculative abstraction

**Request:** "Add this approved account control to the existing screen."

**Expected:** Reads its acceptance criteria and mapped design, examines shared controls and callers,
reuses a compatible existing component and tests changed behavior. Retains isolation, security,
accessibility and required database checks; adds no unrelated future capability or dependency.

## 25. Resume after a token limit or machine restart

**Request:** "Continue; the previous session hit its token limit." (QA already passed, complete
reports exist, relevant code/tests/context unchanged)

**Expected:** Reads the saved revision, evidence and unfinished handoff; checks relevant changes
and evidence integrity, reuses the pass and continues delivery. Does not spawn a QA agent or rerun
the product suites. A running command is inspected before another copy can start.

## 26. Skill-only edit after passed QA

**Request:** "Update the skill so you do not repeat QA without code changes."

**Expected:** Lead updates the canonical shared skill, validates its package and Claude/Codex
mirror, and preserves product QA. A metadata commit/new SHA or elapsed time does not trigger QA.

## 27. Bounded fix after a full pass

**Request:** "The confirmation error handling changed; verify the fix."

**Expected:** Independent QA assesses the changed paths and dependencies, verifies the affected
behavior and retains unrelated passing results. Records targeted versus base full evidence with
their actual revisions. Broad verification needs a concrete impact-based reason. An overbroad
closure fingerprint is reported as a tooling limit, not repaired by another unchanged full run.

## 28. Close a stage with complete developer evidence

**Request:** "The developer handed over F07-S02. Close it." (all exact gate commands passed;
complete logs, source identity and conditions are available; current relevant source is identical)

**Expected:** Lead inspects handoff, logs, relevant diff and conditions, reuses the gates, validates
and closes in the merge commit. No duplicate command or QA pass. When roles use different worktrees, resolves logs from the recorded originating checkout and
retains it through closure, or verifies transferred evidence before using it. Does not assume
ignored logs arrived with the merge or rerun solely because the lead's checkout lacks them.

## 29. Missing or changed stage proof

**Request:** "Close the stage using the old result." (one gate log is missing; a shared permission
helper changed after another gate passed; unrelated lint proof is intact)

**Expected:** Does not close on incomplete/stale proof. Runs the missing gate and affected
permission/dependency gates, retains valid unrelated results, and records why breadth is needed.
Does not silently substitute a focused test for a declared broader command.

## 30. A batch of related fixes

**Request:** "Fix these three account-screen bugs." (same stage, overlapping developer gates)

**Expected:** Developer reproduces and adds a meaningful failing test for each defect, fixes the
batch, deduplicates invalidated broader gates on final source, and hands all bugs to independent QA.
QA combines overlapping affected checks and retains a result for each bug. No per-bug full suite.

## 31. QA dry run while development continues

**Request:** "Build this feature; have QA prepare while the developer builds." (approved specs,
S01 closed, S02 in progress, S03 outlined; the developer uses the shared database and clock)

**Expected:** QA writes tests named by case for approved specs on the feature branch in its own
worktree, and dry-runs S01's tests (`sdlc run --stage F07-S01`) on an environment of its own,
repairing its own test defects. Records no `qa_status`, evidence or bug; reports a product defect
to the lead. Does not test S02, infer S03's contract or touch the developer's database or clock.
The feature pass waits for every stage to close.

## 32. Critical failure proof versus counting

**Request:** "Finish verification of these cases." (ordinary list-count assertion already passes;
critical cross-salon write case has a recorded genuine failure before the fix; new shared response
validator has never been exercised against an invalid response)

**Expected:** Retains nonempty-fixture and isolation assertions, reuses the actual regression failure,
and proves the new validator rejects a realistic faulty fixture. Does not invert every expected
count, edit application code as QA, or treat shared proof as coverage of distinct invariants.

## 33. Context and logs after a handoff

**Request:** "Continue the account fix." (same role, unchanged relevant docs still in context;
passing command display was truncated but complete saved log exists)

**Expected:** Inspects saved log and current diff, reuses intact context/evidence and proceeds.
Rereads changed, missing or previously unread context only. A fresh QA context still reads the
requirements independently. Does not rerun the command to obtain differently formatted output.

## 34. Delivery metadata and CI

**Request:** "Open the PR and finish delivery." (feature complete, push/PR authorised, no merge request)

**Expected:** Creates/reuses one PR, records and pushes required metadata before waiting for final
head CI, updates progress through the PR body, and leaves merge to the owner. Does not use a prior
green CI result for a newer head or change CI/protection to avoid waiting.

## 35. Bounded usability review

**Request:** "Verify the confirmation fix." (prior full pass and competitor review intact;
only error feedback changed; the reviewer has new aesthetic preferences)

**Expected:** Independently verifies affected behavior/design states and retains unrelated evidence.
Does not refresh the entire competitor survey or redesign working screens. New preferences remain
proposals; required failures still block closure.

## 36. A refactor is not a feature

**Request:** "Upgrade the web framework and reorganise the API's packages before the next feature."

**Expected:** Treats it as a refactor (decision 066): a decision entry and updated documents, a
`refactor/` branch, the developer's gates with every existing test unchanged and a fixture for each
new rule, the lead's review of the whole diff, smoke, and regression because delivered features are
touched, then one PR. Creates no feature, stage, cases or QA pass, and does not ask the owner
whether to run regression.

## 37. Which suite runs

**Request:** "The F07 PR is open." then "It's merged." then "Deploy to staging."

**Expected:** Smoke on the PR and again on the merged `main`; no regression for an ordinary feature
merge. Before the staging deploy, the `release` suite (load, production-like stack, image scans)
with the owner's explicit staging request in the current message. Each through
`qa/scripts/sdlc run --suite <name>`, within the budgets in `docs/qa/test-plan.md`.

## 38. Re-running after a fix

**Request:** "QA found 3 of 15 checks failing; the developer fixed them. Test it." Later, during a
bug fix to the deploy scripts: "Is it fixed?"

**Expected:** QA re-runs the 3 failed checks and only those of the other 12 whose code the fix
touched, naming the changed paths and why; the other passes stand. No whole suite or regression
because a fix landed. The developer iterates with fast, focused checks and does not repeat a load or
`release` check per adjustment; load checks run only when the owner asks (decision 069).

## CR-010 platform mapping evaluation

A fresh independent Codex context received the raw owner request and shared skill paths. It
resolved staff-only web, deferred React Native customer UI and M1 release ordering without
clarification, and identified stale test-plan, F01 design-map and browser-policy paragraphs.
Those paragraphs were corrected before verification. Claude uses the same symlinked skill source;
this evaluation exercised Codex only, with Claude packaging checked statically by validation.
