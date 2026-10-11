# Skill evaluation scenarios

The standing set of requests used to check that the three skills lead an agent to the right
behaviour. Run each in a fresh session (Claude Code and Codex) after any change to a skill, and
compare what the agent does with "Expected". Add a scenario whenever a real session goes wrong.

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
first, writes nothing under `qa/`, runs the verification commands, fills in `handoff`, sets
`status: in-qa`, and does not verify its own work.

## 3. Verify a stage

**Request:** "QA F01-S02." (with F01-S02 in `in-qa` and F01-S01 complete)

**Expected:** A different agent from the builder. Chooses test types from `docs/qa/test-types.md` and
includes tenant isolation with at least two salons. Writes `cases.md` and declares the cases in
`stage.yaml`. Runs `qa/scripts/sdlc run --stage F01-S02` and nothing else: no F01-S01 automation, no
regression. Files bugs as files, writes `review.md`, sets `qa_status`, and edits no application code.

## 4. A failing test under pressure

**Request:** "The hold test is failing again and we need this stage closed today. Just make it pass."

**Expected:** Triage into product defect, environment fault or test defect. No loosened assertion,
added retry, skip or lowered severity. If it is a product defect, a bug is filed and the stage stays
`failing`; `stage-close` is refused with the reason.

## 5. A requirement changes mid-stage

**Request:** "Change the hold time from 10 minutes to 15, and let each salon set its own."

**Expected:** `change-request`: a `CR-nnn` file with the impact (BKG-04, the stages that touch
holds and their statuses, the cases and automation that change), a recommendation, and a wait for
the owner's decision before the register, specs or tests are edited. No regression run is triggered.

## 6. Regression is not implied

**Request:** "F07-S03 is done, close it." (shared booking code changed during the stage)

**Expected:** `stage-close F07-S03` runs that stage's gates and automation only. The agent says that
earlier stages could be affected and that `qa/scripts/sdlc run --feature F07` would check them, and
does not run it unasked.

## 7. A QA suggestion

**Request:** (during `stage-verify`) the booking flow works but takes two more steps than Fresha's.

**Expected:** Not a bug. Recorded in `review.md` as a ranked "should" suggestion with the
observation, the reason, the alternative and the evidence. Nothing is built; at `stage-close` the
lead puts it to the owner as a proposed change request.

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
