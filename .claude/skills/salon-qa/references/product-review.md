# Product review

Passing its tests does not make a stage good. After the cases have run, use what was built the way
its users will, compare it with what they use today, and write down what would make it better. This
is the part of QA that the developer cannot do for themselves.

Who the users are, what matters to them in particular, and which products to compare with are in
`docs/qa/review-context.md`. Read it first.

## How to review

Walk through the stage as each kind of person it affects. Do it slowly once and fast once. Use the
device and the conditions they would really have.

## What to look at

- **Effort:** how many steps, taps and fields. Could a step go, or be remembered from last time?
- **Clarity:** at each moment, does the person know where they are, what will happen next, and what
  it will cost?
- **Confidence:** before anything irreversible, is what is about to happen plain? Afterwards, is it
  obvious it worked?
- **Recovery:** after a mistake, a refusal or a dropped connection, is the way forward one step away
  with nothing re-entered?
- **Speed as felt:** does anything make the person wait with nothing to look at?
- **Fit:** does it suit the place, the language, the devices and the habits of the people it is for?
- **Consistency:** does it look and behave like the stages before it?
- **The customer's interest:** does it do the job the customer is paying for, better than before?

## Compare with what they use today

For the flow this stage delivers, look at how the established products handle it. For each
comparison, note what they do, what we do, and which is better for our user. "They have it and we
do not" is only a finding when our users would miss it. Use public pages and documentation only; do
not create accounts with real personal data.

## Classify every finding

| Class | Test | Where it goes |
| --- | --- | --- |
| **must** | It breaks a requirement or an acceptance criterion | It is a bug. File it; do not list it as a suggestion |
| **should** | The spec is met, but a user is clearly better served another way | `review.md`; the lead raises a change request for the owner |
| **idea** | Worth remembering, not worth interrupting for | `review.md`; the lead adds it to the backlog |

Nothing in "should" or "idea" is built until the owner approves it. Your job is to make the case
well enough to decide on.

## Writing `review.md`

Use `assets/review.md`. For each suggestion give:

- **What was observed**, concretely, with the screen and the steps.
- **Why it matters**, for which person.
- **What to do instead**, specifically enough to estimate.
- **Evidence**: a screenshot, a count of steps, a competitor's handling.

Rank them. Five suggestions that matter beat twenty that include the spacing of a footer. Say what
is good too, briefly: it tells the next stage what to keep. Leave the Disposition column empty; the
lead fills it in.

A stage with no user-facing surface (infrastructure, a background job) still gets a review: of the
API as a client developer would meet it, of the error messages, of what an operator would see when
it fails. If there is truly nothing to review, say so in one line with the reason.

If what you found differs from what a document under `docs/` says, note it in the review so the
lead can correct the document.
