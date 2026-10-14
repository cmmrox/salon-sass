# Web rules (`apps/web`)

One Next.js application serves three surfaces. The hostname decides which.

| Surface | Reached at | Users |
| --- | --- | --- |
| Booking site | The salon's subdomain, later its own domain | Customers, without installing anything |
| Workspace | The same hostname, under `/workspace`, behind sign-in | Salon staff |
| Operator console | A reserved platform hostname that never resolves to a tenant | Operator staff |

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
- An active salon's `/` is rewritten to the page `/salon-site`, which answers 200. The proxy hands
  it the salon it resolved in the request header `x-salon-resolved`, which it first removes from
  every incoming request; the page does not call the API again. `/salon-site` asked for from
  outside answers the application's 404.
- Nothing about a salon is cached: every response on a salon's hostname carries `Cache-Control:
  private, no-cache, no-store, max-age=0, must-revalidate`, and the shell is rendered per request.
- Never read a tenant from a query parameter, a cookie the browser can set, or local storage.

## Structure

- Route groups per surface: `(booking)`, `(workspace)`, `(console)`. A component used by more than
  one surface lives in a shared folder; a surface never imports from another surface.
- Pages and layouts compose; they do not hold business rules. Data access goes through the
  generated client in `packages/api-contracts`, wrapped in one small module per API area.
- Server components fetch for first render, so the public booking pages are fast and indexable.
  Client components are for interaction only.
- No money arithmetic, availability logic or policy logic in the browser. Show what the API returns.
- Forms validate for the user's convenience and the API validates for real. Show the API's field
  errors next to the fields.

## Where things live

| Thing | Place |
| --- | --- |
| Pages and layouts | `apps/web/src/app/`, in the route groups `(booking)`, `(workspace)`, `(console)` |
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
- The workspace and the console use the platform's own neutral theme, not the salon's.
- A brand change is a draft until the salon publishes it. The public site shows the published brand.

## Sessions

- Tokens live in secure, HTTP-only cookies set by the server. Nothing sensitive in local storage.
- A staff session and a customer session are different things with different cookies, even on the
  same hostname.

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
