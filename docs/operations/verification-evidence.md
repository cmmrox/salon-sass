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
(`source_dirty`, `source_changes`). The folder is `<YYYYMMDD-HHMMSS>-<scope>-<run id>`, and a run
narrowed by `--entry` or `--rerun-failed` is `<YYYYMMDD-HHMMSS>-<scope>-partial-<run id>`, with
`partial: true` in its summary (folders from before the marker keep their names). QA records the
latest run in `evidence.last_run` and the commit in `integration.qa_revision`.

**What a pass needs on record (decision 069).** A full run of the scope: `evidence.full_pass_run`
when it is set, otherwise `evidence.last_run`. A first full run usually has failures, so it may
have failed; each entry it did not pass must then have passed in a later run listed in
`evidence.reruns` (`qa/reports/<folder>` paths). `last_run` may be a narrowed or stage-scoped
rerun only when `full_pass_run` names the full run. `validate`, and so `pr-check`, checks this:
- everywhere, by the folder names: the full run is not a `-partial-` folder, and each rerun is a
  report folder later than it;
- where the full run's folder is in the checkout (`qa/reports` is not committed, so not in CI):
  its `summary.json` is a run of the scope, not `partial`, and finished, every selected entry with
  an outcome;
- where the listed reruns' folders are all in the checkout: every entry the full run did not pass
  passed in at least one of them. A feature with a merge record is exempt from this last check
  only; F07, merged before `evidence.reruns` existed, records its reruns in prose under
  `reverification`.

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

`qa/scripts/sdlc run` refuses a run, before its first entry and for every tool, unless the API is
a current build of `HEAD`: the commit `/api/v1/meta` reports is an ancestor of `HEAD`, and `HEAD`
has not changed the build's sources (`infra/scripts/lib/build-sources.txt`) since, so a commit of
tests or records alone needs no rebuild (`infra/scripts/qa-redeploy.sh` rebuilds the QA instance
at `HEAD`). `summary.json` records the build tested: `api_commit`, `api_current` and `build`. A run
whose entries do not need the running product (shell checks that do not call
`require_product_up`) does not ask the API, and records `build.needed: false`. With
`QA_ALLOW_BUILD_MISMATCH=1` the run goes ahead and records `api_current: false`. Playwright's
global setup makes the same check for a test file run directly. The tooling cannot see the web
build's commit or the state of the database: before relying on a run, check that the web app was
built from the tested commit and that the fixtures are the ones the run assumed.

## Progress and interruption

The runner writes `summary.json` atomically while it runs: the current entry, completed counts,
elapsed time and the child process. An interrupted run is marked `interrupted` and is not a pass.
Read the summary to report progress; never start a second run while the first one's process is
alive. A run and `infra/scripts/qa-redeploy.sh` exclude each other through one lock,
`.local/run/qa-instance.lock` in the product checkout: the runner holds it for the whole run,
from before its build check, and a redeploy across its guard, stop, rebuild and start, so neither
can start while the other is going (a redeploy would break a run partway). Each refuses, starting
or stopping nothing and naming the holding process, while the other holds it. The lock names its
holder by process id and start time; one whose holder is gone, killed or not, is stale and is
replaced. `qa-redeploy.sh` also refuses while a summary of its checkout says a live run is going,
naming the report folder. An entry that reaches its time limit is recorded `timeout`. A run narrowed by `--entry` or
`--rerun-failed` is `partial` and is not held to the suite budget.

## Older records

F01 to F03 carry `evidence.scoped_assessment` and `scoped-qa.json` files from the retired
machinery. They are historical records of how those passes were assembled; nothing reads them now.
