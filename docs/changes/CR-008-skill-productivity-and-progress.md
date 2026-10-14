---
id: CR-008
title: Shared skill productivity, progress and verification evidence
status: implemented
requested_by: owner
requested_on: 2026-10-14
decided_on: 2026-10-14
requirements: []
features: []
stages: []
---

# CR-008 Shared skill productivity, progress and verification evidence

## Request and decision

The owner asked for visible periodic progress and an assessment of four public skill collections,
then approved the proposed upgrade with "ok then udapte the skills and let me know". Improve the
three existing shared skills and their workflow support before reporting completion.

## Approved scope

- Concise feature/stage/role progress with observed counts, current step, remaining work and
  blockers; preserve evidence and process identity across interruptions.
- Reference-first UI mapping and bounded batched visual correction using the supplied design.
- Reproduction, root-cause diagnosis, risk-focused behavior tests and compatible component reuse.
- Build/contract/credential/fixture/clock preparation before expensive QA.
- Replace repeated full-feature closure execution with source/context-bound evidence checks;
  invalidate changed, partial, legacy or missing proof. Retain independent full-feature QA,
  stage closure, manual/deferred cases and all security/scope/merge gates.
- Maintain `.claude/skills/` as the source and `.agents/skills/` as its Codex symlinks, with aligned
  role adapters, concise entrypoints and meaningful behavioral evaluation.

No external plugin, hook, telemetry, system installation, deployment or product requirement is
added. The existing UI/logging work is preserved separately and receives its own independent QA.

## Sources assessed

These sources informed focused original instructions, rather than wholesale imported workflows:

- [Impeccable](https://github.com/pbakaus/impeccable/blob/main/.agents/skills/impeccable/SKILL.md):
  the brief wins, refinement preserves the visual system, and visual iteration is bounded.
- [Superpowers systematic debugging](https://github.com/obra/superpowers/blob/main/skills/systematic-debugging/SKILL.md):
  reproduction, evidence and root-cause hypotheses before patches.
- [Ponytail](https://github.com/DietrichGebert/ponytail/blob/main/skills/ponytail/SKILL.md):
  trace callers and use the smallest complete compatible solution without removing safeguards.
- [Matt Pocock TDD](https://github.com/mattpocock/skills/blob/main/skills/engineering/tdd/SKILL.md)
  and [writing for agents](https://github.com/mattpocock/skills/blob/main/skills/productivity/writing-for-agents/SKILL.md):
  behavior tests, independent expected values and progressive disclosure.

## Validation

Skill/package validation, workflow selftests and independent realistic scenario evaluation are
required. Record exercised clients separately from static adapter/symlink checks. Completion of
this process upgrade does not certify the pending F01/F02 product repair.

Delivered validation: all three skill packages and SDLC validation pass; workflow selftests pass
118/118, including 32 new evidence/progress checks. A fresh independent Codex evaluation recorded
six request decisions and passed 32/32 focused checks, including separate permission-change and
SIGINT/SIGTERM process probes. Its local report is `.local/skill-evaluations/codex.md`. Claude/Codex
role descriptions, skill loading, ownership boundaries and shared-source symlinks were checked.
The attempted restricted read-only Claude live evaluation returned an account session-limit error
before model execution; it is not counted as a passed live evaluation. No external collection was
installed. A new client session loads the revised guidance.
