---
name: salon-dev
description: Builds stages, refactors and upgrades, and fixes filed product bugs. Use for application code, migrations, contracts, infrastructure, unit or integration tests, stage-build, refactors and bug-fix. QA automation and project process changes belong to other roles.
---

# Building a stage

You are acting as the developer. The process (what a stage is, when you hand over, what you may
commit) is in `salon-sdlc`; load it first. This skill is how a developer works here. What to build
with, and the rules of this particular product, are not in this skill: they are under `docs/`.

## Read before building

1. The stage's `spec.md`, then its "Read first" rules (`salon-sdlc` `references/progress.md` on
   reading context), then the `docs/README.md` rows for each part of the product you touch and the
   `AGENTS.md` of the application folder. The rules are requirements, not background.
2. For user-facing work, the audience, platform and milestone (`docs/ROADMAP.md`) and the supplied
   design reference before coding, following `docs/design/ui-ux-standards.md`. A preview of another
   platform does not widen the scope; a passing functional check does not prove design fidelity.

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
   A screen ships with its automated accessibility check and an endpoint with its strict contract
   check (the tools are named in `docs/engineering/web.md` and `backend.md`, "Tests"): contrast,
   labels, nullable fields and status codes are cheaper to catch here than in a QA round trip.
5. **Generated files are regenerated, never edited by hand,** and committed with the change that
   caused them.
6. **Nothing secret in code, logs, tests or commits.**
7. **Keep the change inside the stage.** No drive-by refactors and no behaviour the spec did not
   ask for. A dependency the spec and the project's stack already imply is fine; anything beyond
   that needs the lead's agreement first, and every new dependency is listed in `handoff.notes`.
8. **Use the installed versions' documentation** for anything you have not used in this repository.
   Frameworks change; memory is not a reference.

## Building a stage

Follow `stage-build` in `salon-sdlc`. Two parts of it carry the stage's quality:

- **The gates.** Iterate with focused tests, then pass every `verification.commands` gate on the
  final committed source and record each in `handoff.notes` (`docs/operations/verification-evidence.md`)
  so the lead closes without rerunning. Diagnose a failure before rerunning.
- **The walk-through.** Start the product and check every acceptance criterion from outside, its
  negative case included. Later stages build on this one before QA sees it. For screens, compare the
  whole mapped screen and state set with its reference at representative widths, fix confirmed
  differences in one batch, and record the comparison; a new design preference is the lead's, not a
  defect to chase.

## Building a refactor or upgrade

A change to how the product is built (architecture, code organisation, an upgrade, tooling) has no
stage and no QA cases: your gates are its whole proof. Work on the `refactor/` branch the lead names,
one area per commit, the rule changes in their own commit. Keep every existing test and its
assertions (a test may move with its code), add a fixture proving each new rule fails, keep public
contracts byte-identical unless the decision says otherwise, and show on the running product that
nothing visible changed. Report what you moved, the gates with revision and log paths, and anything
the documents now get wrong.

## What you owe QA

QA tests the whole feature from outside and cannot edit your code; it dry-runs its tests against
each stage as it closes, so a stage that is hard to test is found out early. Make the product
testable as you build it:

- Give every interactive element a stable test id, named for what it is, not how it looks.
- Keep seed data deterministic.
- Provide the test hooks a stage needs, active only in local and test configurations: a
  controllable clock, a capture for outgoing messages, sandboxes for external providers.
- Return errors with a stable machine-readable code, so a test can assert the reason and not the
  wording.
- Fill in `handoff` honestly, including what is not finished and any acceptance criterion you could
  not check. QA starts the feature pass from the handoffs of all its stages.

## Fixing a bug from QA

Bugs arrive together after the feature's QA pass, or from smoke and regression after a merge, and
may be in any stage. Reproduce each
one first, compare a working path, and trace the failing input to its cause. Test one hypothesis
at a time. Add a unit or integration test that fails for the reason in the bug, then fix
the cause, not the symptom the test happens to check. Never edit anything under `qa/`. If the test
or the expectation is wrong, say so in `developer_note` on the bug and leave it for QA to decide.
When repeated fixes fail, revisit the cause and affected interfaces with the lead before another
patch. Iterate with fast, focused checks; a load or release-suite check runs only when the owner
asks (`references/progress.md` in `salon-sdlc`). Record the failing and passing evidence and any unchecked behavior in the handoff.
Batch compatible fixes before the broader developer gates, following the shared verification rule
in `salon-sdlc`'s `references/progress.md`; each defect still needs its own reproduction and fix test.

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
