# Verification evidence and progress

## Contents

- Purpose and limits
- Prepare a context
- Reuse developer gates at stage closure
- Record developer gates and independent QA
- Close against unchanged evidence
- Resume and affected-scope evidence
- Progress and interruption

## Purpose and limits

CR-008 replaces duplicate full-feature execution at feature closure with a checked evidence
handoff. Independent QA still runs the entire feature after its stages close. Developer gates,
manual cases, unresolved bugs, scope, tenant isolation and owner merge/deployment gates retain
their meaning. CR-011 also permits checked reuse of developer gates at stage closure.

Evidence is local execution proof, not staging certification. It is reusable only when its
certified source, acceptance criteria, command/automation definitions and declared environment
context remain unchanged. Legacy full-feature reports without the required receipts remain
historical evidence, not reusable certification. Ordinary stage evidence follows the protocol below.
The tooling cannot infer actual service or database state from Git: the lead/QA must check build
identity, health and fixture prerequisites before recording or reusing evidence. When those
conditions are uncertain or changed, prepare the environment and rerun the relevant checks.

CR-009 governs reuse and proportional re-verification after a complete pass. A restart or a
metadata-only edit does not invalidate product QA. Inspect existing proof before any execution.

## Prepare a context

Keep a workspace-local JSON context outside Git, for example `.local/verification-context.json`.
It names the environment, profiles, exact prepared fixture generation and targets. Optional
`config_files` names existing workspace-relative configuration files; `environment_variables`
names variables that influence verification. The runner fingerprints their actual contents or
values and saves an aggregate digest, never their contents or secret values.
Absent, empty and set environment overrides have distinct fingerprints. Presence of required
credentials is established by the preparation checks, not inferred from this digest.

Example for the current local cross-feature repair, after verifying these targets and files:

```json
{
  "environment": "local-http",
  "profiles": ["local", "local-stack"],
  "fixture_identity": "prepared-local-and-isolated-stack-generation-001",
  "targets": ["http://alpha.localhost:3100", "http://alpha.localhost:8090", "http://stack.localhost:8089"],
  "config_files": [".env.stack", ".local/identity-keys.properties"],
  "environment_variables": ["JAVA25_HOME", "QA_API_URL", "QA_WEB_URL", "QA_STACK_URL", "QA_STACK_NEXT"]
}
```

This is an example identity, not a prefilled certificate. Use the actual prepared fixture/build
identity; change it after a reset, a different database/image/provider, or untracked fixture
mutation. Include every configuration file and variable that changes the selected environment,
including `qa/.env.local` when used. A missing declared file refuses reuse. A context that omits
a relevant change cannot prove equivalence; record the change and rerun instead.

The retained F01 stack volume is historical and must be preserved. The current repair explicitly
uses its isolated QA stack volume. F01-to-F02 local synthetic reset is described in
`docs/qa/automation.md`; do not erase a retained stack volume to obtain a pass. Context values
must contain no credentials or personal data; private file contents stay on the machine.

## Reuse developer gates at stage closure

For an ordinary stage handoff, record a compact gate table in `stage.yaml` `handoff.notes` with
each exact `verification.commands` entry, its tested source revision, complete log path and exit
result, and the relevant runtime/configuration/fixture conditions. If the tested tree was dirty,
retain its diff identity as well; a commit SHA alone cannot describe uncommitted source. Prefer
testing committed implementation so the record can be compared without reconstruction.

Ignored logs do not travel with a Git merge. Record the originating checkout's absolute path and
resolve relative evidence paths against that checkout, never against the lead's worktree. Keep
the developer worktree and its evidence intact until the lead has read and checked them at closure.
Do not remove it or overwrite its logs while that handoff is pending. If the lead cannot access
that checkout (for example, on another machine), transfer the complete gate logs and their
command/source/result/context record to a location the lead can access before handoff. Verify
the copied files' checksums against the originals, record the new location, and retain the originals
until the lead confirms access and verification. Apply the existing guarded cleanup policy after
closure; a copied log still needs the same source, result and condition checks below.

Before `stage-close`, the lead checks every declared command is accounted for, its complete log
exists and passed, relevant tracked/untracked source and command definitions are unchanged, and
its test conditions still apply. Keep all applicable security, contract and persistence gates.
Reuse valid results; execute missing or invalidated commands, and diagnose and correct failures
before a meaningful rerun. If dependency impact
is uncertain, inspect it first and run the broader affected gates when uncertainty remains.
No stage QA run is implied. A summary saying "tests passed" without recoverable command/source/
result evidence is insufficient. Record the closure assessment in the existing handoff/evidence,
without changing the original tested identity or adding a new status field.

This stage record supports stage closure only. It does not replace the sealed full-feature
developer-command and independent QA receipts required below. Feature closure must use those
receipts or the supported independent scoped assessment; ordinary stage logs are not promoted
into reusable full-feature certification. No new runner command or evidence format is introduced.

## Record developer gates and independent QA

After committing the source and automation, prepare the environment safely. Stop an API using
a jar before a gate rebuilds that jar, then restore the intended runtime before browser/API QA.
Select the configured JDK through the project's normal build environment. Run:

```bash
qa/scripts/sdlc verify-commands --feature F01 --context .local/verification-context.json
qa/scripts/sdlc run --feature F01 --context .local/verification-context.json
```

The first command deduplicates that feature's stage `verification.commands`, runs them in order
and stops at the first failure. Retain its printed command-report directory. The second belongs
to independent QA, selects the exact full feature and records its complete result. Source or
context changes during either run invalidate reusable proof. An ordinary run without `--context`
continues to work, but closure cannot reuse it.

QA records the tested full SHA in `integration.qa_revision`, the feature report directory in
`evidence.last_run`, its result in `evidence.last_result`, and the real `qa_status`. Manual and
deferred cases are recorded separately. No interrupted or partial run supplies a feature pass.
Perform required manual checks and commit any resulting case/specification or manual-inventory
updates before the final recorded automation run. Review and evidence metadata may follow it.
Changing certified cases or definitions afterward requires a new run even when the edit looks
small; arranging the handoff in this order avoids a paperwork-induced repeat.

## Close against unchanged evidence

Integrate QA and verified fixes into the recorded feature branch. Confirm the current runtime
and prepared environment match the context, then use the explicit command-report directory:

```bash
qa/scripts/sdlc closure-check F01 --context .local/verification-context.json --commands-report qa/reports/ACTUAL-F01-commands
qa/scripts/sdlc validate
```

Reusable runs also retain a local integrity receipt under the repository's Git directory,
`sdlc-certifications`. Preserve it together with the report. These are local integrity checks,
not cryptographic signatures or third-party attestations. A copied summary without its receipt
cannot be reused. Keep evidence outside Git's committed product history.

The closure check requires closed stages, independent QA `pass`, the QA ancestor revision,
unchanged relevant source/criteria/definitions/context, complete successful expected entries and
commands, and intact evidence/log digests. Relevant dirty or untracked source refuses reuse.
Metadata-only commits can follow a passed source revision. Missing, tampered, interrupted,
wrong-scope or legacy evidence refuses; run the invalidated scope before closing. Do not hand-edit
a report to repair a refusal. Review dispositions and required manual/deferred proof remain
the lead's closure gates. This command does not change status, create a PR or deploy.

## Resume and affected-scope evidence

Read the existing feature evidence, bug verification and handoff before running anything. Compare
the recorded tested revision with current relevant tracked and untracked paths; inspect the
context and report integrity. Preserve passing results across token limits, compaction, client or
machine restarts. Review, skill, status and documentation changes trigger only their own applicable
validation unless they actually change requirements, test meaning or product execution.

For a product/test/configuration change, QA records the exact changed paths, affected behavior and
dependencies, retained base full reports with their original revisions, newly executed checks with
their actual tested revision/results/log paths, and any limits. A shared-code change may justify
broader checks; a new commit SHA alone does not. Interrupted results remain incomplete; preserve
them and finish the unfinished affected scope without discarding valid completed work.

By default `closure-check` and `pr-check` retain the conservative single-full-report check. For an
explicit independent base-plus-delta decision, record `evidence.scoped_assessment` as the committed
feature-local `scoped-qa.json` path. Both affected features may refer to the same grouped repair
assessment. Keep original `integration.qa_revision`, `evidence.last_run` and base report contexts
intact; the separate `reviewed_revision` names the revision tested by the targeted checks.

The assessment records QA identity/authority, exact base-to-reviewed changed paths per feature,
impact rationale, original full-report revision and digest, developer-command proof, targeted
passing log/digest/counts and limits. The checker validates original full/command integrity receipts
and every log, ancestry, declared diff inventory, required commands and targeted result. It refuses
later unreviewed product, automation, criteria or configuration changes. Narrow process/metadata
changes may follow; they do not become a new product certification. It does not rerun tests or
claim the historical fixture generation is today's runtime. QA's dependency judgment remains an
explicit independent assessment, not an automatic inference from filenames.

Never manufacture a full report, rewrite its tested SHA or drop relevant context to satisfy a
gate. Missing or changed proof must be investigated specifically; a tooling limitation alone is
not evidence that unchanged product QA needs to run again. Retain the local reports/receipts even
when the assessment itself is committed, and include base and targeted scopes in the PR.

## Progress and interruption

The runner reports the active automation group, completed/pass/fail counts and elapsed time,
including a heartbeat during quiet commands. An automation group may contain many test cases;
its count is not a case count or an overall implementation percentage. Progress records survive
an interruption; a complete final result is still required. Preserve process identity and check
whether it is running before starting another copy. The lead relays concise owner-visible
updates under `salon-sdlc`'s progress guidance rather than exposing private log contents.

## Scoped follow-up automation

For an approved affected-scope follow-up under CR-009, use `qa/scripts/sdlc run --feature F02
--only web-functional --only browser-security` (one shell command on a single line). Each
`--only` value must be a registered automation id in that feature. The runner labels this a
`F02-delta` report and refuses `--context`, stage, release or suite certification with it. A delta
report supplements independently assessed retained results; it never constitutes a full-feature
pass. Keep the full original report and an explicit scoped assessment.

Scoped selections receive kind `scoped-feature-qa`, a source fingerprint and sealed passing runner
receipt, with a final source check. They do not claim the full selection or identical historical
environments. `targeted.reports` in the independent assessment lists each feature, report,
revision, summary digest and exact registered automation ids. Closure checks every raw log and
selection; counts refer to automation entries, preserving each tool's own case/request totals.
Legacy single-log assessments remain valid for their originally declared scope.

Developer commands execute the product workspace, while QA automation lives in a separate
package. A later change confined to `qa/web`, `qa/api`, `qa/mobile`, `qa/load`, `qa/checks` or
`qa/shared` may reuse those product-command results only with an exact `commands.reused_qa_paths`
inventory and `reuse_reason`, followed by current independent sealed delta execution. Application,
package, infrastructure, spec or command changes still invalidate that reuse. An approved change
request may move to implemented after gates only when its entire remaining frontmatter and body
are identical. These rules preserve source checks without repeating unchanged product commands
because QA corrected its own assertions.

Completed scoped runs seal their overall result even when a group fails. An assessment may select
only explicitly named passing entries from that sealed report; it preserves the overall failure
and every log. Further receipts may supply the repaired group with disjoint automation ids.
`retained_qa_paths` plus `retention_reason` permits a later correction confined to other browser
`.spec.ts` files only; selected files, shared helpers and application changes remain invalidating.
The independent QA analysis must establish that those other specs are not selected dependencies.
No retained entry is relabelled as a full-feature pass, and failed/skipped entries never count.
