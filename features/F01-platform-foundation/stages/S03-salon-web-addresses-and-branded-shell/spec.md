# F01-S03 Salon web addresses and branded shell

## Objective

CR-010 replaces the former public customer shell with the salon staff workspace entry. Resolve
each salon from Host before routing; active tenant `/` redirects to `/workspace`, and staff
authentication protects workspace data. Customer UI is only the later React Native app. Preserve
unknown/suspended/unavailable states, tenant isolation, no-store and API contracts. NFR-21 maps
web screens to Salon mode, not the customer 430px canvas.

- AC-12: Rendered comparison follows the warm palette, Poppins, rounded controls and responsive
  Salon workspace layout; neutral foundation errors remain accessible. (NFR-21)

## Read first

- `docs/engineering/web.md`: tenant routing, structure, where things live, branding, text and
  accessibility, tests
- `docs/architecture/multi-tenancy.md`: rules T-5 and T-8, "Salons and their hostnames" (the exact
  hostname rule, the reserved slugs), "How the tenant reaches the database"
- `docs/design/prototype/index.html`
- `docs/design/ui-ux-standards.md`: sources of truth, tokens and branding, states every screen has,
  the width matrix, accessibility, test ids, wording
- `docs/architecture/ARCHITECTURE.md`: web addresses
- `docs/engineering/backend.md`: API conventions (problem details and error codes), the contract
- `docs/architecture/tech-stack.md`: Next.js 16 (`proxy.ts`), the generated client, web styling
- `docs/operations/environments-and-releases.md`: the local environment, its scripts, restarting
  after a rebuild
- QA, at the feature pass: `docs/qa/test-plan.md` (the synthetic salons), `docs/qa/test-types.md`,
  `docs/qa/automation.md`

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| BRD-03 | Every salon receives a platform subdomain with a secure certificate at onboarding | In part: a salon's subdomain reaches that salon on the web. The certificate is F01-S05; giving a new salon its subdomain at onboarding is F03 |
| NFR-01 | Tenant isolation | In part: no page is ever rendered with another salon's brand, and the web app never takes the salon from the client |
| NFR-17 | No per-tenant code | In part: one shell, every difference between salons arrives as data |

## Scope

**Web (`apps/web`)**
- `proxy.ts` and the workspace surface resolve the salon from the request's `Host` header, by the
  exact hostname rule of `docs/architecture/multi-tenancy.md`, using the same platform domain
  setting as the API (`SALON_PLATFORM_DOMAIN`).
- Active tenant root redirects to fixed same-host `/workspace`; its staff sign-in and authenticated
  pages identify only the resolved salon. No customer public shell or customer navigation.
- A translated "salon not found" page and a translated "temporarily unavailable" page.
- The "service unavailable" state of F01-S01 when the API cannot be reached while a salon is being
  resolved.
- The bare platform domain keeps the status page of F01-S01, unchanged.

**API (`apps/api`)**
- A suspended salon: `GET /api/v1/salon` on its hostname answers 403 problem details with the new
  code `salon_suspended`. The same answer is given by every endpoint that needs a salon, through
  the tenant context, so that later endpoints inherit it.
- The contract and the generated client describe the new answer.

**Local environment (`infra/scripts`)**
- A hook for tests, for the local environment only, that suspends and reactivates one of the
  synthetic salons: `infra/scripts/local-salon.sh <suspend|activate> <slug>`. It changes the
  salon's status as the owning role and nothing else, refuses a slug that is not a salon, and is
  documented in `README.md` next to the other local scripts.

## Out of scope

- Services, the booking flow, navigation, logo and cover image on the shell: F04 and F05 onward.
- Staff workspace functionality and sign-in are provided by F02; this correction routes into them. The operator console on its reserved
  hostname: F03. Until then `console.<platform domain>` is a hostname that resolves to no salon.
- Suspending or reactivating a salon through an endpoint, and what staff of a suspended salon may
  still read (PLT-05, PLT-06): F03. This stage defines tenant routing and neutral status responses.
- Editing a brand, drafts and publishing: F04.
- Certificates, and trusting a forwarded hostname behind the edge: F01-S05.
- A domain the salon owns: a later release.
- The synthetic salons themselves do not change: three, all active after a reset.

## Acceptance criteria

Each criterion is one observable outcome that a test can pass or fail. "The web port" is the web
app's local port; hostnames are the synthetic salons' of `docs/qa/test-plan.md`.

- AC-1: `GET /` on alpha/beta/gamma hostnames answers a no-store same-host redirect to
  `/workspace`. Following it without staff credentials reaches staff sign-in with that salon's
  identity only. Matching is case insensitive. (BRD-03, NFR-01)
- AC-2: Staff entry uses the neutral responsive Salon workspace. Customer branding/canvas and
  customer web navigation are absent; the resolved salon name stays accessible. (NFR-17, NFR-21)
- AC-3: `GET /` on a hostname that resolves to no salon answers 404 with the "salon not found"
  page, with the same body for each of: an unknown slug, a reserved slug (`www`, `console`), two
  labels in front of the platform domain (`alpha.beta.localhost`) and a hostname that only contains
  the platform domain (`alpha.localhost.example.com`). The page shows no salon's name or colour and
  nothing that says whether any salon exists. (NFR-01)
- AC-4: `GET /` on the bare platform domain (`localhost`, the web port) still shows the status page
  of F01-S01 with the API's service name and version. (NFR-17)
- AC-5: With gamma suspended by `local-salon.sh suspend gamma`: `GET /api/v1/salon` on gamma's
  hostname (the API's port) answers 403 problem details with `code` equal to `salon_suspended` and
  none of the brand's fields; `GET /` on gamma's hostname (the web port) answers 503 with the
  "temporarily unavailable" page, which shows no salon's name or colour; alpha and beta answer as in
  AC-1. After `local-salon.sh activate gamma`, gamma answers as in AC-1 on the next request, with
  nothing restarted. (BRD-03)
- AC-6: The salon is never taken from the client. A request on alpha's hostname that also carries
  beta's slug or id in a query parameter, in a cookie, or in a header other than `Host` (including
  `X-Forwarded-Host` and `X-Tenant-Id`) shows alpha, on the web port and nothing of beta. (NFR-01)
- AC-7: Under load no page is rendered with another salon's brand: at least 300 requests for `/`,
  spread evenly over the three salons' hostnames and released together by at least 50 virtual
  users, each resolve to their own salon workspace with fixed redirects and zero cross-tenant
  identity mismatches or errors (follow redirects to verify rendered identity). (NFR-01)
- AC-8: With the API stopped, and with the API accepting connections and answering nothing, `GET /`
  on a salon's hostname answers within 5 seconds with status 503 and the plain "service unavailable"
  state: not the "salon not found" page, no salon's name or colour, no error overlay or stack trace.
  When the API answers again, the next request shows the salon, without the web app being
  restarted. (NFR-17)
- AC-9: At 360 and 1280 pixels wide, the shell with beta's long name, the "salon not found" page
  and the "temporarily unavailable" page fit without horizontal scrolling, and an automated
  accessibility scan of each finds no serious violation. (NFR-17)
- AC-10: Every string on the three pages comes from the translation file, and every element a test
  needs to read or press carries a `data-testid` named as `docs/design/ui-ux-standards.md` says.
  (NFR-17)
- AC-11: The OpenAPI document describes the 403 answer of `GET /api/v1/salon` with the code
  `salon_suspended`, the generated client exposes it, and `pnpm contracts:check` passes. (NFR-17)

## Design notes

- Follow `docs/engineering/web.md` and `docs/architecture/multi-tenancy.md` exactly. Where the
  installed framework forces a difference, report it in `handoff.notes`; the lead corrects the
  document.
- **The web app never connects to an address taken from the request.** It reaches the API at its
  one configured address (`SALON_API_BASE_URL`) and tells the API which salon by the hostname it
  forwards, never by a parameter or a custom header. A hostname that is not exactly one label in
  front of the platform domain is answered with the "salon not found" page without calling the API.
  Prove both with unit tests; how the hostname is forwarded is your choice, reported in the handoff.
- The web app reads the hostname from the `Host` header only. It does not use `X-Forwarded-Host` in
  this stage, whatever the framework does by default.
- **Nothing about a salon is cached across requests or shared between salons** in this stage: not
  the resolution, not the brand, not the rendered page. A status change (AC-5) is visible on the
  next request. If the framework caches by default, turn that off for these routes or key it by
  hostname, and say which in the handoff.
- The contrast colour is computed from the brand colour by one function in the formatting or
  styling module, with unit tests for a dark, a light and a mid colour. No component assumes white.
- Status codes: fixed same-host redirect for active tenant root, 200 for staff entry, 404 for "salon not found", 503 for "temporarily
  unavailable" and for "service unavailable". If the framework cannot give a page one of these
  codes, stop and ask the lead.
- API: the tenant context distinguishes "no such salon" (404 `not_found`, as now) from "suspended"
  (403 `salon_suspended`). `meta` and health still answer 200 on a suspended salon's hostname.
  `salon_suspended` joins the error codes in one place, with a test that the contract lists it.
- Developer tests that ship with the stage: the hostname rule on the web (the same table of cases
  the API's `PlatformHostnameTest` uses); the three page states as component tests; an API
  integration test with two salons proving that suspending one changes nothing for the other, and
  that a suspended salon's brand is never returned.

## API and data changes

| Method and path | Response |
| --- | --- |
| `GET /api/v1/salon` | As in F01-S02, and additionally 403 problem details `code: salon_suspended` when the hostname's salon is suspended, with no brand field in the body |

No table changes. No new migration is expected; if one is needed, it follows
`docs/engineering/backend.md` and is reported in the handoff.

## UI and UX

| Screen | Prototype source | States |
| --- | --- | --- |
| Salon staff entry (`/` redirects to `/workspace`) | Salon mode in `docs/design/prototype/index.html` | Neutral workspace loading/sign-in, authenticated entry, service unavailable |
| Salon not found | None; follow the application's existing not-found page and the standards' wording rules | One state |
| Temporarily unavailable | None; plain staff wording: the salon workspace is temporarily unavailable | One state |

The two error pages use the platform's neutral theme, never a salon's colour.

## Risks and test focus

| Risk | Test type and why |
| --- | --- |
| A routing or caching mistake renders one salon's brand on another salon's address | `tenant-isolation` and `concurrency`: three salons, interleaved and simultaneous requests (AC-1, AC-7) |
| The web app trusts something the client sent | `security`: query, cookie, forwarded headers, hostile `Host` values (AC-3, AC-6) |
| A suspended or unknown salon leaks its brand, or an API outage is shown as "not found" | `functional` and `resilience` (AC-5, AC-8) |
| The shell breaks on a long name, a dark or a light brand | `branding`, `responsive`, `accessibility` (AC-2, AC-9) |
| The contract drifts | `api` and `build` (AC-11) |

`permissions`, `payment-integrity`, `mobile` and `load` (no response-time target) do not apply.

## Verification commands

The developer gates. Each line is run in its own shell from the repository root. They are also in
`stage.yaml` `verification.commands`.

```bash
cd apps/api && ./mvnw -q verify
pnpm install --frozen-lockfile
pnpm -r lint
pnpm -r typecheck
pnpm -r test
pnpm --filter @salon/web build
pnpm contracts:check
qa/scripts/sdlc selftest
```

## Done criteria

- The developer has checked every acceptance criterion on the running product and recorded it in
  the handoff, with the test ids of the three pages.
- The verification commands are green locally.
- Before closing, the lead has corrected `docs/architecture/multi-tenancy.md`,
  `docs/engineering/web.md`, `docs/engineering/backend.md` and
  `docs/operations/environments-and-releases.md` for what this stage settled (what a suspended
  salon answers, the new error code, the local hook, and anything in `handoff.notes`).
