# Web rules (`apps/web`)

One Next.js application serves three surfaces. The hostname decides which.

| Surface | Reached at | Users |
| --- | --- | --- |
| Booking site | The salon's subdomain, later its own domain | Customers, without installing anything |
| Workspace | The same hostname, under `/workspace`, behind sign-in | Salon staff |
| Operator console | A reserved platform hostname that never resolves to a tenant | Operator staff |

## Tenant routing

- `proxy.ts` reads the hostname, resolves the tenant through the API, and passes it to the pages.
  This is routing only. It is not a security boundary: the API and the database enforce access.
- An unknown hostname shows a "salon not found" page; a suspended salon shows a "temporarily
  unavailable" page. Neither reveals whether a salon exists elsewhere.
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
