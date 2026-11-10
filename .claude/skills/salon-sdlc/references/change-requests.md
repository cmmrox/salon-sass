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
     | `in-progress`, `built` | Update `spec.md`; set a `built` stage back to `in-progress`; tell the developer |
     | `complete`, its feature not yet merged | Reopen: set `status: in-progress`, `qa_status: not-run`, add `reopened_by: CR-<nnn>`, update `spec.md`. Stages that depend on it keep going; they cannot be closed until it is closed again. If its feature was `in-qa` or `complete`, set the feature back to `in-progress` and `qa_status: not-run` |
     | `complete`, its feature merged but not released | Do not reopen: statuses and the merge record stay, because they state what was merged. Add the new criteria at the end of `spec.md` under a `## CR-<nnn> ...` heading: while the request is `approved` they are pending (no case needed yet, listed as pending in `qa/coverage.md`), the requirements it changes are not counted as passed, and features that depend on this one wait. Ship one follow-up PR, and record QA's targeted re-verification in the feature's `evidence`. `integration` keeps describing the feature's own PR, so never move its `qa_revision`: the merge checks verify it against that PR's head, and moving it once blocked the next feature's start. `pr-check` does not apply to the follow-up's branch: check by hand that it is clean, on `origin/main`, and unchanged since QA's revision. The change is `implemented` once that PR's merge is verified |
     | `released` | Never rewritten. Add a new stage to the feature (or a new feature) that delivers the change |

   - A new feature gets the next free `F` number and a `feature.yaml` with `status: backlog`.
5. **QA follows, at the feature pass.** For every stage the change touches that already has cases,
   QA updates the cases and the automation at the feature's next `feature-verify`. A case whose
   expectation changed is edited; a case that no longer applies is deleted, along with its
   automation. Old expectations do not stay behind "just in case".
6. **Close.** When the last affected stage is `complete` again, set the change request to
   `implemented`.

## What a change request does not trigger

A change to a feature that has not been through QA triggers no QA of its own: the stage is built
and closed again, and the feature pass covers it. A change to a feature that has been through QA
sends the affected scope through independent re-verification under `references/progress.md`,
retaining unaffected passing results. Process-only changes do not trigger product QA. If the change
reaches into behaviour earlier features rely on, say so in the impact analysis; regression then runs
before its PR, as for a refactor.

## Suggestions from QA

QA classifies each finding of a review as must, should or idea (the definitions are in
`references/product-review.md` in `salon-qa`). The lead routes them:

- **must**: it is a defect. QA has filed it as a bug.
- **should**: the lead raises a `proposed` change request and puts it to the owner. Only the owner
  turns one down; the lead never declines a "should" on the owner's behalf.
- **idea**: the lead adds it to `features/BACKLOG.md`.

Nothing in the last two groups is built until the owner approves it.

Before `feature-close` (and before `stage-close`, when a stage pass wrote a review), the lead writes
each suggestion's outcome in the Disposition column of `review.md`: `BUG-...` (it was really a defect), `CR-<nnn>` (raised for the owner), `backlog` (an
idea, added to `features/BACKLOG.md`), or `declined by owner: <reason>`.
