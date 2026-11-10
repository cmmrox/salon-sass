# Verification evidence

What counts as proof in this project, and when it can be reused instead of run again (decision
068, replacing the receipt and scoped-assessment machinery of CR-008, CR-009 and CR-011).

## Developer gates at a stage

At handoff the developer records, in `stage.yaml` `handoff.notes`, one row per
`verification.commands` entry: the command, the tested commit, the exit result and the log path.
Test committed source, so the commit identifies what was tested. Ignored logs do not travel with a
merge: give the absolute path of the checkout they are in, and keep that checkout until the lead
has closed the stage.

At `stage-close` the lead checks that every command is accounted for, its log exists and passed,
and nothing it covers changed since the tested commit. A gate whose source changed runs again; an
unchanged one is not repeated.

## A QA pass

`qa/scripts/sdlc run` writes `qa/reports/<folder>/` with one log per automation entry and a
`summary.json` holding the tested commit and whether the checkout had uncommitted changes
(`source_dirty`, `source_changes`). `validate` requires `evidence.last_run` in the form
`qa/reports/<folder>`. QA records that folder in `evidence.last_run` and the
commit in `integration.qa_revision`.

**A pass holds while the product is unchanged.** It stays valid as long as this prints nothing:

```bash
git diff --stat <qa_revision> HEAD -- apps packages infra .github package.json pnpm-workspace.yaml \
  pnpm-lock.yaml .nvmrc .sdkmanrc docs/api qa/web qa/api qa/mobile qa/load qa/checks qa/shared \
  qa/scripts qa/package.json qa/pnpm-lock.yaml qa/playwright.config.ts qa/tsconfig.json
```

The same list is `FEATURE_QA_PATHS` in `qa/scripts/sdlc_lib/schema.py`; `pr-check` applies it,
together with the feature's specs. Documents, skills, status and review changes never invalidate a
pass. A change to one of these paths is verified for its affected scope: the fixed bug and what
depends on it, not the whole feature again, unless the change is broad.

Playwright's global setup refuses a run unless the API was built from `HEAD`
(`infra/scripts/qa-redeploy.sh` rebuilds the QA instance there). The tooling cannot see the web
build's commit or the state of the database: before relying on a run, check that the web app was
built from the tested commit and that the fixtures are the ones the run assumed.

## Progress and interruption

The runner writes `summary.json` atomically while it runs: the current entry, completed counts,
elapsed time and the child process. An interrupted run is marked `interrupted` and is not a pass.
Read the summary to report progress; never start a second run while the first one's process is
alive. An entry that reaches its time limit is recorded `timeout`. A run narrowed by `--entry` or
`--rerun-failed` is `partial` and is not held to the suite budget.

## Older records

F01 to F03 carry `evidence.scoped_assessment` and `scoped-qa.json` files from the retired
machinery. They are historical records of how those passes were assembled; nothing reads them now.
