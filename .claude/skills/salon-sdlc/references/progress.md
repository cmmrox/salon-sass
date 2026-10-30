# Progress and handoffs

## Start and update

State the feature or stage, your role, the intended outcome and the next check. While working, give
a short update about every minute and whenever a step completes, a failure changes the plan or a
blocker appears. The lead reports to the owner while other agents run; each agent reports to the lead.

An update says:

- **Current:** the scope, role and the step running.
- **Completed:** what was observed or checked, with counts against a stated total.
- **Remaining:** the next step and what it will establish.
- **Blocker:** the observed cause, its effect and what resolves it.

Use plain language: say what a check establishes, not raw output. Keep implementation, diagnosis,
QA and delivery apart: finished code is not a passed feature or a merged PR. Report elapsed time,
not guessed percentages. At handoff, note the observed time spent building, testing, diagnosing and
waiting where the logs show it; say "unknown" otherwise.

## Read and keep context

Read the current spec, its criteria and the documents it names before acting, with enough context
to understand their constraints; read the whole document when its rules span the work. Do not skim
past security, ownership, data integrity or platform rules. In a continuing session reuse what is
unchanged; read again what changed, was truncated or is newly relevant. A fresh QA context reads its
own brief: the developer's conclusions never replace QA's reading of the requirements.

## Long-running commands

Wait in bounded intervals so the lead can keep the owner informed. Read progress from the
command's own record (the runner's `summary.json`, a log) instead of rerunning it. Report missing
tools, stopped processes and capacity limits at once. Capture output once and inspect the saved
log; never rerun to change formatting or recover a truncated display. Refine an existing test or
reproduction instead of writing near-copies. Keep secrets out of chat.

## Interruptions and resuming

New owner instructions steer the work; keep its branch, edits, running processes, completed results
and remaining steps. A token limit, compaction, a new session, a restart or "continue" is not a
reason to rerun anything: inspect what was done and continue from the last finished step.

**Reuse before rerunning.** Before running developer gates or QA, compare the saved result's tested
revision with the relevant changes (including untracked files) and its conditions. Unchanged
source, tests, requirements and conditions with an intact passing result mean the result stands;
a documentation, skill or status change never invalidates product proof. The rule and the paths
are in `docs/operations/verification-evidence.md`.

When something relevant changed, name it and the checks it affects, then run those, keeping
unaffected results. For a batch of fixes, test each fix, then run each affected gate once.
After a fix or a test repair, run only the checks that failed and the checks whose code the change
touched; a check that passed on code the change did not touch stays passed. A slow check (load, the
production-like stack, a whole suite) is never the loop for trying fixes: diagnose with fast,
focused measurements, and run the slow check once on the final source, and a load check only when
the owner asks. Repeating a 15-minute load check after each adjustment of one fix, and whole
regressions after small test repairs, once cost hours and proved nothing new.
Uncertain impact, shared code or changed conditions call for broader checks with a stated reason;
cost never justifies dropping required proof. A targeted run is never reported as a full pass.
Repeat a whole feature only when the change is broad or the owner asks.

Persist a short checkpoint in the handoff or evidence record: tested revision, report paths,
results, what remains. Before starting a run, make sure an earlier one is not still alive.

## Completion

Lead with the delivered behaviour and the evidence for it: tested revision, results and material
limits. Name what was not run, what is deferred and what the owner must do. Give the PR when there
is one. A completion claim rests on complete evidence for the current scope, never on a prediction.
