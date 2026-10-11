# Designing the tests for a stage

How to decide what a stage needs and turn it into cases. The catalogue of test types for this
product, with the trigger for each and what each must assert, is `docs/qa/test-types.md`.

## Choosing the test types

1. Read the spec's "Risks and test focus". It is the lead's minimum.
2. Go down the selection table in `docs/qa/test-types.md`. Every row whose trigger is true for this
   stage applies; add the ones the spec did not list.
3. Where the spec deliberately narrows something (for example fewer screen widths for a throwaway
   screen), follow the spec and note it.
4. If you rule out a type whose trigger is true, say why. Types the product's catalogue marks as
   always required are never ruled out and never deferred where they apply.
5. Write the choices, and what was ruled out, in the "Test approach" table at the top of `cases.md`.

Some types need the owner's go-ahead before they are run; the catalogue lists them.

## From acceptance criteria to cases

- Every acceptance criterion gets at least one case. A criterion that can be violated gets a
  negative case as well as a positive one.
- Then go beyond the criteria. For each thing the stage lets someone do, ask:
  - **Who else?** Another organisation's user, a lower role, someone signed out.
  - **Twice?** The same action repeated, submitted twice, replayed.
  - **At the same time?** Two people on the same thing at the same instant.
  - **At the edges?** Nothing, the minimum, the maximum, one too many, the wrong kind, very long.
  - **Interrupted?** A dropped connection, a refresh, the back button, a second tab, a dependency
    that is down or slow.
  - **Later?** After the session expired, after the data changed underneath, across midnight.
  A case that the spec never thought of and that fails is the most valuable thing a pass produces.
  If the right behaviour is not stated anywhere, ask the lead; do not invent the expectation.
- One case proves one thing. Its title is a statement that is true when it passes ("A second
  customer cannot hold a slot that is already held").
- Give each case a type from the catalogue, a priority, and the criteria it covers.

| Priority | Meaning |
| --- | --- |
| `critical` | If this fails, someone's data, money or appointment is wrong |
| `high` | An acceptance criterion or a main path |
| `medium` | A secondary path or an edge that has a workaround |
| `low` | Cosmetic or rare |

## Writing a case

Use `assets/cases.md`. A case another person could run without asking you has: who performs it and
as which role, what must exist first, exact steps, and an expected result that says what must
happen and what must not. "Works correctly" is not an expected result.

Declare every case under `cases:` in `stage.yaml` with the same id as its heading in `cases.md`.

## Automated, manual or deferred

| Outcome | When | Record |
| --- | --- | --- |
| Automated | Whenever it can be | The file under `verification.automation`, covering the case |
| Manual | It needs a person's judgement, or automating it would cost far more than it protects | `verification.manual` with the reason and the date it was performed |
| Deferred | What it tests cannot exist yet: it depends on a later stage, or on an event outside this stage that the spec's "Done criteria" or the lead has acknowledged | `verification.deferred` with the reason and what unblocks it: a stage id, or the event |

Deferred and blocked are different. Deferred: the thing under test does not exist yet, and everyone
has agreed it will be tested later. Blocked (`qa_status: blocked`): the thing exists and could be
tested, but this environment lacks something. Never defer a case to get past a block, and never
defer on your own authority for an event the spec does not mention: ask the lead.

A deferred case is not forgotten. `qa/coverage.md` lists every one, and `feature-verify` and
`release-prepare` go through that list.
