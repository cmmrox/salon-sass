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
  every request. This is routing only. It is not a security boundary: the API and the database
  enforce access.
- A hostname that is not exactly one slug-shaped label in front of the platform domain (a bare
  network address included) is "salon not found" without calling the API. Reserved slugs are left
  to the API, which answers 404.
- The API is reached at its one configured address, `SALON_API_BASE_URL`, never at an address taken
  from the request. The salon is named to it by the `Host` header of that request,
  `<slug>.<platform domain>`, sent through a fetch built on `node:http`
  (`src/lib/api/forward-hostname.ts`), because Node's `fetch` drops a `Host` header.
- **Status codes (decision 028).** The proxy answers "salon not found" (404), "temporarily
  unavailable" (503, a suspended salon, on every path of its hostname) and "service unavailable"
  (503, the API cannot be reached within three seconds, or `SALON_PLATFORM_DOMAIN` is unset) itself,
  as complete HTML from `src/lib/plain-page/`, with no script. Next.js 16 resets a status the proxy
  sets for a page reached by rewrite, and has no 503 for a page. Neither page reveals whether a
  salon exists elsewhere, and both use the neutral theme.
- An active salon's `/` redirects to the fixed same-host `/workspace`; unauthenticated entry
  reaches `/workspace/sign-in`. `/salon-site` is not publicly reachable and returns 404.
- Nothing about a salon is cached: every response on a salon's hostname carries `Cache-Control:
  private, no-cache, no-store, max-age=0, must-revalidate`, and the shell is rendered per request.
- Never read a tenant from a query parameter, a cookie the browser can set, or local storage.

## Structure

- Route groups per surface: `(workspace)`, `(console)`; legacy route aliases contain no customer UI. A component used by more than
  one surface lives in a shared folder; a surface never imports from another surface.
- Pages and layouts compose; they do not hold business rules. Data access goes through the
  generated client in `packages/api-contracts`, wrapped in one small module per API area.
- Server components fetch for first render in the staff workspace.
  Client components are for interaction only.
- No money arithmetic, availability logic or policy logic in the browser. Show what the API returns.
- Forms validate for the user's convenience and the API validates for real. Show the API's field
  errors next to the fields.

## Where things live

| Thing | Place |
| --- | --- |
| Pages and layouts | `apps/web/src/app/`, in the route groups `(workspace)`, `(console)`; legacy route aliases contain no customer UI |
| Strings | `apps/web/messages/<locale>.json`, read through `next-intl`. There is no locale in the URL |
| Tokens | `apps/web/src/styles/tokens.css`, as CSS custom properties; components style with CSS Modules |
| Base styles shared by pages and plain pages | `apps/web/src/styles/base.css` |
| The proxy's plain HTML pages | `apps/web/src/lib/plain-page/`. They inline `tokens.css` and `base.css` from their one source; their layout is repeated in `plain-page.css`, in tokens only (a test forbids any colour or size of its own), because Turbopack cannot give a CSS module's source as text. A layout change to the AppShell, Notice or Status modules is made there too |
| Formatting of money, dates and phone numbers | `apps/web/src/lib/format/` |
| Calls to the API | `apps/web/src/lib/api/`, wrapping the generated client |

- A string literal in JSX fails lint. Add the string to the messages file.
- The application's own not-found page exists so that even a 404 is translated. Add an error
  boundary page the same way when a route can fail while rendering.
- Rendering follows the framework's current defaults (cache components and partial prefetching).
  Data fetched while rendering sits inside a suspense boundary or is marked cacheable; the page is
  served with its loading skeleton and the result is streamed in.
- The web app waits three seconds for the API, in the proxy and in a page, and then shows the
  unavailable state. A page never hangs on the API.
  Account BFF calls share that original three-second API cancellation budget across proxy,
  metadata and mutation work. The response-finish log also includes framework/error serialization
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

## Branding

- A salon's brand (name, logo, cover, colour) arrives from the API and is applied through CSS
  custom properties on the root. Components use the tokens; none hard-codes a brand colour.
- The text colour on the brand colour is computed for contrast. Never assume white.
- Every salon screen, sign-in and workspace alike, uses the salon's colour as its accent
  (CR-013), with the prototype's `#D6086A` when the salon has none. Only the operator console uses
  the platform's neutral theme, because it belongs to no salon.
- Entry decides on the server, in the proxy (`src/lib/tenancy/route-request.ts`,
  `src/lib/identity/entry.ts`), with real HTTP 307s: a page's own `redirect()` streams as a client
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
- A brand change is a draft until the salon publishes it. The future React Native app consumes the published brand; no customer website is created.

Staff sign-in uses the dedicated CR-012 layout without the protected workspace rail/header. It
shows the hostname-resolved salon name/initial and a shared decorative interior fallback.

## Sessions

- Tokens live in secure, HTTP-only cookies set by the server. Nothing sensitive in local storage.
- Web issues staff session cookies only. Customer tokens belong to the future native client;
  direct API principal kinds remain isolated.

## Text, formats and accessibility

- Every string comes from the translation files. No text literal in a component.
- Currency, dates, times and phone numbers are formatted with the salon's locale, currency and
  time zone, through one formatting module.
- Follow `docs/design/ui-ux-standards.md`: semantic elements, labels, focus order, visible focus, keyboard
  access to everything, contrast on the brand colour.

## Tests

- Unit and component tests with Vitest and Testing Library for logic and for components with
  states or branches.
- Give every interactive element a `data-testid`, named as `docs/design/ui-ux-standards.md` says.
  QA's browser tests depend on them, and QA cannot add them.
- The gates are lint, type check, unit tests and a production build, as listed in the stage's
  `verification.commands`.
