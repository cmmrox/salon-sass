---
name: salon-dev
description: Builds stages and fixes filed product bugs. Use for application code, migrations, contracts, infrastructure, unit or integration tests, stage-build and bug-fix. QA automation and project process changes belong to other roles.
---

# Building a stage

You are acting as the developer. The process (what a stage is, when you hand over, what you may
commit) is in `salon-sdlc`; load it first. This skill is how a developer works here. What to build
with, and the rules of this particular product, are not in this skill: they are under `docs/`.

## Read first, every time

1. The stage's `spec.md`, twice: once for scope, once for the acceptance criteria.
2. Every document its "Read first" section names, in full.
3. `docs/README.md`, for the row that matches each part of the product you are about to touch.
   When in doubt, read the document; the rules in it are requirements, not background.
4. The `AGENTS.md` in the application folder you are working in, when there is one.
5. For user-facing work, inspect the supplied design reference before coding. Record the mapped
   screen, responsive layout and visual states; a passing functional check does not prove design
   fidelity. Follow `docs/design/ui-ux-standards.md` for the project's reference and comparison gates.

When the documents do not cover something:

| Situation | Do |
| --- | --- |
| A document or the spec is silent on a detail, and the framework's default or the common practice is safe | Use it, and list the choice in `handoff.notes` so the lead can write it down |
| The gap affects security, isolation, money, stored data, a public contract, or what a user sees | Stop and ask the lead. Do not fill it with a guess |
| A document contradicts the spec, or two documents contradict each other | Stop and ask the lead |

## Habits for every change

1. **Search before creating.** Trace the affected callers, tests, fixtures and configuration.
   Reuse a compatible component, service, hook or utility, then the standard platform or an
   installed dependency. Introduce an abstraction when real callers need it; visual similarity
   alone does not establish a shared contract. Keep the change complete and easy to read.
2. **Follow the project's rules exactly where they are strict** (isolation, money, time, migrations,
   contracts, security) and use judgement where they leave room. The documents say which is which.
3. **Build one acceptance behavior at a time.** Update its contract and data model before its
   consumers, then connect the necessary layers so the behavior can be exercised. Short feedback
   loops find a wrong assumption before it spreads across the stage.
4. **Tests ship with the change.** Unit tests for rules, integration tests against the real kind of
   database the product uses, and the negative cases (wrong owner, wrong role, bad input) for
   anything new. Anything that stores or returns one organisation's data ships with the test that
   another organisation cannot reach it. A test asserts an outcome the spec promises; a test that
   still passes when the feature is removed is not a test. QA does not test your stage when you
   hand it over, so until the feature's QA pass these tests are all that guards it.
   For risky new logic and bug fixes, observe a meaningful failing test before implementing the
   fix. Assert promised behavior through stable interfaces, with independent expected values;
   retain database-level checks where persistence, isolation or integrity is the promise.
5. **Generated files are regenerated, never edited by hand,** and committed with the change that
   caused them.
6. **Nothing secret in code, logs, tests or commits.**
7. **Keep the change inside the stage.** No drive-by refactors and no behaviour the spec did not
   ask for. A dependency the spec and the project's stack already imply is fine; anything beyond
   that needs the lead's agreement first, and every new dependency is listed in `handoff.notes`.
8. **Use the installed versions' documentation** for anything you have not used in this repository.
   Frameworks change; memory is not a reference.

## Building a stage

1. Read, as above.
2. Set the status and branch as `stage-build` in `salon-sdlc` describes. Branch from the feature's
   integration branch; the lead integrates closed stages there. Follow `references/git-workflow.md`
   in `salon-sdlc`: unfinished stages do not merge into `main`.
3. Implement against the acceptance criteria, with tests alongside.
4. Run the stage's `verification.commands` for the changed implementation; on an unchanged resume,
   reuse complete passing evidence under `references/progress.md` in `salon-sdlc`.
   If one fails, diagnose before rerunning; a changed
   result without a known cause is unresolved. For reusable feature-closure evidence, follow
   `docs/operations/verification-evidence.md`; ordinary stage closure retains its own gates.
5. Start the product and walk through every acceptance criterion yourself, from outside: the
   screen, the endpoint, the command, including the criterion's negative case. Later stages are
   built on this one before QA sees it, so this walk-through is the stage's acceptance check. Do not
   hand over something you have not seen work.
6. Hand over as `stage-build` describes. In `handoff.notes`, account for each acceptance criterion:
   checked on the running product, or not checked and why. For screens, include reference-to-product
   comparison evidence at representative widths and resolve visual differences before handoff.
   Report any reference preview limitation explicitly. The lead closes the stage on that record.

For visual work, inspect the complete mapped screen/state set together, including representative
device sizes. Correct confirmed differences in one batch and confirm that batch. End refinement
when the approved design and acceptance criteria hold; a fresh defect needs diagnosis, while a
new design preference belongs to the lead's scope process.

## What you owe QA

QA tests the whole feature from outside, once every stage is closed, and cannot edit your code. A
stage that is hard to test is found out late, when it is most expensive. Make the product testable
as you build it:

- Give every interactive element a stable test id, named for what it is, not how it looks.
- Keep seed data deterministic.
- Provide the test hooks a stage needs, active only in local and test configurations: a
  controllable clock, a capture for outgoing messages, sandboxes for external providers.
- Return errors with a stable machine-readable code, so a test can assert the reason and not the
  wording.
- Fill in `handoff` honestly, including what is not finished and any acceptance criterion you could
  not check. QA starts the feature pass from the handoffs of all its stages.

## Fixing a bug from QA

Bugs arrive together, after the feature's QA pass, and may be in any of its stages. Reproduce each
one first, compare a working path, and trace the failing input to its cause. Test one hypothesis
at a time. Add a unit or integration test that fails for the reason in the bug, then fix
the cause, not the symptom the test happens to check. Never edit anything under `qa/`. If the test
or the expectation is wrong, say so in `developer_note` on the bug and leave it for QA to decide.
When repeated fixes fail, revisit the cause and affected interfaces with the lead before another
patch. Record the failing and passing evidence and any unchecked behavior in the handoff.

## When reality differs from a document

You do not edit the documents under `docs/` (except generated ones such as the API document) or the
skills. When what you had to build differs from what a document says, or a document is missing
something the next developer will need, write what and why in `handoff.notes`. The lead corrects the
document before the stage is closed.

## Stop and ask

Ask the lead when the spec contradicts a requirement or a document, when an acceptance criterion
cannot be met as written, when the work needs a new dependency or system software, when a decision
the project lists as open blocks the work, or when the right fix is outside the stage's scope. Do
not widen the stage yourself.
