---
name: salon-dev
description: The developer role in this repository - how to build a stage from its spec, what to read first, the engineering habits every change must follow, how to check and hand over a stage that QA will not test until the feature is finished, and how to fix a bug QA filed. Use when writing, changing or reviewing application code, database migrations, API contracts, infrastructure or unit and integration tests, when running stage-build or bug-fix, and when a spec or a project document turns out to be wrong.
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

1. **Search before creating.** Reuse the existing component, service, hook or utility. Two things
   that look 70% alike are one thing with a parameter.
2. **Follow the project's rules exactly where they are strict** (isolation, money, time, migrations,
   contracts, security) and use judgement where they leave room. The documents say which is which.
3. **Change the contract and the data model first**, then build inward to outward: domain rules,
   service, API, then screens.
4. **Tests ship with the change.** Unit tests for rules, integration tests against the real kind of
   database the product uses, and the negative cases (wrong owner, wrong role, bad input) for
   anything new. Anything that stores or returns one organisation's data ships with the test that
   another organisation cannot reach it. A test asserts an outcome the spec promises; a test that
   still passes when the feature is removed is not a test. QA does not test your stage when you
   hand it over, so until the feature's QA pass these tests are all that guards it.
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
4. Run the stage's `verification.commands` until green.
5. Start the product and walk through every acceptance criterion yourself, from outside: the
   screen, the endpoint, the command, including the criterion's negative case. Later stages are
   built on this one before QA sees it, so this walk-through is the stage's acceptance check. Do not
   hand over something you have not seen work.
6. Hand over as `stage-build` describes. In `handoff.notes`, account for each acceptance criterion:
   checked on the running product, or not checked and why. For screens, include reference-to-product
   comparison evidence at representative widths and resolve visual differences before handoff.
   Report any reference preview limitation explicitly. The lead closes the stage on that record.

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
one first. Add a unit or integration test that fails for the reason in the bug, then fix
the cause, not the symptom the test happens to check. Never edit anything under `qa/`. If the test
or the expectation is wrong, say so in `developer_note` on the bug and leave it for QA to decide.

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
