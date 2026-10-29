# F23-S06 Web app organised by module

## Objective

`apps/web/src` is organised by the same business modules as the API (`features/<module>`), composed
by `surfaces/`, with `app/` holding routing only; import boundaries are enforced by lint; data access
uses the per-module generated client; formatting and dead-code checks are part of the gates; and
Next.js is upgraded past the security advisory the build reported. Every screen, route, text and
behaviour stays exactly as it is.

## Read first

The project documents that hold the rules for this stage. Developer and QA apply the shared
context-reading rule in `salon-sdlc` before their work.

- `docs/changes/CR-021-modular-extractable-architecture.md` (decision 8) and
  `docs/changes/CR-022-module-internal-layout.md` (decision 5, the Next.js upgrade).
- `docs/engineering/web.md` in full, especially "Tenant routing", "Structure", "Clean code", "Where
  things live", "Styling" and "Tests".
- `docs/engineering/backend.md`: "Ownership of tables and paths" (the per-module client).
- `docs/architecture/tech-stack.md`: the web rows, "Web import boundaries", "Formatting and
  dead-code checks", and the pnpm minimum-release-age rule.
- `docs/design/ui-ux-standards.md`: nothing visible may change.
- `docs/operations/verification-evidence.md`.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| NFR-24 | Web and mobile client code is organised by the same business modules as the API, with import boundaries enforced, and shares only non-UI packages ... the code meets the written clean-code standard ... | full for the web |
| NFR-18 | Backend and web are deployable at any time without downtime ... | part: no route or behaviour change |

## Scope

Web and packages:

- **Layout** as in `web.md`, "Structure": `app/` (routing, layouts and thin route handlers),
  `surfaces/{workspace,console,platform}/` (shells, navigation, screens composing features),
  `features/{identity,platform,branding}/` (each with `server/`, `components/`, `hooks/`,
  `schemas/` only as needed, a client-safe `index.ts` and a server entry `server.ts`),
  `components/ui/` (shadcn/ui, unchanged), `components/shared/` (notice, status and other
  cross-feature components), `lib/` (cross-cutting only: `tenancy`, `http`, `observability`,
  `plain-page`, `api-core`, `utils`). Moves follow the mapping in the F23 research: `lib/identity`
  to `features/identity/server`; `lib/operator` and `lib/platform` to `features/platform/server`;
  `components/account` to `features/identity/components` (billing parts to the module that owns
  billing, `platform`); `components/console` screens to `surfaces/console`, plan and billing parts to
  `features/platform`; `components/support` to `features/platform`; `components/shell` to
  `surfaces/*`; `components/notice` and `components/status` to `components/shared`;
  `lib/api/client.ts`, `failure.ts` and `forward-hostname.ts` to `lib/api-core`; `lib/api/salon.ts`
  to `features/branding/server`; `lib/api/meta.ts` to `features/platform` or `lib/api-core` by what
  it serves (record the choice).
- **The BFF route handlers** (`identity-web`, `operator-web`, `platform-web`) stay at their paths
  (the URLs are part of the product) and become thin: each delegates to its feature's server entry.
- **Per-module client:** every API call imports from `@salon/api-contracts/<module>` inside that
  module's `features/<module>/server/`; nothing imports the package root any more, and the root
  export is removed if nothing else needs it.
- **Server-only:** every file under `features/*/server/` starts with `import 'server-only'`.
- **Boundaries:** `eslint-plugin-boundaries` enforces `app` -> `surfaces` -> `features` ->
  `components`, `lib`; no feature imports another feature; no surface imports another surface; no
  deep import into another folder's internals. The stale `(booking)` surface in the lint
  configuration is removed.
- **`packages/format`** (`@salon/format`): money, date and phone formatting moved from
  `src/lib/format`, with its tests, used by the web through the package.
- **Prettier** with `prettier-plugin-tailwindcss` formats every web and package file; the one-time
  reformat is its own commit with nothing else in it. **`knip`** finds unused files, exports and
  dependencies; everything it finds is removed (or, where a tool needs it, configured with the
  reason). Both run as gates.
- **Next.js upgrade** past the security advisory the build reported on 2026-10-30 for 16.4.0: the
  smallest upgrade that the official advisory names as fixed, honouring the pnpm minimum release age
  (`tech-stack.md`); record the advisory id and the version in `handoff.notes`. Do not use any
  `next upgrade` agent command; change the version through pnpm.
- **Clean code** (`web.md`, "Clean code"): no component over about 200 lines without a reason
  beside it; no commented-out code; no `TODO` without an id.
- **Documentation hand-back** in `handoff.notes`.

## Out of scope

- Any visible change: markup, text, styles, routes, status codes or caching headers.
- The mobile app and the `tokens`, `i18n` and `schemas` packages (with `M2-mobile`), Turborepo.
- API changes.

## Acceptance criteria

Each criterion is one observable outcome that a test can pass or fail.

- AC-1: `apps/web/src` contains `app/`, `surfaces/`, `features/`, `components/{ui,shared}/`, `lib/`
  and the other folders `web.md` lists, and nothing else at that level; `src/components/<area>/` and
  `src/lib/{api,identity,operator,platform,format}` no longer exist. (NFR-24)
- AC-2: Lint fails on a fixture where a feature imports another feature, a surface imports another
  surface, `features` imports `surfaces`, or a file deep-imports another folder's internals; and
  passes for the application with `--max-warnings 0`. (NFR-24)
- AC-3: Every API call is made from a `features/<module>/server/` file through
  `@salon/api-contracts/<module>` for that same module, and every file under `features/*/server/`
  imports `server-only`; a check fails on a fixture that breaks either. (NFR-24)
- AC-4: `prettier --check` and `knip` pass as gates, and each fails on a fixture (an unformatted
  file; an unused export). (NFR-24)
- AC-5: `next` is at a version the official advisory lists as fixed, and the production build
  completes without the security stop the build showed on 16.4.0. (NFR-18)
- AC-6: Nothing visible or routable changes: every existing web test passes with unchanged
  assertions; for the operator console and a salon workspace (sign-in, account, staff, activity,
  billing, support, console salons, plans, invoices), the rendered HTML of each page before and
  after the stage differs only in build-specific asset names; the BFF paths answer as before. (NFR-18)
- AC-7: `@salon/format` is used by the web, its tests pass, and no formatting code remains in
  `apps/web/src`. (NFR-24)

## Design notes

- Move first, refactor second: one commit per area of moves (imports only), then the boundary rules,
  then Prettier's reformat, then `knip` removals, then the Next.js upgrade, so each is reviewable.
- `eslint-plugin-boundaries` element types: `app`, `surface`, `feature`, `shared-component`, `ui`,
  `lib`; capture the feature and surface names so cross-feature imports are refused.
- The plain HTML pages' generated CSS (CR-017) is checked by a staleness test; regenerate it if
  Prettier or a move changes its inputs, and prove its output unchanged.
- For AC-6, capture each page's server-rendered HTML on the local stack before the first move and
  after the last, with the same data, and compare after removing hashed asset names.

## API and data changes

Not applicable for this stage. `packages/api-contracts` may drop its root export if nothing uses it.

## UI and UX

No change. AC-6 proves it.

## Risks and test focus

- **A visible regression from a move** (`functional`): AC-6, and QA's regression run over F01 to F03
  that the owner asked for.
- **Server code reaching the browser bundle** (`security`): AC-3's `server-only` guard.
- **The framework upgrade changing behaviour** (`resilience`, `functional`): the proxy's status
  codes, the three-second API deadline and the no-cache headers (`web.md`, "Tenant routing") are
  covered by existing tests that must pass unchanged.

## Verification commands

The developer gates, run from the repository root. Copy them into `stage.yaml` `verification.commands`.

```bash
pnpm install --frozen-lockfile
pnpm -r lint
pnpm -r typecheck
pnpm -r test
pnpm --filter @salon/web build
pnpm format:check
pnpm knip
pnpm contracts:check
```

## Done criteria

- The developer has checked every acceptance criterion on the running product and recorded it in
  the handoff.
- The verification commands are green.
- The advisory id and the Next.js version are in `handoff.notes`, and any difference from
  `docs/engineering/web.md` or `docs/architecture/tech-stack.md` is listed there.
