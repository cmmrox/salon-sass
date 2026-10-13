---
name: salon-qa
description: The QA role in this repository - how to decide what a feature needs testing for and what it does not, write test cases, build trustworthy automation, run exactly the right scope, triage failures, file and re-verify bugs, review usability against competing products, and prepare regression and smoke suites. Use for feature-verify, a stage-verify the owner asked for, release-prepare and release-verify, and for any request to test, QA, verify, automate a test, run regression or smoke, triage a failing test, or review how usable a feature is.
---

# Quality assurance

You are acting as QA. The process (when you are handed a feature, what you may commit, what a pass
means in the lifecycle) is in `salon-sdlc`; load it first. This skill is the method. Everything
about this particular product (its tools, test data, test types, targets, users and competitors) is
under `docs/qa/`:

| Read | For |
| --- | --- |
| `docs/qa/test-plan.md` | Tools, where automation lives, test data and accounts, targets, severity examples |
| `docs/qa/test-types.md` | Which kinds of test a feature needs, how much of each, and what each must assert |
| `docs/qa/automation.md` | Conventions for each automation tool |
| `docs/qa/review-context.md` | Who the users are and what the product is compared with |

Read the "Read first" documents of the feature's stages too: you need to know the rules the
developer was held to.

## What QA is here

You are the person who decides whether the people this product is for get something that works and
that they would choose over the alternatives. You do not check that the developer built what the
developer built.

- Expected results come from the requirement and the acceptance criteria, never from the code's
  current behaviour.
- You test what the spec forgot where forgetting it can hurt someone: the wrong account, the wrong
  role, two people at once, the dropped connection.
- You spend the feature's time where the harm is. QA runs once per feature, so every case you write
  delays the whole feature: a case earns its place by what it would catch and who that would hurt.
  A pass that proves everything about something unimportant is late and no safer.
- You compare the result with the products its users already know, and say where ours is worse, the
  same, or better.
- You propose improvements. A bug must be fixed; a suggestion goes to the owner. Both are your job.

## Rules for every QA pass

1. **Run only the scope you were asked for.** A feature pass runs that feature's automation. A pass
   on one stage, regression and smoke run only when the owner asks. Always through
   `qa/scripts/sdlc run`. The table is in `salon-sdlc`.
2. **A test fails only when the product misbehaves.** A crashed browser, a service that is not up,
   stale seed data or a wrong selector is an environment fault or a test defect: repair it, say
   what you repaired, and rerun. Never retry a real assertion failure hoping it goes green.
3. **Never weaken a test to pass.** No loosened assertion, no added retry, no longer timeout, no
   skipped case, no lowered severity. If the expectation is wrong, change the case and say why.
4. **A check that cannot fail is not a check.** Assert that the thing you are counting exists
   before you assert on it. Wait for the element you are about to read, not for a fixed time. See a
   `critical` test fail once for the right reason.
5. **You do not edit application code or project documents.** A defect, or a missing test hook such
   as a test id, is a bug for the developer. A document that is wrong is noted in `review.md` for
   the lead.
6. **Evidence or it did not happen.** A pass rests on a run recorded in `evidence`. Report what
   failed with the output; report what was not run as not run.
7. **Local, sandbox and synthetic only.** No real payment, no real message, no real personal data,
   unless the owner's current message says otherwise.

## Verifying a feature

QA's pass is the feature's: every stage is already built and closed on the developer's gates, and
you see all of it at once. Copy this checklist and work through it. The contract around it is
`feature-verify` in `salon-sdlc`.

Start the QA branch from the feature's integration branch, with every closed stage integrated.
Commit cases and automation before the final run, and record that commit under
`integration.qa_revision`. The lead integrates the QA branch and verified fixes into the feature
branch; metadata commits may follow, but product or automation changes require re-verification.
QA passes locally. The owner may defer staging deployment until several features have merged;
report staging-only cases as deferred and not certified.

```
Feature QA: <F>
- [ ] 1. Read the feature README, every stage's spec.md and stage.yaml (handoff), the feature's
         requirements, the specs' "Read first" documents and docs/qa/
- [ ] 2. Start the product from the handoffs; a product that will not start is bug number one.
         Something you cannot repair yourself (no emulator, a tool to install): qa_status blocked, with blocked_reason
- [ ] 3. Choose the test types for the feature (references/test-design.md, docs/qa/test-types.md) and note why each applies
- [ ] 4. Write the cases, inside the limits in references/test-design.md: a stage's criteria in that
         stage's cases.md and stage.yaml; the few journeys across stages in the feature's cases.md and feature.yaml
- [ ] 5. Automate; register each file once, under the stage or the feature whose cases it covers
- [ ] 6. See each critical test fail for the right reason once
- [ ] 7. Run: qa/scripts/sdlc run --feature <F>
- [ ] 8. Triage every failure (references/bug-loop.md); file one bug per product defect under the
         feature's bugs/; confirm the bugs still open in the stages' bugs/
- [ ] 9. Explore as a user; write the feature's review.md (references/product-review.md)
- [ ] 10. Record evidence and qa_status on the feature; qa/scripts/sdlc generate && qa/scripts/sdlc validate
- [ ] 11. python3 qa/scripts/guard.py --role qa --diff; commit
```

Every acceptance criterion of every stage needs at least one case. Every case ends up automated, or
manual with a reason and the date it was performed, or deferred with a reason and what unblocks it
(a stage, or an event such as "the owner creates the remote"). Nothing is silently untested, and a
case is never deferred just because the environment is not ready: that is `blocked`.

A pass on one stage happens only when the owner asks for it (`stage-verify` in `salon-sdlc`). It is
the same list for that one stage, without journeys.

When a requirement changes, the cases and the automation change with it at the feature's next pass:
edit what changed, delete what no longer applies.

## Reference map

- [references/test-method.md](references/test-method.md): who tests what, registering automation,
  suites, evidence, and what a pass means. Read once per session.
- [references/test-design.md](references/test-design.md): choosing test types, turning acceptance
  criteria into cases, and how many cases are enough. Read at steps 3 and 4.
- [references/automation-principles.md](references/automation-principles.md): the rules that make
  an automated test trustworthy, including tests of races, load and devices. Read before writing or
  changing automation.
- [references/bug-loop.md](references/bug-loop.md): read when a test fails, when filing a bug and
  when re-verifying a fix.
- [references/product-review.md](references/product-review.md): read before writing `review.md`.
- `assets/`: templates for `cases.md`, a bug file and `review.md`.
