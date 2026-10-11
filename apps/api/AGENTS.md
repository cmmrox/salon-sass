# apps/api

The API: one deployable, a modular monolith. Before changing anything here, read in full:

- `docs/engineering/backend.md`: modules, API conventions, persistence, migrations, booking
  integrity, virtual threads, security, tests.
- `docs/architecture/multi-tenancy.md`: the isolation rules, the checklists for a new table and a
  new endpoint, and the rules for jobs, caches, files and logs.
- `docs/architecture/tech-stack.md`: versions. Read the installed version's documentation before
  using a framework feature for the first time here.

The gates for a stage are in its `stage.yaml` under `verification.commands`.
