---
id: CR-011
title: Reduce repeated development and QA work while preserving delivery quality
status: implemented
requested_by: owner
requested_on: 2026-10-15
decided_on: 2026-10-15
requirements: []
features: []
stages: []
---

# CR-011 Reduce repeated development and QA work

## Request and decision

After the project skill audit and external research, the owner requested: "ok according to that
can you fix the existing skills which we have in this project". This directly approves the shared
process changes below. No product requirement, feature status, deployment or merge is authorised.

## Scope

- Reuse complete developer-gate evidence at stage closure; execute missing or invalidated gates.
- Use focused feedback while coding and deduplicate broader gates after compatible fix batches.
- Allow independent QA case/fixture preparation during development against approved contracts;
  keep the formal feature pass after all stages close, with no implicit stage QA.
- Require realistic failure-detection proof for critical behavior/shared verification mechanisms;
  reuse genuine pre-fix failures and remove blanket count/scrape expectation-inversion exercises.
- Reuse relevant unchanged context, diagnostic scripts and complete logs; isolate checks that
  mutate shared clocks, services or synthetic data.
- Keep review proportional to changed flows and approved design; suggestions remain proposals.
- Consolidate delivery metadata before waiting for final-head CI and record observed time by
  work category in existing handoffs where available.

Tenant isolation, permissions, integrity, all declared gates, independent QA, manual/design checks,
scope/role guards and final-head CI remain required. Stage evidence is not sealed full-feature
proof. No CI workflow, application code, test threshold, plugin or installed tool changes.

Canonical skill edits live under `.claude/skills/`; `.agents/skills/` symlinks share them with
Codex. Agent adapters and the evidence/evaluation documents are aligned with the same rules.

## Research basis and limits

The following informed original, project-specific instructions; no external skill text or code
was copied:

- [Anthropic: harness design](https://www.anthropic.com/engineering/harness-design-long-running-apps):
  simplify orchestration incrementally while retaining useful independent evaluation.
- [Spotify: verification loops](https://engineering.atspotify.com/2025/12/feedback-loops-background-coding-agents-part-3):
  reusable deterministic checks and concise diagnostic output reduce agent decision overhead.
- [Cost-inefficient agent behaviors](https://arxiv.org/html/2609.30725v3): repeated retrieval,
  script generation and unchanged test execution are measurable waste; reported inference-cost
  reductions do not establish time savings or unchanged quality in this project.
- [Evaluating AGENTS.md](https://arxiv.org/abs/2602.11988) and
  [AGENTS.md efficiency study](https://arxiv.org/abs/2601.20404): results differ by setup;
  preserve necessary project constraints and evaluate instruction changes locally.
- [Developer experience with agentic coding](https://martinfowler.com/articles/exploring-gen-ai/13-role-of-developer-skills.html):
  redundant tests, misunderstood scope and speculative fixes increase downstream work.

## Verification

- `qa/scripts/sdlc validate`: passed (179 requirements, 16 features, 13 stages, 3 skills).
- Skill-creator package validation: all three skills passed. Codex skill symlinks resolve to the
  canonical Claude files; both Codex agent TOML files parsed successfully.
- `git diff --check`: passed.
- A fresh independent Codex context evaluated eight raw workflow scenarios without reading the
  expected answers: valid/stale gate evidence, batched fixes, concurrent QA preparation, meaningful
  failure detection, context/log reuse, final-head delivery and bounded product review. Its decisions
  preserved the required gates. The review exposed ambiguous stage completion, closure-record and
  release-preparation wording; these were corrected, and its saved follow-up found no new
  decision-changing conflict. Local evidence: `.local/skill-evaluations/CR011-independent.md`.
- This was a bounded decision simulation and adapter consistency review, not execution of a
  feature or release. Claude Code was checked statically, not exercised in a live session.
  No local product suites were run for this documentation-only change; GitHub's existing required
  CI remains unchanged and must pass on the final PR head before merge.

Start a fresh client session to load the revised skill and agent instructions. Actual elapsed-time
savings remain to be measured on subsequent feature work; this evaluation does not establish a
percentage improvement or certify future product quality.
