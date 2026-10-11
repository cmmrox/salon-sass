# F00-S01 <stage title>

## Objective

<Two or three sentences: what exists when this stage is done, and for whom.>

## Read first

The project documents that hold the rules for this stage. The developer and QA read each one in
full before starting.

- `docs/<area>/<document>.md`: <what to take from it for this stage>

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| BKG-00 | <text from docs/brd/requirements.yaml> | full |

## Scope

- <What this stage builds, by surface: API, web, mobile, infrastructure.>

## Out of scope

- <What a reader might expect here that belongs to another stage, with that stage's id.>

## Acceptance criteria

Each criterion is one observable outcome that a test can pass or fail.

- AC-1: <Given ..., when ..., then ...> (BKG-00)
- AC-2: <a negative or boundary case: wrong owner, wrong role, simultaneous requests, bad input> (BKG-00)

## Design notes

<Decisions that constrain the build: modules touched, data model, states, error behaviour.
Link to docs/architecture/ rather than restating it.>

## API and data changes

<Endpoints added or changed, with request and response shape. Tables, columns, constraints and
migrations. Write "Not applicable for this stage" if none.>

## UI and UX

<Screens and states, with the prototype screen each one comes from. Loading, empty and error
states. Write "Not applicable for this stage" if none.>

## Risks and test focus

<What is most likely to go wrong, and the test types QA should plan for (see `docs/qa/test-types.md`), with the reason for each.>

## Verification commands

The developer gates, run from the repository root. Copy them into `stage.yaml` `verification.commands`.

```bash
<command>
```

## Done criteria

- Every acceptance criterion holds on the running product.
- The verification commands are green.
- QA has passed the stage and every blocker and major bug is verified fixed.
- <Anything specific to this stage: a document updated, a decision recorded.>
