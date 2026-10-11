# Change requests

New requirements arrive in the middle of work, and QA finds improvements worth making. Both go
through the same path, so that the register, the specs, the cases and the automation never drift
apart.

## When one is needed

| Situation | Change request? |
| --- | --- |
| The owner adds, changes or removes a requirement | Yes |
| QA suggests an improvement in a `review.md` and it is a "should" | Yes, with status `proposed`, for the owner to decide |
| A spec turns out to be wrong or impossible while building | Yes |
| A finished (`complete` or `released`) stage must behave differently | Yes |
| A bug: the product does not do what the spec says | No. File a bug |
| A stage that is `planned` and not started needs a clearer spec | No. Re-plan it |

## Steps

1. **Record.** Create `docs/changes/CR-<nnn>-<slug>.md` from `assets/change-request.md` with the
   next free number and `status: proposed`. State the request in the requester's own words, and who
   asked.
2. **Analyse the impact.** Read the folders of the stages that may be affected (this is one of the
   commands that reads across stages). List, with ids:
   - requirements added, changed or retired;
   - features and stages affected, with each one's current status;
   - cases and automation that become wrong or missing;
   - data, API contract and released-app compatibility consequences;
   - the size of the work, and what it delays.
3. **Decide.** Show the owner the analysis and your recommendation. Only the owner approves or
   rejects. Record the decision and its date in the file: `approved` or `rejected`.
4. **Apply, only when approved.** In one change:
   - `docs/brd/requirements.yaml`: add new requirements with the next free id in their module; on a
     changed one set `status: changed`, `changed_by: CR-<nnn>` and the new text; on a removed one set
     `status: retired`. Never reuse or renumber an id.
   - Ownership: give every new requirement to a feature, and to a stage.
   - Stages, by their status:

     | Stage status | What to do |
     | --- | --- |
     | `outlined`, `planned` | Update the outline or `spec.md` in place |
     | `in-progress`, `in-qa` | Update `spec.md`; tell the developer and QA; QA updates cases and automation |
     | `complete` (not released) | Reopen: set `status: in-progress`, `qa_status: not-run`, add `reopened_by: CR-<nnn>`, update `spec.md`. Stages that depend on it keep going; they cannot be closed until it is closed again |
     | `released` | Never rewritten. Add a new stage to the feature (or a new feature) that delivers the change |

   - A new feature gets the next free `F` number and a `feature.yaml` with `status: backlog`.
5. **QA follows.** For every stage the change touches, QA updates the cases and the automation in
   the same stage pass. A case whose expectation changed is edited; a case that no longer applies is
   deleted, along with its automation. Old expectations do not stay behind "just in case".
6. **Close.** When the last affected stage is `complete` again, set the change request to
   `implemented`.

## What a change request does not trigger

Only the stages it touches are verified again, each by its own `stage-verify`. It is not a reason
to run regression. If the change reaches into behaviour that earlier features rely on, say so in the
impact analysis and let the owner decide whether to ask for a regression run.

## Suggestions from QA

QA classifies each finding of a review as must, should or idea (the definitions are in
`references/product-review.md` in `salon-qa`). The lead routes them:

- **must**: it is a defect. QA has filed it as a bug.
- **should**: the lead raises a `proposed` change request and puts it to the owner. Only the owner
  turns one down; the lead never declines a "should" on the owner's behalf.
- **idea**: the lead adds it to `features/BACKLOG.md`.

Nothing in the last two groups is built until the owner approves it.

Before `stage-close`, the lead writes each suggestion's outcome in the Disposition column of
`review.md`: `BUG-...` (it was really a defect), `CR-<nnn>` (raised for the owner), `backlog` (an
idea, added to `features/BACKLOG.md`), or `declined by owner: <reason>`.
