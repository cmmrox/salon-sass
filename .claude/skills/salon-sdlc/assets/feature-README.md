# F00 <feature title>

## What the customer gets

<Who can do what once this feature is delivered, in plain words. One short paragraph.>

## Requirements

<The requirement ids this feature owns, grouped if that helps. The text lives in
docs/brd/requirements.yaml; do not copy it here.>

## How it is built

<The surfaces involved and the main design decisions, with links to docs/architecture/.
Dependencies on other features.>

## Design check

<Done at `feature-new`, before any spec. The records this feature adds or changes and what each
belongs to; the states and who may change them; the events and read interfaces other modules will
need. Then one row per later feature that depends on this one.>

| Later feature | What it will need from this model | Allowed as designed? |
| --- | --- | --- |
| F00 | <for example: several services in one booking> | <yes, or the change made now so that it is> |

## Market check

<How the comparison products do this job, what they have that the requirements lack, and where
each gap went: a change request, the backlog, or declined by the owner.>

## Stages

The order and status of the stages is in `features/README.md` (generated). This section explains
why the feature is divided the way it is.

| Stage | Delivers | Why it is its own stage |
| --- | --- | --- |
| F00-S01 | <what can be used or called at the end of it> | <the risk or dependency it isolates> |

## Feature-level test focus

<What the feature's one QA pass must prove: the journeys that only make sense once every stage is
done, and the non-functional requirements this feature must prove (nfr_focus).>

## Decisions and open questions

<Decisions taken for this feature, each also recorded in docs/decisions/DECISION_LOG.md, and
questions waiting for the owner.>
