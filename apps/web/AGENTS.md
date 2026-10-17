# apps/web

One Next.js application serving salon owner/admin/staff workspaces and the operator console.
Customer screens belong only to the later React Native app after M1-web release (CR-010). Before changing anything here, read in full:

- `docs/engineering/web.md`: the surfaces, tenant routing, structure, branding, sessions, tests.
- `docs/design/ui-ux-standards.md` and the prototype at `docs/design/prototype/index.html`: before
  building or changing any screen.
- `docs/engineering/web.md`, "Styling": every screen uses Tailwind CSS and shadcn/ui only (CR-016,
  CR-017). Look the job up in its component map, add the shadcn/ui component with its CLI into
  `src/components/ui/`, and use it; no Native Select or other native control, no hand-built
  look-alike, no CSS Modules or hand-written CSS.
- `docs/architecture/tech-stack.md`: versions. This Next.js version differs from older ones; read
  the installed version's documentation before using a framework feature for the first time here.

The gates for a stage are in its `stage.yaml` under `verification.commands`.
