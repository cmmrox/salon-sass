# Designing the tests for a feature

How to decide what a feature needs and turn it into cases, and where to stop. The catalogue of test
types for this product, with the trigger for each, how much of each is enough and what each must
assert, is `docs/qa/test-types.md`.

## Contents

- Choosing the test types
- From acceptance criteria to cases
- Where to stop
- Writing a case
- Automated, manual or deferred

## Choosing the test types

1. Read "Risks and test focus" in each stage's spec. Together they are the lead's minimum.
2. Go down the selection table in `docs/qa/test-types.md`. Every row whose trigger is true for the
   feature applies; add the ones the specs did not list.
3. Where a spec deliberately narrows something (for example fewer screen widths for a throwaway
   screen), follow the spec and note it.
4. If you rule out a type whose trigger is true, say why. Types the product's catalogue marks as
   always required are never ruled out and never deferred where they apply.
5. Write the choices, and what was ruled out, in the "Test approach" table at the top of the
   feature's `cases.md`.

Some types need the owner's go-ahead before they are run; the catalogue lists them.

## From acceptance criteria to cases

- Every acceptance criterion of every stage gets one case. A criterion that can be violated gets one
  negative case as well.
- Then go beyond the criteria only where a failure would be `critical`: for each thing the feature
  lets someone do with another person's data, with money, with an appointment, with an account or
  with a permission, ask:
  - **Who else?** Another organisation's user, a lower role, someone signed out.
  - **Twice?** The same action repeated, submitted twice, replayed.
  - **At the same time?** Two people on the same thing at the same instant.
  - **Interrupted?** A dropped connection, a refresh, a dependency that is down or slow.
  A case the specs never thought of that fails here is the most valuable thing a pass produces. If
  the right behaviour is not stated anywhere, ask the lead; do not invent the expectation.
- One case proves one thing. Its title is a statement that is true when it passes ("A second
  customer cannot hold a slot that is already held").
- Give each case a type from the catalogue, a priority, and the criteria it covers.

| Priority | Meaning |
| --- | --- |
| `critical` | If this fails, someone's data, money or appointment is wrong |
| `high` | An acceptance criterion or a main path |
| `medium` | A secondary path or an edge that has a workaround |
| `low` | Cosmetic or rare |

## Where to stop

QA runs once per feature, and the feature waits for it. These limits keep the pass to what protects
someone. They came from a stage whose single read-only endpoint was given forty-five cases, about
as much test code as product code, and a QA pass three times as long as the build.

- **Name the harm before adding a case.** Say what the case would catch that no existing case
  catches, and who is hurt if it is missed. If you cannot, do not write it.
- **Beyond the acceptance criteria, write only `critical` cases.** A `medium` or `low` idea that no
  criterion asks for is a line under "Suggestions" in `review.md`, not a case.
- **Variations are rows, not cases.** Five kinds of bad input, or the same check for three
  organisations, are one case that runs over a table of inputs.
- **Do not prove one thing twice.** A criterion is proved at one level: where an API test proves a
  rule, a browser test does not prove it again. A journey strings the stages together and asserts
  what passes between them, not each stage's rules over again.
- **Leave the inside to the developer.** A rule the developer's unit and integration tests prove
  from inside (a calculation, a parser, a policy in the database) gets one case from outside that
  shows it holds for a user, not a second copy of every branch.
- **Test types the catalogue limits are limited.** How many load, resilience or hostile-input
  cases a feature gets is in `docs/qa/test-types.md`; do not exceed it without the lead's agreement.

What these limits never remove: a case for every acceptance criterion, and every type the catalogue
marks as always required.

## Writing a case

Use `assets/cases.md`. A case another person could run without asking you has: who performs it and
as which role, what must exist first, exact steps, and an expected result that says what must
happen and what must not. "Works correctly" is not an expected result.

Declare every case in the yaml of the scope it belongs to, with the same id as its heading in that
scope's `cases.md`: a stage's criteria under `cases:` in its `stage.yaml`, a journey in `feature.yaml`.

## Automated, manual or deferred

| Outcome | When | Record |
| --- | --- | --- |
| Automated | Whenever it can be | The file under `verification.automation`, covering the case |
| Manual | It needs a person's judgement, or automating it would cost far more than it protects | `verification.manual` with the reason and the date it was performed |
| Deferred | What it tests cannot exist yet: it depends on a later feature, or on an event outside this one that a spec's "Done criteria" or the lead has acknowledged | `verification.deferred` with the reason and what unblocks it: a stage id, or the event |

Deferred and blocked are different. Deferred: the thing under test does not exist yet, and everyone
has agreed it will be tested later. Blocked (`qa_status: blocked`): the thing exists and could be
tested, but this environment lacks something. Never defer a case to get past a block, and never
defer on your own authority for an event the spec does not mention: ask the lead.

A deferred case is not forgotten. `qa/coverage.md` lists every one, and `feature-verify` and
`release-prepare` go through that list.
