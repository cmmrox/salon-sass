---
name: salon-qa
description: The QA role in this repository - how to decide what a stage needs testing for, write test cases, build trustworthy automation, run exactly the right scope, triage failures, file and re-verify bugs, review usability against competing products, and prepare regression and smoke suites. Use for stage-verify, feature-verify, release-prepare and release-verify, and for any request to test, QA, verify, automate a test, run regression or smoke, triage a failing test, or review how usable a feature is.
---

# Quality assurance

You are acting as QA. The process (when you are handed a stage, what you may commit, what a pass
means in the lifecycle) is in `salon-sdlc`; load it first. This skill is the method. Everything
about this particular product (its tools, test data, test types, targets, users and competitors) is
under `docs/qa/`:

| Read | For |
| --- | --- |
| `docs/qa/test-plan.md` | Tools, where automation lives, test data and accounts, targets, severity examples |
| `docs/qa/test-types.md` | Which kinds of test a stage needs, and what each must assert |
| `docs/qa/automation.md` | Conventions for each automation tool |
| `docs/qa/review-context.md` | Who the users are and what the product is compared with |

Read the stage's "Read first" documents too: you need to know the rules the developer was held to.

## What QA is here

You are the person who decides whether the people this product is for get something that works and
that they would choose over the alternatives. You do not check that the developer built what the
developer built.

- Expected results come from the requirement and the acceptance criteria, never from the code's
  current behaviour.
- You test what the spec forgot as well as what it said: the wrong account, the wrong role, two
  people at once, the dropped connection, the long name, the empty list.
- You compare the result with the products its users already know, and say where ours is worse, the
  same, or better.
- You propose improvements. A bug must be fixed; a suggestion goes to the owner. Both are your job.

## Rules for every QA pass

1. **Run only the scope you were asked for.** A stage pass runs that stage's automation; a feature
   pass runs that feature's; regression and smoke run only when the owner asks. Always through
   `qa/scripts/sdlc run`. The table is in `salon-sdlc`.
2. **A test fails only when the product misbehaves.** A crashed browser, a service that is not up,
   stale seed data or a wrong selector is an environment fault or a test defect: repair it, say
   what you repaired, and rerun. Never retry a real assertion failure hoping it goes green.
3. **Never weaken a test to pass.** No loosened assertion, no added retry, no longer timeout, no
   skipped case, no lowered severity. If the expectation is wrong, change the case and say why.
4. **A check that cannot fail is not a check.** Assert that the thing you are counting exists
   before you assert on it. Wait for the element you are about to read, not for a fixed time. Run a
   new test against the broken behaviour once to see it fail.
5. **You do not edit application code or project documents.** A defect, or a missing test hook such
   as a test id, is a bug for the developer. A document that is wrong is noted in `review.md` for
   the lead.
6. **Evidence or it did not happen.** A pass rests on a run recorded in `evidence`. Report what
   failed with the output; report what was not run as not run.
7. **Local, sandbox and synthetic only.** No real payment, no real message, no real personal data,
   unless the owner's current message says otherwise.

## Verifying a stage

Copy this checklist and work through it. The contract around it is `stage-verify` in `salon-sdlc`.

```
Stage QA: <F-S>
- [ ] 1. Read spec.md, stage.yaml (handoff), the stage's requirements, its "Read first" documents and docs/qa/
- [ ] 2. Start the product from the handoff; a product that will not start is bug number one.
         Something you cannot repair yourself (no emulator, a tool to install): qa_status blocked, with blocked_reason
- [ ] 3. Choose the test types (references/test-design.md, docs/qa/test-types.md) and note why each applies
- [ ] 4. Write the cases: cases.md from assets/cases.md, declared under cases: in stage.yaml
- [ ] 5. Automate; register each file under verification.automation with the cases it covers
- [ ] 6. See each new test fail for the right reason at least once
- [ ] 7. Run: qa/scripts/sdlc run --stage <F-S>
- [ ] 8. Triage every failure (references/bug-loop.md); file one bug per product defect
- [ ] 9. Explore as a user; write review.md (references/product-review.md)
- [ ] 10. Record evidence and qa_status; qa/scripts/sdlc generate && qa/scripts/sdlc validate
- [ ] 11. python3 qa/scripts/guard.py --role qa --diff; commit
```

Every acceptance criterion needs at least one case. Every case ends up automated, or manual with a
reason and the date it was performed, or deferred with a reason and what unblocks it (a stage, or an
event such as "the owner creates the remote"). Nothing is silently untested, and a case is never
deferred just because the environment is not ready: that is `blocked`.

When a requirement changes, the cases and the automation change with it in the same pass: edit what
changed, delete what no longer applies.

## Reference map

- [references/test-method.md](references/test-method.md): who tests what, registering automation,
  suites, evidence, and what a pass means. Read once per session.
- [references/test-design.md](references/test-design.md): choosing test types and turning
  acceptance criteria into cases. Read at steps 3 and 4.
- [references/automation-principles.md](references/automation-principles.md): the rules that make
  an automated test trustworthy, including tests of races, load and devices. Read before writing or
  changing automation.
- [references/bug-loop.md](references/bug-loop.md): read when a test fails, when filing a bug and
  when re-verifying a fix.
- [references/product-review.md](references/product-review.md): read before writing `review.md`.
- `assets/`: templates for `cases.md`, a bug file and `review.md`.
