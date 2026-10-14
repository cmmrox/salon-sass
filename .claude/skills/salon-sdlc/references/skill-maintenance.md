# Maintaining the skills

Three skills serve both Claude Code and Codex: `salon-sdlc` (the lifecycle), `salon-dev` (how a
developer works) and `salon-qa` (how QA works). Only the lead edits them, in their own commit with
scope `skills`.

## Contents

- What belongs in a skill, and what does not
- Layout
- Rules for the content
- Adding a lesson
- Differences between the two tools
- After every change

## What belongs in a skill, and what does not

A skill holds how work is done: process, roles, method, habits. It holds nothing about this
particular product. The test for any sentence: would it still be true in a different project? If
yes, it may go in a skill. If it names something of this product (a technology, a module, a path
inside an application, a business rule, a tool's layout, a target), it belongs in a document under
`docs/`, and the skill points to that document by path.

Written this way the three skills can be copied into another repository unchanged; only that
repository's `docs/` differs. The skill prefix is a placeholder until the product is named.

## Layout

| Path | What it is |
| --- | --- |
| `.claude/skills/<skill>/` | The real files. Edit here and nowhere else |
| `.agents/skills/<skill>` | A symlink to the folder above. Codex reads skills from `.agents/skills/` and follows symlinks |
| `.claude/skills/<skill>/agents/openai.yaml` | Codex display name, short description and invocation policy |
| `.claude/agents/salon-dev.md`, `salon-qa.md` | Claude Code subagents. They preload the skills and carry the path-ownership hook |
| `.codex/agents/salon-dev.toml`, `salon-qa.toml` | Codex agents `salon_dev` and `salon_qa`. Codex has no edit hook; the role runs `guard.py --diff` before committing |
| `AGENTS.md` | Root guidance, read by both tools. `CLAUDE.md` only imports it |
| `<folder>/AGENTS.md` with `<folder>/CLAUDE.md` | A few lines per application folder naming the documents to read before working there. Codex reads the nearest `AGENTS.md`; Claude Code reads the `CLAUDE.md` beside it, which imports it |
| `docs/README.md` | The index of project documents that the skills point to |

If symlinks cannot be used on a machine (some Windows setups), replace each symlink with a copy and
keep it identical; `qa/scripts/sdlc validate` fails when a mirror is missing or differs.

## Rules for the content

- `SKILL.md` is a router: the rules that apply to every task, a short workflow, and a map of the
  references saying when to read each. Keep its body under 500 lines.
- Frontmatter is `name` and `description` only, so that both tools read it the same way. The
  description is written in the third person, says what the skill does and when to use it, puts the
  main use first, and stays under 1,024 characters.
- Detail goes in `references/`, one level deep. Every reference is linked from `SKILL.md`; a
  reference never sends the reader on to another reference for the actual rule.
- A reference longer than 100 lines starts with a `## Contents` list.
- One home for each rule. If two files need it, one states it and the other links to it. A project
  rule's home is always under `docs/`.
- Use the vocabulary of `SKILL.md` in `salon-sdlc` everywhere.
- Fragile, repeated operations become a script under `qa/scripts/`, with a test, rather than prose.
- No dates, version numbers or addresses in a skill. They live in `docs/`
  (`docs/architecture/tech-stack.md` for versions).
- No secrets, credentials or personal data.
- Preserve user scope and existing authorisation. Adopt an external technique only where it
  improves an observed decision; reconcile it with the project's lifecycle and supplied design.
  Retain source attribution and any required licence notice in project documentation when
  incorporating external material. Installing a plugin or hook is a separate change.

## Adding a lesson

When something goes wrong and the fix is "the agent should have known", add the lesson:

1. Decide where it lives. A lesson about this product goes in the document under `docs/` that will
   be open when the mistake could happen again. A lesson about how to work goes in the narrowest
   skill reference, not in `SKILL.md`.
2. Write the rule, then one sentence saying why, with the real example. A rule without its reason
   gets dropped the first time it is inconvenient.
3. If a script can enforce it, enforce it there and keep the prose to one line.
4. Remove or merge an older rule it replaces. The skills must not grow into a history of incidents.

## Differences between the two tools

| | Claude Code | Codex |
| --- | --- | --- |
| Invoke a skill | `/salon-sdlc stage-plan F07-S02` | `$salon-sdlc stage-plan F07-S02` |
| Skill location | `.claude/skills/` | `.agents/skills/` |
| Agents | `.claude/agents/*.md`, delegated to by name | `.codex/agents/*.toml`, spawned by name |
| Path ownership | Blocked at edit time by the hook in the agent file | Checked with `guard.py --diff` before committing |
| Root guidance | `CLAUDE.md` (imports `AGENTS.md`) | `AGENTS.md` |

Write instructions that are true in both. Where a step differs, name both forms.

Both tools read skills and agent files when a session starts. After adding or changing one, start a
new session to use it; a session already running will not see it.

## After every change

```bash
qa/scripts/sdlc validate
```

It checks each skill's name, description length, body length, that every reference is linked, that
long references have a contents list, that the Codex mirror matches, and that every document a
skill or an `AGENTS.md` points to exists. Then try the change: start
a fresh session, give it a real request the change was meant for, and watch whether the right file
gets read. The standing set of requests to rerun for this project is
`docs/operations/skill-evaluation.md`.

For substantial behavior changes, evaluate the relevant scenarios in a fresh independent context
with the raw request and permitted artifacts, without supplying the expected answer. Inspect the
actual actions and evidence, then make focused corrections. Format validation proves packaging;
it does not prove correct decisions. Keep this evaluation proportional to the change and report
which client was exercised versus which was checked statically.
