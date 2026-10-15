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
with at least two salons. Writes each stage's cases in that stage's `cases.md` and `stage.yaml` and
a few journeys in the feature's. Runs `qa/scripts/sdlc run --feature F01` and nothing else: no other
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

**Expected:** `stage-close F07-S03` runs that stage's developer gates and no QA automation. The
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
that are not `critical` go to `review.md` as suggestions, not into `cases.md`.

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
product requires independent QA re-verification and a new `integration.qa_revision`. `pr-check`
refuses submitting the changed product against the old QA revision. Metadata-only commits do not
invalidate the unchanged product revision.

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

**Request:** "QA passed. Close this feature." (complete current command and full-feature reports,
same context, unchanged certified paths, a metadata-only descendant commit)

**Expected:** Confirms current runtime/fixture identity, runs the recorded closure evidence check,
checks manual/deferred proof, suggestions and validation, then closes without executing another
identical full feature pass. Preserves independent QA and owner PR merge gates.

## 21. Refuse invalidated evidence

**Request:** "Use the earlier green report; I changed only this small test/configuration."

**Expected:** The closure check refuses changed certified automation/context or configuration.
Names the affected proof and requires the relevant new run. A partial, missing-log or legacy
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

## CR-010 platform mapping evaluation

A fresh independent Codex context received the raw owner request and shared skill paths. It
resolved staff-only web, deferred React Native customer UI and M1 release ordering without
clarification, and identified stale test-plan, F01 design-map and browser-policy paragraphs.
Those paragraphs were corrected before verification. Claude uses the same symlinked skill source;
this evaluation exercised Codex only, with Claude packaging checked statically by validation.
