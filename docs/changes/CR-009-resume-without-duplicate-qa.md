---
id: CR-009
title: Resume passed verification without duplicate QA
status: implemented
requested_by: owner
requested_on: 2026-10-15
decided_on: 2026-10-15
requirements: []
features: []
stages: []
---

# CR-009 Resume passed verification without duplicate QA

## Request and decision

The owner explicitly instructed: "if there is not any changes in the code don't do the qa again
in begining again continue the rest" after observing repeated QA across interruptions. The owner
also asked to update the skill for Claude Code and Codex. This request approves the process change;
no product requirement, stage or acceptance criterion changes.

## Delivered scope

- Check saved results, tested revisions, relevant paths and test conditions before starting QA.
- Reuse complete intact passing evidence after token limits, compaction, session/client or machine
  restarts and metadata-only updates; continue the next unfinished step.
- Require a concrete invalidating change and affected-scope rationale before rerunning checks;
  retain unaffected results after a bounded fix. Independent QA and original full-feature coverage
  remain required, with targeted checks reported separately.
- Preserve unfinished/failed evidence honestly. A partial run is never promoted to a pass.
- Remove blanket rerun instructions from the lifecycle, bug loop and Git handoff. Extend closure
  and submission checks to validate an explicit independent scoped assessment alongside intact
  base full/command receipts and targeted evidence; do not repeat unaffected product QA merely
  to satisfy a conservative fingerprint. Preserve the original full-run identities and limits.
- Apply the same canonical instructions through the existing Codex symlinks. Validate instruction
  consistency and packaging only; this skill edit does not trigger product QA.

## Verification

The resume, metadata-only and bounded-fix decisions in skill-evaluation scenarios 25–27 were
checked against the revised instructions. All three packages pass skill-creator validation using
the existing tooling Python environment; SDLC validation and diff checks pass, including the
shared Claude/Codex mirrors. This is static instruction/package validation, not a fresh client
behavioral evaluation. No application QA, runtime reset or product code change was performed.
