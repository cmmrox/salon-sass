# Web rules (`apps/web`)

One Next.js application serves the salon staff workspace and operator console. The hostname
identifies the salon; only owners, admins and relevant staff sign in here. Customer UI belongs
exclusively to the later React Native app after M1-web release (CR-010).

| Surface | Reached at | Users |
| --- | --- | --- |
| Workspace | Salon hostname; `/` redirects to `/workspace` | Salon owners and staff |
| Operator console | Reserved platform hostname, never a tenant | Operator staff |

The exact console hostname, separate operator cookies/BFF, permissions and console route contract
are in `docs/architecture/operator-console.md`. Console routing is recognized separately from
salon resolution; bare platform status and other reserved hostnames do not become console aliases.

Legacy `/sign-in` and `/account` redirect to `/workspace/sign-in`. They never render customer
OTP/profile/privacy UI. `/identity-web/customer/*` returns no-store 404 without upstream API,
message or customer-cookie work. Existing direct customer Java APIs remain mobile foundations.

## Tenant routing

- `proxy.ts` reads the `Host` header only (never `X-Forwarded-Host` or `Forwarded`), applies the
  hostname rule of `docs/architecture/multi-tenancy.md`, and resolves the salon through the API on
  every request, except a module's BFF call (the API resolves the salon from the same `Host` when
  the call reaches it) and the platform's own static files. This is routing only. It is not a security boundary: the API and the database
  enforce access.
- A hostname that is not exactly one slug-shaped label in front of the platform domain (a bare
  network address included) is "salon not found" without calling the API. Reserved slugs are left
  to the API, which answers 404.
- The API is reached at its one configured address, `SALON_API_BASE_URL`, never at an address taken
  from the request. The salon is named to it by the `Host` header of that request,
  `<slug>.<platform domain>`, sent through a fetch built on `node:http`
  (`src/lib/api-core/forward-hostname.ts`), because Node's `fetch` drops a `Host` header.
- **Status codes (decision 028).** The proxy answers "salon not found" (404), "temporarily
  unavailable" (503, a suspended salon, on every path of its hostname) and "service unavailable"
  (503, the API cannot be reached within three seconds, or `SALON_PLATFORM_DOMAIN` is unset) itself,
  as complete HTML from `src/lib/plain-page/`, with no script. Next.js 16 resets a status the proxy
  sets for a page reached by rewrite, and has no 503 for a page. Neither page reveals whether a
  salon exists elsewhere, and both use the neutral theme.
- An active salon's `/` redirects to the fixed same-host `/workspace`; unauthenticated entry
  reaches `/workspace/sign-in`. `/salon-site` is not publicly reachable and returns 404.
- Nothing about a salon is kept by any shared cache: every response on a salon's hostname carries
  `Cache-Control: private, no-cache, no-store, max-age=0, must-revalidate`, and every page is
  rendered per request (the root layout asks for the connection, so the content security policy's
  nonce reaches every script; nothing is prerendered). The one exception is a salon's public
  photos, which the visitor's own browser may keep under the salon's own address: a service photo
  or a published brand image for a day, a person's photo for an hour, `private` and `immutable`,
  because each is addressed by an id that a new upload replaces (`lib/http/photo-cache.ts`; CR-030, approved by the
  owner). Never
  `public`, never `s-maxage`; a draft brand image, any answer that is not 200 and a suspended or
  unknown salon stay `no-store`. The platform's own static files (`/images`, `/icons`, `/brand`,
  `/fonts`) are the same on every hostname, hold nothing of a salon, and may be cached for a day.
- Every response sends `X-Content-Type-Options: nosniff`, `Referrer-Policy: same-origin` and
  `X-Frame-Options: DENY`, and every page a content security policy (`lib/http/security-headers.ts`):
  scripts only from the origin with the request's nonce (`'strict-dynamic'`, never
  `'unsafe-inline'`), styles from the origin and inline, images from the origin, `blob:` and
  `data:`, fonts and requests from the origin, no plugins, no framing, forms and the base URL on the
  origin. A page that needs more changes the policy in that file, with the reason.
- Never read a tenant from a query parameter, a cookie the browser can set, or local storage.

## Structure

The web app is organised by the same business modules as the API (CR-021):

```
apps/web/src/
  app/                    routing only: route groups (workspace), (console), (platform); pages,
                          layouts and thin route handlers (the BFF) that delegate to a feature
    _proxy/               the proxy's routing (it composes several modules, so it is not in lib/)
  surfaces/<surface>/     workspace, console: shells, navigation and screens that compose features.
                          The platform host's two pages compose shared parts directly, so there is
                          no surfaces/platform
  features/<module>/      one folder per API module that the web uses (identity, platform, branding,
                          catalogue, staff, booking, payment, notification, customer, reporting)
    server/               'server-only' calls to that module's API through the generated client; `routes.ts` declares
                          the module's BFF actions and the permission each needs
    client/               the module's typed BFF actions and its TanStack Query hooks (`*-data.ts`); the session, the
                          cross-tab lock and failures are `lib/api-core/request.ts`
    pages.ts              the module's workspace pages (path, title, permission), safe to load in the browser
    components/           that module's product components, built from components/ui and Tailwind
    hooks/                client hooks; a form's checks are the module's `*-form.ts` (there is no schema library)
    types.ts              types shared by the module's server and client code
    index.ts  server.ts   the client-safe entry and the server entry; nothing else is imported from outside
  components/ui/          shadcn/ui (unchanged)
  components/shared/      product components used by several features or both surfaces: AppShell,
                          BrandStyle, Navigation, Notice, Status, LiveStatus
  lib/                    cross-cutting only, no business module: tenancy, http, observability,
                          plain-page, api-core, utils
  hooks/  i18n/  styles/
apps/web/test/            test helpers, imported as @test/*
```

`src/proxy.ts` and `src/instrumentation.ts` are framework entry files outside every element.

- Imports point one way: `app` -> `surfaces` -> `features` -> `components`, `lib`. A feature never
  imports another feature; the surface composes them. A surface never imports another surface.
  `eslint-plugin-boundaries` enforces both, and lint fails on a violation; its element types are
  `app`, `surface`, `feature`, `shared-component`, `ui`, `lib` (which also covers `hooks`, `i18n`
  and `styles`) and `test-support`, and the rules apply to tests too.
- When one feature needs another's data, it declares a small interface (a port) and the route that
  composes them passes the other feature's implementation in. A module's BFF actions are not passed
  this way any more: they are registered in the route table (below); `SalonAccount` remains only
  for the support view's entry and end.
- The workspace navigation lists the route table's pages that the person's permissions allow.
  Module entitlements join it with F18 (decision 077).
  It reads them from the API (plan features plus add-on modules, decision 077). Hiding an item is a
  convenience; the API refuses the module's routes on its own.
- Each feature exposes a client-safe `index.ts` and a server entry `server.ts`; a surface exposes
  `index.ts`. Code under `features/*/server/` and every `server.ts` starts with
  `import 'server-only'`. No deep import into another folder's internals. A lint rule refuses the
  contracts package root, another module's client, a client outside server code and the shared
  module's client outside `lib/api-core`; `src/app/module-boundaries.test.ts` proves each rule
  fails on a fixture.
- Route groups per surface: `(workspace)`, `(console)`; legacy route aliases contain no customer UI.
- Pages and layouts compose; they do not hold business rules. Data access goes through the
  generated client in `packages/api-contracts`, imported per module (`@salon/api-contracts/<module>`),
  inside that module's `features/<module>/server/`.
- Mutations go through the BFF route handlers, which re-check the session; there are no Server
  Actions alongside them.
- The server renders the frame: the principal, the salon's brand and entitlements, and the page's
  shell. A screen's data is read after first render through the client data layer.
- **One client data layer (decision 121).** After first render, a screen reads and changes data
  only through TanStack Query hooks in its module's `features/<module>/client/`, over the one
  request module in `lib/api-core`. That module renews an expired access credential once and
  retries the request, so no screen sends a signed-in person to sign-in while the session can be
  renewed; maps error codes in one place; and never applies an answer to a form or list that has
  changed since the request was made. A list other people change (bookings, the calendar) is
  refreshed by polling at an interval stated in its hook. Every hook has a unit test in which an
  earlier request answers after a later one (`apps/web/test/late-answers.tsx`). Hand-written
  `useEffect` fetching is not added. Each frame has its own query client, keyed by a scope the
  server makes from the hostname, the kind of session and a hash of the session; every key starts
  with it, and the client is dropped when the frame goes, so nothing one person or one salon read
  can be shown for another. The layer does not retry a read, and only Bookings reads again on
  focus and every 30 seconds; a re-read that fails keeps the last good list and says it may be out
  of date. A change goes straight out and is asked again once, with the same body and
  `Idempotency-Key`, only after the answer that says the credential was refused before anything was
  done (401 `invalid_session`, renewable); a lost or timed-out answer is never asked again by the
  layer.
- **Who refuses what in the BFF.** A read of a module endpoint is not checked in the BFF first: the
  API authenticates the credential, resolves the salon from the `Host` and requires the permission
  itself, so a missing permission is the API's 403. Every change, every upload, identity's own
  actions, a support view's reads and a read whose endpoint answers anyone (marked `open` in its
  route) are checked in the BFF, session and permission, before the call.
- **Routes are registered per module.** Each module declares its workspace pages in
  `features/<module>/pages.ts` and its BFF actions with the permission each needs in
  `features/<module>/server/routes.ts`; `surfaces/workspace/routes.ts` composes them into the one
  table the proxy's protected pages, the page, the BFF, the frame's title and the menu read.
  `identity` holds only sign-in and the session's own actions, a new feature edits no other module
  to add a route, and the lint rule `local/own-routes` refuses a module's routes or pages declared
  anywhere else.
- Every route group has an error boundary (`error.tsx`), and the application a `global-error.tsx`,
  so one value that fails to render does not blank the page.
- A BFF handler's time limit starts after it has read the request body, and an upload or a long
  read has its own stated limit.
- No money arithmetic, availability logic or policy logic in the browser. Show what the API returns.
- Forms validate for the user's convenience and the API validates for real. Show the API's field
  errors next to the fields.

## Clean code

The same standard as the API (`docs/engineering/backend.md`, "Clean code"), with the web's tools:

- Names in the business's words; a component is named for what it shows, a hook for what it gives.
- Small units: aim for components under about 200 lines (many older ones are longer and are split
  when next changed); a component that mixes data access with layout is
  split; logic that is not rendering moves to a hook or to `features/<module>/server/`.
- Nothing dead: no commented-out code, unused component, export, file, dependency or message key.
  `knip` fails CI on an unused file, export or dependency. A `TODO` names its stage, change
  request or bug.
- One format: Prettier, with the Tailwind class-sorting plugin, formats every file; `prettier
  --check` and `eslint --max-warnings 0` are part of the gates. TypeScript is `strict`; no `any`,
  no non-null assertion without a comment saying why it holds.
- Comments say why, not what.

## Where things live

| Thing | Place |
| --- | --- |
| Pages and layouts | `apps/web/src/app/`, in the route groups `(workspace)`, `(console)`; legacy route aliases contain no customer UI |
| Strings | `apps/web/messages/<locale>.json`, read through `next-intl`. There is no locale in the URL |
| Theme (every colour, radius, size and font, as CSS variables) | `apps/web/src/styles/theme.css`, plain CSS with no Tailwind directive, so the plain pages can inline it. See "Styling" |
| The application's one style sheet | `apps/web/src/app/globals.css`: Tailwind, the theme, `@theme inline` and the base layer |
| shadcn/ui components | `apps/web/src/components/ui/`, added by the shadcn CLI and committed; configured by `apps/web/components.json` |
| Product components | `apps/web/src/features/<module>/components/`, or `components/shared/` when several features use one; built from `ui/` components and Tailwind utilities |
| Screens and shells of a surface | `apps/web/src/surfaces/<surface>/` |
| Class name helper | `cn()` in `apps/web/src/lib/utils.ts` (`clsx` with `tailwind-merge`) |
| The proxy's plain HTML pages | `apps/web/src/lib/plain-page/`. Their markup uses Tailwind utility classes, and their CSS is generated by Tailwind from the theme and those classes and committed (CR-017); a test fails when it is stale. See "Styling", rule 9 |
| Formatting of money, dates and phone numbers | `packages/format` (`@salon/format`), shared with the customer app |
| Calls to the API | `apps/web/src/features/<module>/server/`, through the per-module generated client; the shared request plumbing (base URL, hostname forwarding, timeouts) in `apps/web/src/lib/api-core/` |

- A string literal in JSX fails lint. Add the string to the messages file.
- The application's own not-found page exists so that even a 404 is translated. Add an error
  boundary page the same way when a route can fail while rendering.
- Rendering follows the framework's current defaults (cache components and partial prefetching).
  Data fetched while rendering sits inside a suspense boundary or is marked cacheable; the page is
  served with its loading skeleton and the result is streamed in.
- The web app waits three seconds for the API, in the proxy and in a page, and then shows the
  unavailable state. A page never hangs on the API.
  Account BFF calls share one three-second API cancellation budget across proxy,
  metadata and mutation work. It starts when the request arrives and starts again once the
  request's body has been received, so the time a slow connection takes to send a body is not
  taken from the API (BUG-F04-001). An upload's call to the API has its own limit of ten seconds
  (`UPLOAD_API_TIMEOUT_MS`; the operator's holiday import has ten, `HOLIDAY_IMPORT_API_TIMEOUT_MS`, and an
  export download thirty, `EXPORT_DOWNLOAD_API_TIMEOUT_MS`), counted from when its form has been read; the form is read only after the
  session and permission check, so a caller who is not signed in is refused with its upload unread.
  (The framework hands a route handler the body only once it has all arrived, so that check has its
  full budget after a slow upload.) The response-finish log also includes framework/error serialization
  after cancellation; measure it separately from upstream wait. The existing F01-S03 AC-8
  end-to-end unavailable-response deadline is five seconds. Do not equate a three-second upstream
  timer with an exact 3000 ms HTTP-completion assertion or add a fresh timer for each chained call.
- `agentRules` is off in `next.config.ts`, so that the framework does not write into
  `apps/web/AGENTS.md`.
- Local startup uses `apps/web/scripts/serve.mjs`, a custom server around Next.js's request
  handler, so that both IPv4 and IPv6 loopback work. Commands and settings are in
  `docs/operations/environments-and-releases.md`. The production image instead runs Next.js's
  generated standalone server (`apps/web/server.js`), on one container port (decision 030).
- `GET /healthz` is the container's readiness check. On SIGTERM readiness fails immediately,
  while the app keeps serving for the drain delay so the edge removes it from rotation, then
  finishes accepted requests. The container sets `NEXT_MANUAL_SIG_HANDLE=true`; keep the
  application drain handler active when changing how the image starts.
- Each answered request writes one JSON log line with method, path without query, status,
  duration, `tenant.slug` and `trace.id`. A W3C trace continues into the API; no cookie, header
  or query value is logged. The standalone server uses the same request instrumentation as
  the local custom server.

## Styling (CR-016)

Every screen and component of the web app is built with **Tailwind CSS and shadcn/ui**, and
nothing else. This holds for every feature, including the operator console and every later
workspace screen. It replaces CSS Modules (decision 046, replacing decision 014's styling choice),
because the owner set one standard for all Next.js work in this product.

**Setup.** Versions are in `apps/web/package.json`; the tools are in `docs/architecture/tech-stack.md`.

- Tailwind CSS 4 through `@tailwindcss/postcss` in `apps/web/postcss.config.mjs`. There is no
  `tailwind.config.*`: the theme is configured in CSS.
- `apps/web/src/app/globals.css` is the only style sheet the application imports. It holds, in this
  order: `@import "tailwindcss"`, `@import "tw-animate-css"`, the import of `src/styles/theme.css`,
  `@theme inline` (which maps the theme variables to Tailwind's colour, radius, font and size
  names), and `@layer base`.
- shadcn/ui is configured by `apps/web/components.json`: style `new-york`, React Server Components
  and TSX on, CSS variables on, `lucide` icons, and the aliases `@/components`, `@/components/ui`,
  `@/lib`, `@/lib/utils` and `@/hooks`. Add a component from `apps/web` with
  `pnpm dlx shadcn@<version in tech-stack.md> add <component>`, and commit what it writes.
- **After every `shadcn add`:** the CLI writes `import { cn } from "cn"` and adds the npm package
  `cn`, ignoring the utils alias. Point the new component at `@/lib/utils` and remove the `cn`
  package; there is one `cn()`. Then remove any `dark:` classes and the CLI's focus ring in favour of
  the theme's focus outline, and map its colours to the theme (rule 6).
  The CLI also appends `hsl` `:root` and `.dark` variable blocks after a `---break---` marker in
  `globals.css` (delete them; the theme is `theme.css`), and asks before overwriting an existing
  `ui/` file: answer no, or a component already adapted to the theme is lost.
- Tailwind's preflight resets browser defaults: lists lose their markers, headings and `pre` lose
  their margins, and links lose their underline (the base layer restores the underline on `a`).
  Set what a screen needs with utilities. Tailwind 4's `hover:` applies only on devices that can
  hover.
- `cn()` in `apps/web/src/lib/utils.ts` extends tailwind-merge with the theme's own Tailwind names
  (font sizes such as `text-field` and `text-small`, the named radii, shadows, spacing, containers
  and breakpoints), so that a theme font size is not mistaken for a colour and two theme radii
  replace each other. A name added to `@theme inline` in `globals.css` is added there too;
  `lib/utils.test.ts` covers it.

**Rules.**

1. **Every control, menu, list row, status label, message, empty state, loading indicator, pager
   and overlay is a shadcn/ui component, and every style is a Tailwind utility (CR-016, CR-017).**
   Before building any screen, look the job up in the table below and in the shadcn/ui catalogue;
   when shadcn/ui has a component for it, add it with the CLI and use it. Never use a native form
   control dressed as a component (`<select>`, `<input type="checkbox">`, `<dialog>`), never
   hand-compose what a component already provides, and never install a second component library.
   Plain text (headings, paragraphs, a list of plain text), layout containers and landmarks are
   written as HTML elements with Tailwind utilities.

   | Job | shadcn/ui component |
   | --- | --- |
   | Action, link that acts as a button | Button (`asChild` for a link) |
   | Text input, labelled input with help and error | Field (`Field`, `FieldLabel`, `FieldDescription`, `FieldError`, `FieldGroup`, `FieldSet`) around Input or Textarea |
   | Input with a button or icon inside it (password visibility) | Input Group |
   | One-time code | Input OTP |
   | Choice from a list | Select (never Native Select) |
   | Check box or switch with its label | Field `orientation="horizontal"` with Checkbox or Switch |
   | Workspace navigation and any menu | Sidebar (rail from 900px, its sheet below 900px); Dropdown Menu or Navigation Menu for other menus |
   | Container | Card |
   | Row of a list (device, staff member, audit entry, any record) | Item (`ItemGroup`, `Item`, `ItemContent`, `ItemTitle`, `ItemDescription`, `ItemActions`); the `divided` variant rules a line under each row, as the prototype's settings lists do (F04) |
   | Tabular data meant to be compared across columns | Table |
   | Status word, role, tag | Badge; the prototype's warning pill (`.pl.wn`) is Badge `variant="caution"` (F06-S03) |
   | Nothing to show | Empty |
   | Page through a list | Pagination |
   | Message, refusal, warning, success | Alert, or Sonner for a passing notice; the prototype's information banner (`.bn.in`) is Alert `variant="info"` on the theme's `--info-surface` (F05-S01) |
   | Pending data, busy action | Skeleton, Spinner |
   | Divider | Separator |
   | Confirmation, side panel | Sheet (bottom sheet on phones, right drawer from 900px), Dialog or Alert Dialog |
   | Person or salon initial | Avatar |
   | One-time secret shown for copying (authenticator setup key and URI) | No shadcn/ui component: a labelled `<output>` inside a Field, styled with Tailwind (CR-017) |
   | Hint on hover or focus | Tooltip |
   | Sections switched in place | Tabs |
   | One choice from a few visible options (brand colour swatches) | Toggle Group, `type="single"` (F04-S02); the prototype's segmented control (`.seg2`) is its `segment` variant (F05-S02) |
   | Brand colour typed as `#RRGGBB` | Field around Input, with a swatch of the value beside it; no native colour input (F04-S02) |
   | Choosing a file (logo, cover) | No shadcn/ui component: a Button that opens a visually hidden `<input type="file">` inside a Field, whose label, description and error belong to it, with the chosen image previewed (F04-S02) |
   | Choosing a date | Popover with Calendar, opened from a Button showing the chosen date (F04-S03) |
   | Leaving a Settings tab with unsaved edits (a tab change as well as a link) | `settings-leave.tsx` holds the tab change and opens the shared leave dialog, whose `area` prop sets its test ids (F04-S04) |
| A pressed or unpressed choice in a row (a weekday open or closed) | Toggle, `chip` variant for the prototype's `.chip` (F04-S03). Calendar day buttons carry full date names (`lib/dates/calendar-labels.ts`), because react-day-picker's own labels fail axe's label-in-name rule |

   A job the table and the catalogue do not cover is raised with the lead before it is built.
2. Product components in `src/features/<module>/components/` (or `src/components/shared/`) compose `ui/` components with Tailwind utilities.
   Variants that recur go into the `ui/` component with `cva`, not into each screen.
3. A `ui/` component may be edited after the CLI writes it, to carry the prototype's look (classes
   and variants). Keep its accessibility behaviour: roles, focus management, keyboard handling,
   `aria-*` and the primitives it is built on. Behaviour edits are limited to what the product
   needs and are listed in the stage handoff: translated strings passed by the caller instead of
   the CLI's English, no persistence cookie, sizes from the theme instead of a `style` prop, unused
   parts removed, and where focus lands on open and close. As built (CR-017), `ui/sidebar.tsx`
   focuses the current route when its phone sheet opens and returns focus to the trigger, keeps no
   cookie, takes its labels from the caller, reads its widths from the theme and has no
   `SidebarRail`; `hooks/use-mobile.ts` is the CLI's hook rewritten with `useSyncExternalStore`, with
   the `rail` breakpoint (900px). `ui/pagination.tsx` uses Button controls (the lists page in place),
   takes its words from the caller and adds `PaginationCurrent`; `ui/spinner.tsx` is decorative
   unless given a translated label; `ui/input-otp.tsx` renders no `noscript` style; `ui/input-group.tsx`
   carries the theme's field size and addon padding; a caller may drop `FieldError`'s role where a
   surrounding Alert already announces the refusal.
4. No CSS Modules, no new style sheet, no `<style>` element other than `BrandStyle` (the `input-otp`
   library inserts its own runtime `<style>` for Input OTP, and Sonner one for its toasts; those are
   the libraries'), and no `style`
   prop for a visual value. The only exception is a value that exists only at run time and that no
   utility can name.
5. No raw value in a component. A colour, radius, font, shadow or prototype-specific size comes
   from the theme, as a Tailwind name (`bg-primary`, `rounded-card`) or as a theme variable
   (`w-(--size-sidebar)`). An arbitrary colour (`bg-[#D6086A]`, `text-[rgb(...)]`) fails the
   styling test. Tailwind's spacing scale is used for ordinary spacing.
6. The theme is the prototype's final tokens, under shadcn/ui's variable names. Do not use shadcn's
   base colours or its default palette. The required mapping:

   | shadcn/ui variable | Value |
   | --- | --- |
   | `--background` / `--foreground` | Canvas `#F3F0EB` / ink `#111827` |
   | `--card`, `--popover` (and their foregrounds) | `#ffffff` / ink |
   | `--secondary`, `--muted` | `#EAE5DC`; `--muted-foreground` `#6B7280` (only on white, see `ui-ux-standards.md`) |
   | `--border`, `--input` | `#DDD6CA` |
   | `--primary` / `--primary-foreground` | `var(--color-brand)` / `var(--color-on-brand)`, set per salon by `BrandStyle` |
   | `--accent` / `--accent-foreground` | `var(--color-brand-soft)` / a foreground that reaches 4.5:1 on it |
   | `--destructive` | `#B3261E` |
   | `--ring` | `#1D5FD0`, the keyboard focus outline |
   | `--radius` and the named radii | Cards 24px, fields 20px, actions pill, as in `ui-ux-standards.md` |

   The other prototype tokens are in `theme.css` too and are mapped in `@theme inline`. A variable
   in `@theme inline` must not read a variable of its own name (Tailwind would emit
   `--color-x: var(--color-x)`), so `theme.css` names status colours without the prefix
   (`--positive`, `--positive-surface`, `--caution`, `--caution-surface`, `--negative-surface`,
   `--backdrop`, `--surface-translucent`) and `@theme inline` maps them to `--color-*`. Sizes keep
   their `--size-*` names and map to Tailwind's namespaces (`--spacing-header`, `--radius-card`,
   `--text-body`, `--container-workspace` and so on): read `globals.css` for the list before adding
   one, and add a missing one there rather than an arbitrary value.
   The workspace breakpoints are Tailwind breakpoints: `rail` (900px, the Sidebar becomes a fixed rail
   instead of its phone sheet) and `wide` (1200px, the wider rail and spacing).
7. There is no dark mode. The prototype has none; do not add `dark:` variants.
8. Poppins is loaded from `public/fonts/poppins/` by the `@font-face` rules in `theme.css`, and is
   the theme's `--font-sans`. Icons: the navigation keeps the prototype's own SVG glyphs, drawn
   inside `SidebarMenuButton`; every other icon comes from `lucide-react`. The workspace navigation
   is the shadcn/ui Sidebar (CR-017): a fixed rail from 900px at the prototype's widths, and below
   900px the Sidebar's own sheet, opened by a `SidebarTrigger` in the sticky header.
9. **The plain proxy pages** (`src/lib/plain-page/`) are complete HTML strings answered by
   `proxy.ts` with no script, so they cannot render React components. Their markup carries the same
   Tailwind utility classes as the shadcn/ui Card and Alert they mirror, and their CSS is generated
   by Tailwind from the theme and those classes by the script named in `apps/web/package.json`
   (it compiles `globals.css` with `source(none)` and an `@source` for the pages' classes, so the
   pages share preflight and the base layer; the output holds real `@layer` and `@property` rules
   and preflight's own colour values, and is exempt from the colour scan as generated output). The
   generated file is committed, regenerated after any change to the pages or the theme, never edited
   by hand, and a test fails when it is stale (CR-017). No hand-written CSS remains apart from the
   theme (`theme.css`) and the Tailwind entry (`globals.css`).
10. `src/styles/styling-rules.test.ts` enforces rules 4, 5 and 9. It fails on any `*.module.css`,
    any hand-written style sheet other than `globals.css` and `theme.css`, any style sheet import
    outside the root layout, `global-error.tsx` (which stands in for the root layout when it fails, and
    so renders its own document) and the generated plain-page CSS, an arbitrary colour in a class name,
    or a colour value (hex, `rgb()`, `hsl()`, `oklch()`, `color-mix()` and the like) in any `.tsx`
    file or any code under `src/components`, `src/surfaces`, `src/features` and `src/app`. `src/styles/brand.ts`, which computes
    brand colours, is the one code file that holds colour values. ESLint (`eslint.config.mjs`) refuses raw
    controls outside `components/ui`, CSS Module imports and a `style` prop outside its `STYLE_PROP_ALLOWED`
    list, so a violation fails `pnpm -r lint` as it is typed. A composition test enforces rule 1
    across every product component under `src/components`, `src/surfaces`, `src/features` and
    `src/app`: no raw `button`, `input`,
    `select`, `textarea`, `table` or `dialog` element, no import of Native Select, and the
    expected shadcn/ui `data-slot`s rendered on each screen. Fix the code, never the test.
11. The confirmation overlay is the shadcn/ui Sheet, a bottom sheet on phones (capped by
    `--size-sheet-max-height`) and, with `rail:` classes, a full-height right drawer of
    `--size-drawer` from 900px. Radix restores focus to the opener on a timer after the sheet or
    Select closes, so a test waits for focus rather than reading it synchronously. A test drives a
    shadcn/ui Select by opening its trigger and choosing the option by role and name (or its
    `account-role-option-<role>` test id), never with `selectOption`; Radix renders a hidden native
    select inside a form, which is not the control.

## Branding

- A salon's brand (name, logo, cover, colour) arrives from the API and is applied through CSS
  custom properties on the root, which the theme's `--primary` and `--accent` read. Components use
  the theme; none hard-codes a brand colour.
- The text colour on the brand colour is computed for contrast. Never assume white. It is white or
  ink, whichever contrasts more, when that reaches 4.5:1; otherwise pure black, which always does
  (at least 4.58:1) for mid-tone brands such as `#808080` (BUG-F02-013).
- Every salon screen, sign-in and workspace alike, uses the salon's colour as its accent
  (CR-013), with the prototype's `#D6086A` when the salon has none. Only the operator console uses
  the platform's neutral theme, because it belongs to no salon.
- A page without a salon (the platform status page, not-found, the plain pages, the operator
  console) has no `BrandStyle`, so `--color-brand` and with it `--primary` and `--accent` are unset.
  Such a page must not use a primary-coloured component until it sets the neutral accent on its
  root: ink `#111827` as `--color-brand`, white as `--color-on-brand`, `#EAE5DC` as
  `--color-brand-soft` and ink as `--color-brand-text`, from named constants beside the brand rule
  in `src/styles/brand.ts` (decision 047). The operator console (F03) does this in its layout.
- Entry decides on the server, in the proxy (`src/app/_proxy/route-request.ts`,
  `src/features/identity/server/entry.ts`), with real HTTP 307s: a page's own `redirect()` streams as a client
  meta redirect after a shell is painted, under `cacheComponents`. `/workspace` decides on the
  presence of the session cookies, with no API call; a protected workspace page costs one
  `/identity/me` call from the proxy, inside the same three-second budget, and the page checks the
  session again. The server never renews a session: an expired access cookie with a refresh
  credential renders the workspace and the browser renews under its cross-tab lock. Known limit
  (decision 045): if that refresh credential was already revoked elsewhere, the visitor sees their
  salon's named workspace briefly before the browser sends them to sign-in. No generic name or
  account skeleton is painted on the way.
- The brand is applied by `BrandStyle` from `brandRootRule` (`src/styles/brand.ts`):
  `--color-brand`, `--color-on-brand`, `--color-brand-soft` and `--color-brand-text`, the brand
  when it reaches 4.5:1 on white and ink otherwise, for text drawn in the brand colour.
- A salon whose API colour is missing or not `#RRGGBB` keeps its name and identity and gets the
  default `#D6086A` (BUG-F02-010); the database refuses an invalid stored colour, so this guards
  only against a malformed API answer.
- A brand change is a draft until the salon publishes it. The customer booking page (F24) and the future React Native app consume the published brand.

Staff sign-in uses the dedicated CR-012 layout without the protected workspace rail/header. It
shows the hostname-resolved salon name/initial and a shared decorative interior fallback.

## Sessions

- Tokens live in secure, HTTP-only cookies set by the server. Nothing sensitive in local storage.
- Web issues staff session cookies only. Customer tokens belong to the future native client;
  direct API principal kinds remain isolated.

## Text, formats and accessibility

- Every string comes from the translation files. No text literal in a component.
- Currency, dates, times and phone numbers are formatted with the salon's locale, currency and
  time zone, through one formatting module. A typed mobile number is checked with
  `@salon/format/mobile`, a separate entry so only screens that check one load its metadata.
- A screen that depends on "now" (a hold's countdown, "today") takes it from the API's clock
  (`GET /api/v1/meta` through the BFF), read once on load and kept as an offset, never from the
  browser's clock; at a deadline it asks the API what happened rather than deciding itself
  (F07-S03).
- Follow `docs/design/ui-ux-standards.md`: semantic elements, labels, focus order, visible focus, keyboard
  access to everything, contrast on the brand colour.

## Tests

- Unit and component tests with Vitest and Testing Library for logic and for components with
  states or branches. Assert behaviour, roles and test ids, not utility class names.
- Give every interactive element a `data-testid`, named as `docs/design/ui-ux-standards.md` says.
  QA's browser tests depend on them, and QA cannot add them.
- Every screen a stage adds or changes ships with an automated accessibility check run by the
  developer, with no violation at WCAG 2.2 AA, colour contrast included. Since F04-S01 it is
  `pnpm --filter @salon/web test:a11y`: Vitest's `a11y` project in browser mode (real Chromium, the
  compiled Tailwind theme), axe-core run directly with `vitest-axe`'s matcher, at every documented
  width with a dark and a light brand. Helpers are under `apps/web/test/` (`a11y.ts`, `viewport.ts`,
  `theme.ts`); a new screen adds its own `*.a11y.test.tsx`. The files run one at a time, since nine sharing one
  browser made upload cases time out (decision 097). Three of F01 to F03's major bugs were contrast defects QA
  found late (decision 068). A stage planned from now on lists it in `verification.commands`; adding
  the tool to the workspace is part of the first stage that needs it.
- The gates are lint, type check, unit tests, the accessibility check and a production build, as
  listed in the stage's `verification.commands`.
