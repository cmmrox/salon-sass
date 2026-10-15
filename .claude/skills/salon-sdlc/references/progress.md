# Progress and handoffs

## Start and update

State the feature/stage, role, intended outcome and next check at the start. During active work,
give a compact update about every 30–60 seconds and whenever a meaningful step completes, a
failure changes the plan, or a blocker appears. The lead remains responsible for owner-visible
updates while another agent runs; each delegated role sends the lead the same information.

An update answers:

- **Current:** feature/stage, role and the concrete step running.
- **Completed:** observed work or checks, with counts when available.
- **Remaining:** the next step and what it will establish.
- **Blocker:** the observed cause, its effect and the action resolving it, when one exists.

Use plain language. Explain what a check establishes instead of streaming raw implementation
details. Label automation groups separately from individual cases. Separate implementation,
diagnosis, independent QA and delivery: finished code is not a passed feature or a merged PR.
Use counts tied to a defined denominator; report elapsed time rather than guessed percentages
or an unsupported completion estimate. When nothing has changed, name the command still running,
its elapsed time and the next observable result without implying new progress.
At handoff, record observed time for implementation, automation preparation, verification,
diagnosis/rework and delivery waiting where available. Use existing logs and handoff notes;
label missing timings unknown rather than creating a separate tracking system.

## Read and retain relevant context

Read the current spec, acceptance criteria and named project rules before acting. Load relevant
sections with enough surrounding context to understand their constraints; read a whole document
when its rules span the work or its dependencies are unclear. Do not skim away security, ownership,
data integrity or platform requirements to save time.

Within a continuing role session, reuse unchanged material that is still available in context.
Read again when it changed, was truncated or lost, or a new area becomes relevant. A fresh role
context reads its own authoritative brief; the developer's conclusions never replace QA's reading
of requirements. Keep handoffs concise and link evidence instead of copying documents or logs.

## Long-running commands

Use bounded waits so the lead can communicate while a command runs. Keep the original process
and its evidence across updates. Read the runner's current entry, completed counts and elapsed
time; use `docs/operations/verification-evidence.md` for this project's tooling. Report missing
tools, stopped processes and capacity limits promptly. Keep secrets and private data out of chat.
Capture complete output once and inspect the saved log for diagnosis; do not repeat execution just
to change output formatting or recover a truncated display. Reuse and refine the existing test or
reproduction script instead of creating near-identical variants.

## Interruptions and resuming

New owner instructions steer ongoing work. Preserve its branch, edits, running-process identity,
completed evidence and remaining steps. Before resuming, inspect those records and the process
state; continue from the last valid boundary. An interruption alone is not a reason to rebuild
or rerun completed checks. This includes a token/context limit, compaction, a new agent or client
session, a machine restart and a later "continue" message.

### Reuse before rerunning

Before starting QA or developer gates, inspect the saved result and tested revision, compare the
relevant working-tree changes (including untracked files), and check the recorded test conditions.
If the relevant application code, tests, requirements and test conditions are unchanged and the
complete passing evidence is intact, reuse it and continue with the next unfinished step. Do not
restart QA because the session restarted, a commit SHA/date changed, or only review, documentation,
skill or status metadata changed. A skill edit receives its own validation, not product QA.

This applies at stage handoff and closure as well as feature closure. During implementation use
focused tests for short feedback loops. Before handoff, every declared developer gate must have
complete passing evidence for the final relevant source and conditions. The lead validates that
evidence rather than rerunning it. Missing or invalidated gates must run; diagnose and correct a
failed gate before a meaningful rerun. A narrower test
does not silently replace a declared gate. The project's evidence document defines the record.

For a batch of related fixes, reproduce and test each defect, then deduplicate the affected stages'
gate commands and run each invalidated command once after the batch is ready. Keep unrelated valid
results. Shared changes, uncertain impact or changed runtime conditions require broader checks
with a stated reason; cost or elapsed time never justifies dropping required proof.

When something relevant changed, name the change and the checks it affects before execution.
Retain unaffected passing results; independently verify a fix and its affected dependencies.
Repeat a whole feature only when its affected scope warrants that breadth, or when the owner
explicitly requests it. A failed or incomplete check is unfinished work; finish that scope without
discarding unrelated completed evidence. Never describe targeted checks as a new full-feature pass.

Persist a short checkpoint in the existing handoff/evidence record: tested revision, report/log
paths, completed results, remaining work and relevant environment/fixture identity. Verify a
surviving runner's identity before starting another. Missing or uncertain proof calls for a
specific inspection first; do not assume that all QA must start over. Follow the project's
evidence documentation for integrity and tooling limits. Repeating a check without an identified
invalidating change or an explicit owner request wastes time and tokens without new evidence.

When priorities change, let an already running command reach a safe boundary if appropriate and
report what remains in progress. Resume dependent work only within the owner's current scope.

## Completion

Lead with the delivered behavior and the evidence proving it. State the tested revision, relevant
results and material limitations. Distinguish checks not run, explicitly deferred work and
remaining owner actions. Provide the reviewable artifact or PR when one exists. Completion
claims rest on complete evidence for the current scope, never on a prediction or a partial run.
