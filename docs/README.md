# Project documentation

Everything specific to this product lives here: what it is, how it is built, and the rules for
building and testing it. The skills under `.claude/skills/` hold only the process (how work is
planned, built, verified and released) and point here for everything else. A fact has one home: if
it is about this product it is in this folder, and nowhere else.

## Read before you work

| When you are about to | Read |
| --- | --- |
| Start in this repository for the first time | `ROADMAP.md`, `architecture/ARCHITECTURE.md`, `architecture/repository-structure.md`, `architecture/glossary.md` |
| Plan a feature or a stage | `ROADMAP.md`, `brd/requirements.yaml`, `architecture/ARCHITECTURE.md`, `design/prototype/index.html` |
| Store, read, cache, log or schedule any salon's data | `architecture/multi-tenancy.md` |
| Build sign-in, sessions or permissions | `architecture/identity-and-access.md`, `architecture/multi-tenancy.md` |
| Build operator access, salon provisioning or plan definitions | `architecture/operator-console.md`, `architecture/multi-tenancy.md` |
| Change the API (`apps/api`) | `engineering/backend.md`, `architecture/multi-tenancy.md` |
| Change the web app (`apps/web`) | `engineering/web.md`, `design/ui-ux-standards.md` |
| Change the customer app (`apps/mobile`) | `engineering/mobile.md`, `design/ui-ux-standards.md` |
| Build or change any screen | `design/ui-ux-standards.md`, `design/prototype/index.html` |
| Add a dependency or use a framework feature for the first time | `architecture/tech-stack.md` |
| Test a feature | `qa/test-plan.md`, `qa/test-types.md`, `qa/automation.md` |
| Record verification evidence or close a QA-passed feature | `operations/verification-evidence.md` |
| Review how usable something is | `qa/review-context.md` |
| Touch anything that is not local, or prepare a release | `operations/environments-and-releases.md` |
| Push, open a feature PR, confirm a merge, start the next feature or clean up branches/worktrees | `operations/git-delivery.md` |

A stage's `spec.md` has a "Read first" section naming the documents for that stage. Read those in
full before starting; this table is for everything the spec did not anticipate.

## All documents

| Document | What it holds |
| --- | --- |
| `ROADMAP.md` | The delivery order: the web application first, then the customer mobile app |
| `brd/Salon-SaaS-BRD-v1.0.pdf` | The business requirements document: market research, the reasoning behind the solution, open decisions D-01 to D-13 |
| `brd/requirements.yaml` | The living requirement register: every functional and non-functional requirement, by id |
| `architecture/ARCHITECTURE.md` | The shape of the system and why |
| `architecture/tech-stack.md` | Languages, frameworks, versions, external services, machine prerequisites, placeholder names |
| `architecture/repository-structure.md` | What lives where in the repository, and who may change it |
| `architecture/multi-tenancy.md` | The ten isolation rules, the checklists for a new table and a new endpoint, and the rules for jobs, caches, files and logs |
| `architecture/identity-and-access.md` | F02 security policies, permissions, authentication contracts and account privacy |
| `architecture/operator-console.md` | Operator authority, console hostname, platform database privileges, atomic salon/Owner bootstrap and immutable plan definitions |
| `architecture/glossary.md` | The words of the business, as the code and the screens use them |
| `engineering/backend.md` | Rules for the API: modules, API conventions, persistence, migrations, booking integrity, virtual threads, security, tests |
| `engineering/web.md` | Rules for the web app: staff workspace and operator console, tenant routing, branding, sessions, tests |
| `engineering/mobile.md` | Rules for the customer app and per-salon builds |
| `design/ui-ux-standards.md` | Screens, tokens, states, formats, widths, accessibility, wording |
| `design/prototype/index.html` | The HTML prototype: the reference for screens, flow and wording (`brand.json` beside it is its sample brand) |
| `qa/test-plan.md` | How this product is tested: tools, layout of `qa/`, the synthetic salons, accounts, targets, severity examples |
| `qa/test-types.md` | Which kinds of test a feature needs, how much of each, and what each must assert |
| `qa/automation.md` | Conventions for the Playwright, Maestro and k6 automation |
| `qa/review-context.md` | Who uses the product, what matters to them, and the products it is compared with |
| `operations/environments-and-releases.md` | The environments, what a test may and may not touch, and the rules this product imposes on a release |
| `operations/git-delivery.md` | GitHub repository and authorisation, feature PR/merge records, cleanup evidence, and the existing-history transition |
| `operations/skill-evaluation.md` | The requests used to check that the skills and these documents lead an agent to the right behaviour |
| `operations/verification-evidence.md` | Recorded gate/full-feature proof, environment identity, progress and safe reuse at closure |
| `decisions/DECISION_LOG.md` | Decisions taken, one entry each |
| `changes/README.md` | Change requests: how a requirement changes after the baseline |
| `changes/CR-007-prototype-design-fidelity.md` | Owner-approved HTML design authority and existing UI repair |
| `changes/CR-008-skill-productivity-and-progress.md` | Owner-approved shared skill upgrades, progress visibility and guarded evidence reuse |
| `changes/CR-009-resume-without-duplicate-qa.md` | Owner-directed reuse of passed QA across interruptions and scoped verification of actual changes |
| `changes/CR-010-staff-web-only.md` | Owner-approved salon staff web only, deferred React Native customer app and platform mapping |
| `changes/CR-005-local-http-staging-tls.md` | Owner-approved change: local HTTP-only stack and staging-only TLS verification |

`api/openapi.json` is generated by the API build once it exists. `brd/source/` holds the files the
BRD was built from.

## Keeping these documents true

- The lead owns these documents. The developer and QA do not edit them: when what was built or
  tested differs from what a document says, the developer says so in `handoff.notes` and the lead
  corrects the document before the stage is closed; QA says so in `review.md` and the lead corrects
  it before the feature is closed.
- A decision that changes a document is also recorded in `decisions/DECISION_LOG.md`.
- A new document is added to both tables above. `qa/scripts/sdlc validate` fails when a document is
  missing from this index, or when the index, a skill or an `AGENTS.md` points at a document that
  does not exist.
