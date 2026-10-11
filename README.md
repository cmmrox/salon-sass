# Salon SaaS platform

A white-label, multi-tenant salon management platform: each salon gets its own web address, its own
branding, a staff workspace and a customer app, all served from one deployment. The product name is
not decided yet; `salon` is a placeholder.

No application code exists yet. The repository currently holds the requirements, the delivery
process and the plan for the first feature.

## Where things are

| Path | Holds |
| --- | --- |
| `docs/brd/` | The business requirements document and `requirements.yaml`, the living requirement register |
| `docs/ROADMAP.md` | The delivery order: the web application first, then the customer mobile app |
| `docs/architecture/ARCHITECTURE.md` | How the platform is built |
| `docs/README.md` | The index of every project document: architecture, engineering rules, design, test plan, environments |
| `docs/design/prototype/` | The HTML design prototype |
| `features/README.md` | The feature portfolio and what is next (generated) |
| `features/<feature>/stages/<stage>/` | A stage's specification, state, test cases, bugs and review |
| `qa/` | QA automation and the lifecycle tooling |
| `.claude/skills/` | The three skills that run the process: `salon-sdlc`, `salon-dev`, `salon-qa`. They hold how work is done; everything about this product is under `docs/` |
| `AGENTS.md` | Guidance for Claude Code and Codex |

## How work moves

Feature -> stage -> release. The lead plans a stage, the developer builds it, QA verifies it as a
separate agent, and the lead closes it only when QA has passed it.

```bash
qa/scripts/sdlc status      # whose turn it is and which command comes next
qa/scripts/sdlc validate    # the gate over requirements, features, stages, tests and skills
```

In Claude Code: `/salon-sdlc stage-build F01-S01`. In Codex: `$salon-sdlc stage-build F01-S01`.

## Prerequisites for the tooling

Python 3.10 or later. The first run of `qa/scripts/sdlc` creates its own virtual environment and
installs PyYAML. The prerequisites for the product itself are listed in
`docs/architecture/tech-stack.md`; stage F01-S01 documents and checks them.
