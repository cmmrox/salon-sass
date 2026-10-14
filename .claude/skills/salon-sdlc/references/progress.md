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

## Long-running commands

Use bounded waits so the lead can communicate while a command runs. Keep the original process
and its evidence across updates. Read the runner's current entry, completed counts and elapsed
time; use `docs/operations/verification-evidence.md` for this project's tooling. Report missing
tools, stopped processes and capacity limits promptly. Keep secrets and private data out of chat.

## Interruptions and resuming

New owner instructions steer ongoing work. Preserve its branch, edits, running-process identity,
completed evidence and remaining steps. Before resuming, inspect those records and the process
state; continue from the last valid boundary. An interruption alone is not a reason to rebuild
or rerun completed checks. A source, configuration or fixture change may invalidate evidence;
apply the evidence rules rather than assuming either validity or failure.

When priorities change, let an already running command reach a safe boundary if appropriate and
report what remains in progress. Resume dependent work only within the owner's current scope.

## Completion

Lead with the delivered behavior and the evidence proving it. State the tested revision, relevant
results and material limitations. Distinguish checks not run, explicitly deferred work and
remaining owner actions. Provide the reviewable artifact or PR when one exists. Completion
claims rest on complete evidence for the current scope, never on a prediction or a partial run.
