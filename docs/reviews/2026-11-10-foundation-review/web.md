# Web foundation review: `apps/web`, `packages/api-contracts`, `packages/format`

Reviewed 2026-11-10 on `main` at `e5ccf3aa` (F01 to F07 merged). Read-only: nothing in the repository was
changed, no server, container, build or QA suite was started. Two static analysers were run because they only
read files: `knip` and `prettier --check`. Everything else is from reading code, docs and `git`.

All paths are relative to `the repository root`.

Confidence labels: **verified** means verified by reading the code that executes; where the finding is a runtime
behaviour it was not reproduced, because no server was started. **suspected** means inferred, not traced end to end.

---

## 1. Verdict

The tenant-isolation, session, CSRF and styling-conformance foundations are sound and unusually careful. The
weak part is the layer between the browser and the BFF: there is no shared client data layer, session renewal
and failure handling are re-implemented per screen (and are wrong on three screens today), every BFF call costs
three upstream API calls, and the whole workspace BFF is one dispatcher inside the `identity` feature. That
layer is exactly what the calendar, checkout and front desk will lean on hardest. It should be fixed before
F08/F10, not during them. Two present-day defects (H1, H2) should be fixed regardless.

| Area | Verdict |
| --- | --- |
| Tenant routing, cache isolation, console separation | Sound |
| Sessions, CSRF, route-handler authorisation, XSS, secrets | Sound; security headers/CSP missing |
| shadcn/Tailwind rule conformance | Sound and enforced by tests |
| Contract package (generation, CI check, per-module clients) | Sound |
| Browser data access, renewal, error handling | Not sound: duplicated per screen, defective on Team/Services/Products |
| BFF structure and cost per call | Works, will not scale to F08 to F13 as built |
| i18n beyond English | Not ready (locale is a constant in three places) |
| Calendar / checkout / print / patchy network foundations | Absent; five decisions needed before F10 |
| Gates | CI omits the accessibility check, knip and the format check |

---

## 2. Findings, ranked

### HIGH

#### H1. Team, Services and Products send a signed-in person to the sign-in form once the 5-minute access cookie lapses
Severity high. Confidence: verified by reading; not reproduced at runtime.

Evidence:
- The access cookie lives 300 seconds: `apps/web/src/features/identity/server/identity-web.ts:307`.
- A BFF read without a usable access credential answers `401 invalid_session` with `renewable: true`:
  `identity-web.ts:426-456`.
- `identityRequest` does not renew: `apps/web/src/features/identity/client/session.ts:16-40`.
- These reads call `identityRequest` directly, and their failure handler maps `invalid_session` straight to
  `leaveEndedSession` (which goes to `/workspace/sign-in`, `session.ts:88-95`):
  - Team: `apps/web/src/surfaces/workspace/use-team.ts:93-101` (reads), `:73-78` (handler)
  - Services: `apps/web/src/surfaces/workspace/use-catalogue.ts:122-137`, `:100-105`
  - Products: `apps/web/src/surfaces/workspace/use-products.ts:92-94`, `:68-72`
  - Schedule sheet: `apps/web/src/surfaces/workspace/use-schedule.ts:121-127`, `:104-108`
  - Leave impact: `apps/web/src/surfaces/workspace/use-time-off.ts:82-88`
- Screens that do renew, for contrast: `booking-requests.ts:16-24`, `use-settings-draft.ts:82`,
  `use-hours-settings.ts:111`, `use-booking-rules.ts:84`, `use-tax-settings.ts:124`, `BillingScreen.tsx:55`.
- The same class of bug was found and fixed for Bookings only in F07-S03 (commit `7d2b51c0`, recorded at
  `features/F07-online-booking/stages/S03-staff-booking-screens/stage.yaml:165`; the fix is
  `apps/web/src/surfaces/workspace/BookingsScreen.tsx:71-82`).
- Second half of the same defect: when the server renders with an expired access cookie the principal is null
  (`apps/web/src/surfaces/workspace/AccountPage.tsx:87`), so the navigation shows only "Account"
  (`WorkspaceShell.tsx:27,42-111`) and Team/Services render with `canManage`/`canChange` false
  (`AccountPage.tsx:133-141,157-163`). Only Account and Bookings call `router.refresh()` after renewing.
- No test covers it: `TeamScreen.test.tsx`, `ServicesScreen.test.tsx` and `ProductsScreen.test.tsx` contain no
  `invalid_session` case.

Failure scenario: a receptionist works on Bookings for six minutes without triggering a BFF call, then clicks
Team or Services (or reloads either page, or opens it in a new tab). The server renders without a principal,
the first read answers 401 renewable, and the screen navigates to the sign-in form although the refresh session
is valid. The sign-in page does not forward a still-valid session (`SignIn.tsx:38-40` only checks for Web
Locks), so the person retypes password and authenticator code. It cannot be seen in a normal QA run because it
needs five idle minutes of real browser time.

Smallest fix: put the "renew once under the lock and retry" of `booking-requests.ts:16-24` inside the shared
read path (one `read()` used by every hook) and move the "rendered without a principal: renew then
`router.refresh()`" effect from `BookingsScreen.tsx:73-82` into the workspace frame so every view gets it. Add
one test per screen with a 401-renewable first answer.

#### H2. The 3-second API budget starts when request headers arrive, so uploads over a slow link fail and any slow endpoint reads as an outage
Severity high. Confidence: verified by reading; not reproduced at runtime.

Evidence:
- The deadline is set at `http.server.request.start`: `apps/web/src/lib/observability/request-log.ts:39`.
- Every upstream call is cut off at that deadline: `apps/web/src/lib/api-core/client.ts:54-59` and `:69-79`.
- For an upload the BFF creates its signal at handler start (`identity-web.ts:409`), runs `/identity/me`
  (`:426`), then reads up to 10 MB of body (`:667`, `:674`, `:681`, `uploadForm` at `:766-787`), then calls the
  API with that same signal (`:669`, `:676`, `:683`; `apps/web/src/features/staff/server/salon-team.ts:164-173`).
- The limit is 10 MB: `apps/web/src/lib/http/upload-limits.ts:5`.
- The edge streams request bodies (no request buffering in `infra/stack/edge/Caddyfile.staging:39-63`).
- The export archive is fetched and buffered whole inside the same budget:
  `apps/web/src/features/platform/server/platform-web.ts:74`; every per-salon response is buffered in memory by
  `apps/web/src/lib/api-core/forward-hostname.ts:50-57`.

Failure scenario: an owner uploads a 3 MB phone photo for a team member over a 1.5 Mbps uplink. The body takes
about 16 seconds to arrive; the deadline passed at second 3; the upstream call is aborted before it is sent and
the BFF answers `503 service_unavailable`. It always works on localhost. The same budget means that an API
endpoint that legitimately needs 2.5 seconds (a week of calendar, a report in F13, an import in F14) fails as
soon as the salon lookup and `/me` in front of it take the other half second, and that a write the BFF gave up
on at 3 seconds may still commit in the API (see M1).

Smallest fix: for the three multipart actions, read the body first and then restart the budget
(`context.deadline = performance.now() + 3000`) before `/me` and the upload; give long reads (export, future
reports) an explicit longer budget per action instead of the global one.

#### H3. No client data layer, and each BFF call costs three upstream API calls (five per change)
Severity high for F10 (structural). Confidence: verified.

Evidence:
- The proxy runs for every path except six static prefixes, so it runs for `/identity-web/*`:
  `apps/web/src/proxy.ts:16`. It resolves the salon through the API each time:
  `apps/web/src/app/_proxy/route-request.ts:187`, `apps/web/src/app/_proxy/site.ts:52`,
  `apps/web/src/features/branding/server/salon.ts:45`.
- The BFF then calls `/identity/me` before every non-public action: `identity-web.ts:426`; then the real call.
- A change first asks for the principal under the lock, then posts:
  `apps/web/src/surfaces/workspace/booking-requests.ts:27-33`, and the same shape in `use-catalogue.ts:188-194`,
  `use-team.ts:142-147`, `use-products.ts:133-138`, `use-schedule.ts:149-154`. That is 2 BFF requests and
  5 upstream calls per change, serialised across tabs by one Web Lock (`session.ts:41-52`).
- Counted for one view of `/workspace/bookings`: document (salon lookup, `/me`, entitlements) + logo (2) +
  clock (3) + list (3) + team (3) = about 14 upstream calls. Services issues five reads on load
  (`use-catalogue.ts:124-137`) = 15 upstream calls for data alone.
- All screen data is fetched in `useEffect` by hand-written hooks: 3,450 lines of `use-*.ts` in
  `apps/web/src/surfaces/workspace/`; eight near-identical `failed()` functions
  (`use-team.ts:73`, `use-catalogue.ts:100`, `use-products.ts:68`, `use-schedule.ts:104`,
  `use-hours-settings.ts:88`, `use-booking-rules.ts:57`, `use-tax-settings.ts:93`, `use-settings-draft.ts:61`);
  13 `react-hooks/exhaustive-deps` suppressions on load effects; no cache, no de-duplication, no refetch on
  focus, no polling, no optimistic update (`useOptimistic`, `useTransition`: none in the workspace).
- This contradicts `docs/engineering/web.md:100-101` ("Server components fetch for first render in the staff
  workspace. Client components are for interaction only"). The server fetches only the principal and
  entitlements (`AccountPage.tsx:75-88`); 135 of 301 source files are `'use client'`.

Cost: a calendar that polls every 5 seconds from 3 tablets is 108 upstream calls a minute per salon for one
list, two thirds of them redundant. Drag-to-move at 5 calls per drop will feel slow on a salon connection.
H1 is a direct result of each hook re-implementing renewal.

Smallest fix, in order:
1. One request module (`read`, `change`) with renewal, retry and one typed failure mapping; delete the eight
   `failed()` copies.
2. Drop the `/me` pre-flight for ordinary reads and writes: the API authenticates the bearer token on the real
   call and returns 401 itself. Keep `/me` only for `principal` and `refresh`.
3. Skip salon resolution in the proxy for `/identity-web/*` (the handler validates the host itself at
   `identity-web.ts:363-364`, and the API resolves and refuses by `Host`). Result: 1 upstream call per read.
4. Adopt one cache/query library for the browser before the calendar is written (decision D1 below).

#### H4. The workspace BFF is one dispatcher inside the `identity` feature; every new module edits identity
Severity high (structural cost per feature). Confidence: verified.

Evidence:
- One route serves every workspace call: `apps/web/src/app/identity-web/[kind]/[action]/route.ts:9-17`, wiring
  seven ports positionally.
- `createIdentityWeb` takes 7 positional parameters (`identity-web.ts:319-338`); `handleSupport` takes 10
  (`:182-193`); the file is 794 lines with a 15-branch nested ternary for reads (`:496-524`, repeated at
  `:269-289`).
- The action names, read/write lists and the permission each action needs for booking, catalogue, staff and
  rules live in identity: `apps/web/src/features/identity/server/salon-bookings.ts:42-60`, and
  `salon-catalogue.ts`, `salon-team.ts`, `salon-hours.ts`, `salon-booking-rules.ts` beside it.
- Types are erased at the port (`data?: unknown`, `body: Record<string, unknown>`:
  `features/identity/server/salon-bookings.ts:4-27`) and rebuilt with casts
  (`apps/web/src/features/booking/server/salon-bookings.ts:15,29-40`).
- The browser to BFF hop is stringly typed: `identityRequest<T>(kind, action, body?: unknown)` returns
  `data as T` (`session.ts:16,39`), so `bookingRead<TeamMemberList>(kind, 'catalogue-services')` compiles.
- Because a feature may not import another feature, and the BFF transport lives in `features/identity/client`,
  no feature can own its own data hooks: all of them sit in `apps/web/src/surfaces/workspace/` (43 non-test
  files, 7,408 lines). `web.md:60` describes a per-module `client/` for BFF requests; only identity and platform
  have one.

Cost: F08 (payment), F09, F10, F11 (notification), F12 (customer), F13 (reporting) each add a port file under
identity, another positional argument in three places, more branches in two ternaries and more hooks in the
surface. The lint boundary is satisfied on paper while identity knows every module's actions and permissions.

Smallest fix: move the transport (request, lock, failure class) to `src/lib/bff/`; replace the dispatcher with a
registry `{ action: { method, permission?, handle(call, input) } }` that each module's `server.ts` exports and
the route merges; derive the browser's action and response types from that registry. Do it as one refactor
before F08 adds its actions.

### MEDIUM

#### M1. A lost answer to "hold this time" throws the idempotency key away
Severity medium. Confidence: verified.
Evidence: `apps/web/src/surfaces/workspace/use-new-appointment.ts:303-329`: the key is created per chosen time
(`:305-306`) but the catch block clears it for every failure (`:323`), including `service_unavailable`, which is
what a dropped connection or the BFF's 3-second cut-off produce (`session.ts:27-29`, `identity-web.ts:721-722`).
Scenario: patchy Wi-Fi at the desk. The hold is placed, the answer is lost. The receptionist taps the same time
again; a new key is sent; the API refuses with `slot_taken` because of the orphan hold; the screen says the time
was just taken and it disappears from the list until the hold expires, with the customer on the phone.
Fix: keep `attempt.current` when the outcome is unknown (`service_unavailable`, `browser_unsupported`); clear it
only on a definite refusal.

#### M2. A failed re-read replaces the day's bookings with "unavailable"; the list never refreshes for other people's changes
Severity medium. Confidence: verified.
Evidence: `apps/web/src/surfaces/workspace/BookingsScreen.tsx:126,169-187` shows the alert in place of the list
whenever `list.problem === 'unavailable'`, although the last page is still in state
(`use-bookings.ts:43,93-102`). The list is re-read only on the person's own action or a hold expiring
(`use-bookings.ts:108` dependencies; `:131-142`); there is no interval, focus or visibility refetch anywhere
(`grep visibilitychange|setInterval`: only the clock tick, `use-api-clock.ts:38`).
Scenario: one failed background read on a weak connection blanks the list (against
`docs/design/ui-ux-standards.md`, "Slow or offline: says so, keeps what is already on screen, and retries
safely"). Separately, receptionist B's new booking does not appear on A's screen until A acts or reloads.
Fix: keep rendering the last page with a banner and retry with backoff; refetch on window focus and on an
interval. Both come free with H3 step 4.

#### M3. No error boundary anywhere
Severity medium. Confidence: verified.
Evidence: `apps/web/src/app/` has no `error.tsx` or `global-error.tsx` (only `not-found.tsx`), although
`web.md:138-139` asks for one. Formatters throw on unexpected input: `packages/format/src/index.ts:21-24`
(`formatClock`) and `:42-49` (`formatDay`) pass an invalid `Date` to `Intl.DateTimeFormat#format`, which throws
`RangeError`; `apps/web/src/styles/brand.ts:44-47,127-130` throw on a non-hex colour.
Scenario: one booking with a null or malformed time from the API takes down the whole Bookings page with the
framework's untranslated crash screen and no retry, on a front-desk tablet.
Fix: add `app/(workspace)/error.tsx`, `app/(console)/error.tsx` and `app/global-error.tsx` (translated, with
"Try again"); make the two formatters return the raw value on an invalid date.

#### M4. CI does not run the accessibility check, knip or the format check; knip and Prettier fail on `main` today
Severity medium. Confidence: verified.
Evidence: `.github/workflows/ci.yml:52-71` runs lint, typecheck, `pnpm -r test` (the `unit` project only,
`apps/web/package.json:12`), build and `contracts:check`. It does not run `pnpm --filter @salon/web test:a11y`
(which stage gates list, e.g. `features/F07-online-booking/stages/S03-staff-booking-screens/stage.yaml:280`),
`pnpm knip` or `pnpm format:check`; no workflow or `qa/scripts` file mentions them. The CI header says its steps
are the stage's verification commands "in the same order and with the same text" (`ci.yml:4-5`), which is no
longer true. `web.md:113-117` says knip fails the build and `prettier --check` is a gate.
Current state (run read-only): knip reports 10 unused exports and 6 unused exported types, all from F06/F07
(for example `SHEET_ACTIONS` at `features/booking/client/appointment-rules.ts:5`, `BOOKING_CLOCK` at
`features/identity/server/salon-bookings.ts:36`, `teamPhotoSource` at `features/staff/components/TeamCards.tsx:15`);
Prettier flags `features/identity/components/StaffTeamProfile.test.tsx` and
`features/staff/components/ServicePicker.tsx`.
Cost: the accessibility gate exists only on the building agent's machine; dead code is already accumulating.
Fix: add the three commands to `ci.yml`.

#### M5. A workspace page is declared in about eight places, and they have already drifted
Severity medium (cost per page; low present impact). Confidence: verified.
Evidence: adding a page touches the page file, `PROTECTED` (`route-request.ts:52-68`), the `View` union twice
(`AccountPage.tsx:23-24`, `WorkspaceShell.tsx:17-18`), `REQUIRED` (`AccountPage.tsx:27-40`), the screen ternary
(`AccountPage.tsx:121-202`), the title ternary (`AccountFrame.tsx:51-69`) and the navigation chain
(`WorkspaceShell.tsx:42-111`). `/workspace/bookings` and `/workspace/team` are missing from `PROTECTED`, and
`route-request.test.ts` has no case for either. Effect today: for those two paths the proxy does not issue the
307 or clear a revoked family's cookies (`route-request.ts:227-238`); the page's own check still redirects
(`AccountPage.tsx:86`), so access is not exposed. For the listed pages `/identity/me` is called twice per load
(proxy `:231`, page `AccountPage.tsx:75`).
Fix: one route table (`path, view, permission, title key, nav item`) that the proxy, page, frame and shell read.

#### M6. Static assets and public photos go through the salon lookup and are never cached
Severity medium (speed and API load). Confidence: verified.
Evidence: the proxy matcher excludes only `_next/static`, `_next/image`, `favicon.ico`, `icon.svg`,
`fonts/poppins/` and `healthz` (`proxy.ts:16`). `/images/*`, `/icons/*` and `/brand/*` therefore each cost one
API call (`route-request.ts:187`) and are stamped `no-store` (`route-request.ts:245-246`). The sign-in page uses
four of them (`apps/web/src/styles/theme.css:177-180`; the interior photo is 79.5 KB). Public service and team
photos are served `no-store` (`app/service-photos/[id]/route.ts:6-7`, `app/team-photos/[id]/route.ts:6-7`), each
costing a salon lookup plus the image call and a full in-memory copy (`forward-hostname.ts:50-57,70`).
Scenario: a Services page with 40 photos is 80 upstream calls and 40 full downloads on every visit.
Fix: add `images/|icons/|brand/` to the matcher; for images the API "serves to anyone" by immutable id, send
`Cache-Control: private, max-age=<hours>` (browser cache only) or pass `ETag`/304 through.

#### M7. No security headers beyond HSTS at the staging edge
Severity medium. Confidence: verified for the web app and `Caddyfile.staging`; production edge not seen.
Evidence: `apps/web/next.config.ts` has no `headers()`; the proxy sets only `Cache-Control`; the only
`X-Content-Type-Options` is on the three image routes; `infra/stack/edge/Caddyfile.staging:67` sets HSTS and
nothing else. No `Content-Security-Policy`, `frame-ancestors`/`X-Frame-Options`, `Referrer-Policy` or
`Permissions-Policy` anywhere in `apps/web`, `infra` or the docs.
Risk: framing is partly mitigated by `SameSite=Lax` cookies, and no script-injection sink was found, so this is
defence in depth; but a payment-taking workspace (F08, F10) should not ship without `frame-ancestors 'none'`
and a CSP. A nonce-based CSP has to account for the inline brand `<style>`
(`components/shared/BrandStyle.tsx:12-15`), `input-otp` and Sonner's runtime styles (`web.md:259-261`), which
is easier to design now than after more inline output exists.
Fix: set the static headers in `next.config.ts` now; add a nonce CSP in the proxy as its own refactor.

#### M8. Not ready for Sinhala and Tamil: the locale is a constant in three independent places
Severity medium for the roadmap, none for English. Confidence: verified.
Evidence:
- Request locale is fixed: `apps/web/src/i18n/request.ts:9-15`; no decision exists on where a locale comes from
  (person, salon, cookie); `web.md:126` only says it is not in the URL.
- Every formatter defaults to `'en-LK'` (`packages/format/src/index.ts:4,8,12,21,42,76,87,106,133,182`) and
  about 126 call sites pass none; the few that do pass a literal (`features/platform/components/BillingScreens.tsx:43`,
  `features/catalogue/components/TaxPreview.tsx:62`). `web.md:374` says formatting uses "the salon's locale".
- English outside the message files: `apps/web/src/lib/dates/calendar-labels.ts:7,13` (`'Today'`, `'selected'`,
  `'en-LK'`); the lint rule only sees JSX literals and spoken attributes (`eslint.config.mjs:9-22,115-116`).
- The date picker is not localised: `components/ui/calendar.tsx:49` uses the browser's default locale for one
  label and react-day-picker's default (English) for captions and weekdays; no `locale` prop is passed anywhere.
- Money: `'Rs.'` literal and a two-decimal assumption for every currency
  (`packages/format/src/index.ts:138-143,177-179`); `formatPhone` hard-codes `+94` (`:102-104`).
- The plain proxy pages import `messages/en.json` directly (`lib/plain-page/plain-page.ts:1-2`).
- The whole catalogue (1,692 messages, 13 namespaces, console included) is handed to every page:
  `app/layout.tsx:18` (`NextIntlClientProvider` without a subset). It will roughly double by F14.
- Poppins has no Sinhala or Tamil glyphs; the stack falls back to device fonts (`styles/theme.css:83`), which is
  untested on the launch market's Android tablets.
Fix: decide the locale source; pass the locale through one provider/hook that wraps `@salon/format` instead of
defaults; move the two calendar strings into messages; pass `locale` to `Calendar`.

#### M9. `docs/engineering/web.md` describes things the code does not do
Severity medium (the next stages are built by agents that follow this document). Confidence: verified.
- `:100-101` server components fetch for first render: they do not (H3).
- `:62` `schemas/` with zod: no such folder, zod is not a dependency (`apps/web/package.json`).
- `:85-87` navigation reads entitled modules from the API: it reads permissions only
  (`WorkspaceShell.tsx:53-111`); the contract's `Features` has only `customDomain` and `dedicatedApp`
  (`packages/api-contracts/src/generated/schema.ts:3029-3032`).
- `:111-112` components over about 200 lines are split: 44 non-test, non-`ui/` files exceed 200 lines
  (`features/platform/components/BillingScreens.tsx` 948, `features/identity/server/identity-web.ts` 794,
  `features/identity/components/AccountScreen.tsx` 759, `surfaces/console/LifecycleScreens.tsx` 706,
  `surfaces/console/SalonScreens.tsx` 641, `features/platform/components/HolidayScreens.tsx` 639,
  `surfaces/workspace/use-catalogue.ts` 515).
- `:113-117` knip and Prettier are gates: not wired (M4).
- `:138-139` error boundary page: none (M3).
- `:377-380` "now" comes from the API's clock: only Bookings does (L1).
Fix: correct the document or the code in the same refactor as H3/H4, so F08 is planned from a true description.

### LOW

- **L1. "Today" in three staff date pickers is the browser's, and three screens take "today" from the device
  clock.** `features/staff/components/DateChooser.tsx:64-77`, `DateChangesTab.tsx:192-199` and
  `AddClosureSheet.tsx:123-130` pass no `today` to `Calendar` (the booking pickers do:
  `features/booking/components/NewAppointmentSheet.tsx:201`, `BookingsTools.tsx:89`). `todayInZone(timeZone)`
  with the device clock: `surfaces/workspace/TeamScreen.tsx:104`, `use-hours-settings.ts:48`,
  `features/platform/components/BillingScreens.tsx:805`. Scenario: an owner abroad sees the highlighted and
  screen-reader-announced "Today" on a different day from the salon's first selectable day. The salon time zone
  itself is applied correctly everywhere. Verified.
- **L2. Error codes lose their type after the BFF.** The contract enumerates them
  (`schema.ts:3707`), but `IdentityFailure.code` is `string` (`session.ts:5`), `problem(code: string)`
  (`identity-web.ts:119`) and the mapping tables are `Record<string, …>` (`use-new-appointment.ts:35-46`). A
  renamed code compiles and silently falls to the generic message. Verified.
- **L3. Validation limits are copied by hand from the API** (`features/booking/client/appointment-form.ts:19-20`,
  `bookings-query.ts:16-18`, `features/staff/client/time-off-form.ts:9`). There is no form or schema library;
  each form is a hand-written `*-form.ts` with good unit tests. Adequate so far; a multi-tender checkout with
  line items will strain it (decision D4). Verified.
- **L4. Cookies have no `__Host-` prefix** (`identity-web.ts:122-130`, `operator-web.ts` `setCookie`). All salons
  share one registrable domain, so a script on any subdomain could set a parent-domain cookie; no such script
  sink was found. Cheap hardening. Verified.
- **L5. Three uncompressed TTF fonts, 476 KB** (`styles/theme.css:15-17`, `public/fonts/poppins/`). WOFF2 would
  be about a quarter. Verified.
- **L6. `page` query is forwarded as `NaN` when malformed** (`identity-web.ts:267,420`); the API refuses it.
  Cosmetic. Verified.
- **L7. Date helpers duplicated per feature** (`features/booking/client/booking-time.ts:39-44`,
  `features/staff/client/hours-form.ts:119-124`, `features/platform/components/HolidayScreens.tsx:194-196,230`),
  a side effect of the no-cross-feature rule with no `lib/dates` home for them. Verified.
- **L8. No dependency update automation and no `pnpm audit` step** (`.github/` holds only `ci.yml` and
  `delivery.yml`; image scanning is in `delivery.yml:71-75` only). Verified.
- **L9. No pending indicator on workspace navigation.** Pages block on the server (`app/(workspace)/layout.tsx:11`)
  and there is no `loading.tsx`, `useLinkStatus` or transition, so a click on a slow link shows nothing until the
  server answers. Verified absence; perceived effect suspected.

---

## 3. Rule conformance (shadcn/ui and Tailwind only)

Result: no violation of the hard rules found in product code.

- Raw `button`, `input`, `select`, `textarea`, `table`, `dialog`: none. Native Select: none.
- CSS Modules or extra style sheets: none (only `app/globals.css`, `styles/theme.css` and the generated
  `lib/plain-page/plain-page.generated.css`).
- `style` props: five, all run-time values the rule allows:
  `features/branding/components/BrandPreview.tsx:30,35`, `ColourField.tsx:33,44`, `ImageField.tsx:59`.
- `<style>`: one, `components/shared/BrandStyle.tsx:12`, as documented.
- Raw `<img>`: two, each with a lint suppression (`features/catalogue/components/ServiceCard.tsx:57`,
  `PhotoField.tsx:58`); no shadcn component covers a photo.
- Raw `<a>`: three inside `Button asChild` (conformant), two plain links.
- Raw sizes in arbitrary values, against rule 5's "prototype-specific size comes from the theme":
  `features/staff/components/LeaveTab.tsx:161` (`min-[420px]`),
  `features/catalogue/components/VariantsSection.tsx:108,118` (`5.25rem_3.75rem_2.75rem`). Nits.

Enforcement: `apps/web/src/styles/styling-rules.test.ts` (style sheets, imports, colour values) and
`apps/web/src/app/composition.test.tsx` (raw elements, Native Select, expected `data-slot`s) run inside
`pnpm -r test`, so CI enforces them. Limits: they are regular expressions over source text; `style=` is not
scanned; a hand-built look-alike made from `div`s cannot be detected; raw sizes in arbitrary values are not
scanned. The upcoming timeline needs per-block `left`/`width` as `style` (a run-time value): record that as an
allowed case before F10 so the rule and the calendar do not collide.

---

## 4. Readiness for what is next

| Need | Foundation today | Verdict |
| --- | --- | --- |
| Dense interactive calendar (day timeline by professional, week, list; `docs/design/prototype/index.html:1016-1047`) | None: no query cache, no drag library, no optimistic update, 5 upstream calls per change | Not ready |
| Updates from other receptionists | None: no polling, no focus refetch; the BFF buffers whole responses (`forward-hostname.ts:50-57`), so server-sent events cannot pass through as built; tokens are HttpOnly, so the browser cannot call the API directly | Not ready |
| Drag to move (BKG-12) at 768px on a tablet | The prototype uses HTML5 drag events (`index.html:1027,1032-1043`), which are unreliable on touch; WCAG 2.2 AA (2.5.7) also requires a non-drag way to move | Decision needed |
| Virtualisation | Not present; not needed at salon scale (tens of rows, one day) | Do not add |
| Checkout (PAY-06) | Money formatting exists (`packages/format/src/index.ts:130-152`); `web.md:102` forbids money arithmetic in the browser, so every cart change needs an API quote; no form approach for line items and tenders | Not ready |
| Printing receipts | No print styles; Tailwind `print:` variants are allowed by the rules; no decision between browser print and an API-rendered PDF | Decision needed |
| Patchy connection on a front-desk tablet | No online/offline handling; failures blank lists (M2); unknown outcomes lose the idempotency key (M1); 3-second budget (H2) | Not ready |
| Role-based navigation | Permission-based navigation and server-side "no access" exist (`WorkspaceShell.tsx:53-111`, `AccountPage.tsx:94-108`) | Adequate; make it a table (M5) |
| Module entitlements in navigation | Not in the contract yet | Later (F18) |

Decisions to take before F10 (most before F08), so nothing is rebuilt:

- **D1. Browser data layer.** One query/cache library plus one request module (H3, H1). Everything interactive
  depends on it.
- **D2. Freshness model.** Polling at a stated interval with focus refetch, or a streamed channel. If streamed,
  the BFF needs a non-buffering path. Polling is the smaller step and is affordable once a read is one upstream
  call.
- **D3. BFF shape.** Registry per module instead of the identity dispatcher (H4); drop the `/me` pre-flight and
  the proxy lookup for BFF calls (H3).
- **D4. Forms.** Keep hand-written form modules or adopt a form and schema library before checkout; either way
  correct `web.md:62`.
- **D5. Drag and drop.** Library with pointer and keyboard sensors, plus the non-drag "Move" action; record the
  `style` exception for timeline positions.
- **D6. Shell placement.** The shell is rendered inside every page (`AccountPage.tsx` to `AccountFrame.tsx` to
  `WorkspaceShell.tsx`), while `app/(workspace)/layout.tsx:18-20` returns its children, so each navigation
  re-renders the shell on the server behind `/me` and entitlements, with no pending state. For a desk that
  switches between calendar, bookings and checkout all day, decide whether the shell and principal move to the
  layout with a client principal context.
- **D7. Receipts.** API-rendered numbered PDF (fits PAY-06's per-salon numbering) served through a bytes route
  like the photos, or browser print.
- **D8. Network scope.** State "online only, resilient": keep data on screen, retry with the same idempotency
  key, show a connection banner. Do not imply offline working.
- **D9. Budgets.** Per-action API budgets instead of one 3-second budget from header arrival (H2).

---

## 5. Shared packages

- `packages/api-contracts` is generated, not hand-maintained: `scripts/contracts.mjs` runs the API's
  `OpenApiDocumentTest`, writes `docs/api/openapi.json` and `src/generated/*`, and `pnpm contracts:check`
  (`.github/workflows/ci.yml:70-71`) fails when either is stale or when a hand-written file appears in
  `generated/`. Per-module entry points narrow the paths (`src/generated/booking.ts:14-29`); lint allows a
  module's client only in that module's `server/` (`apps/web/eslint.config.mjs:36-51,120-129`). Sound.
- For the React Native app: it exports TypeScript source and depends only on `openapi-fetch`, so it is usable
  as is. What is not shared and will be needed: the failure/renewal logic and error-code mapping (in
  `apps/web/src/features/identity/client/session.ts` and per-screen tables), the messages
  (`apps/web/messages/en.json`) and the theme tokens (`apps/web/src/styles/theme.css`), although CR-021 item 8
  (`docs/changes/CR-021-modular-extractable-architecture.md:92-95`) lists messages and tokens as shared.
- `packages/format` is small and tested, but English/LKR-shaped (M8). Hermes support for
  `Intl.NumberFormat` `style: 'unit'` (`src/index.ts:190-195`) is unverified.

---

## 6. Checked and found sound

- **Tenant resolution.** `Host` only; `X-Forwarded-Host` never read and stripped at the edge
  (`Caddyfile.staging:60`); exact one-label rule (`lib/tenancy/hostname.ts:40-57,72-74`); non-canonical paths
  refused (`lib/tenancy/console-host.ts:39-46`); the hand-off header is deleted from every request before the
  proxy sets it (`route-request.ts:146-147,204`) and re-validated on read
  (`features/branding/server/salon-header.ts:29-54`); the API address is never taken from a request
  (`lib/api-core/client.ts:24`).
- **No cross-tenant or cross-user caching.** No `'use cache'`, `unstable_cache`, `revalidate`, `cacheLife` or
  static params anywhere in `src`; workspace and console layouts block per request (`instant = false`); every
  salon-host and console response carries `private, no-cache, no-store` (`route-request.ts:46,246,275`); BFF JSON
  is `private, no-store`; per-salon upstream calls use `node:http` (`forward-hostname.ts`), so the framework's
  fetch cache is not involved. The image optimiser cannot reach tenant images: its internal fetch sends no
  `Host` (`node_modules/next/dist/server/image-optimizer.js:760-766`), so the routes answer 404.
- **Console and workspace separation.** Exact console host (`console-host.ts:14-19`); console paths are 404 on
  every other host before any lookup (`route-request.ts:179-185`); workspace paths are 404 on the console host
  (`:264`); separate cookie names and proxy key; the console BFF re-checks its host
  (`features/platform/server/operator-web.ts:176-177`).
- **Route handlers.** No Server Actions exist. `identity-web`, `operator-web` and `platform-web` check host,
  method, allowlisted action, CSRF and session before work (`identity-web.ts:363-457`,
  `operator-web.ts:173-239`, `platform-web.ts:40-56`). The three image routes and `healthz` are intentionally
  public; the API decides per image, and only PNG/JPEG with `nosniff` is passed on
  (`features/branding/server/brand-image.ts:11-12,31-34`).
- **CSRF.** Exact `Origin` equal to the request's own origin plus Fetch Metadata on every non-GET
  (`identity-web.ts:63-73`, `operator-web.ts:30-36`); GET is read-only by allowlist.
- **Sessions.** Tokens only in HttpOnly, Secure, SameSite=Lax, host-only cookies; nothing in
  `localStorage`/`sessionStorage`; refresh rotation coordinated across tabs with Web Locks and never retried
  after an unknown outcome (`session.ts:54-83`, `identity-web.ts:721-725`).
- **Open redirects.** None: every redirect target is a fixed same-origin path and the query is dropped
  (`route-request.ts:279-289`).
- **XSS.** One `dangerouslySetInnerHTML`, fed only a validated `#RRGGBB` (`styles/brand.ts:26,89-103`); plain
  pages escape their strings; external links carry `rel="noopener noreferrer"`; the map link is built on a fixed
  host from parsed numbers (`features/branding/client/profile-form.ts:77-81`).
- **Secrets.** No `NEXT_PUBLIC_*`; all `process.env` reads are in server files; the build scans its output for
  key material (`apps/web/scripts/scan-artifacts.mjs`); the image runs unprivileged with no env file.
- **Dates and salon time zone.** Booking instants arrive with the salon's offset and are sliced, not converted
  (`features/booking/client/booking-time.ts:7-15`); pickers round-trip through local midnight without
  `toISOString` (`:39-44`); Bookings takes "now" and "today" from the API's clock (`use-api-clock.ts`). A
  receptionist's browser in another zone sees the salon's dates and times.
- **Accessibility approach.** axe-core in real Chromium at six widths and two brands
  (`apps/web/test/a11y.ts:10-24`), 13 `*.a11y.test.tsx` files; rows open through a real button with a name
  (`features/booking/components/BookingsList.tsx:47-60`); focus returns to the opener
  (`BookingsScreen.tsx:118-123`); the countdown is announced once a minute. `tsconfig.a11y.json` only
  type-checks those files separately. The gap is M4 (not in CI).
- **Tests.** 120 test files, about 1,035 cases against 301 source files; screen tests drive behaviour through a
  stubbed `fetch` with realistic fixtures rather than asserting class names.
- **Build and image.** Standalone output, exact-pinned runtime dependencies, frozen lockfile in CI and Docker,
  package managers removed from the image, drain on SIGTERM.

---

## 7. Not verified

- Runtime behaviour of H1 and H2: derived from code, not reproduced (no server was started).
- Whether lint, typecheck, unit tests and the build pass on `main` now (not run; `tsc` would write
  `tsbuildinfo`).
- Security advisories and known issues for Next.js 16.4.1, React 19.3.0, next-intl 4.14.9 and the other pinned
  versions: no advisory source was consulted, and these releases are newer than what I can assess from memory.
  The config also relies on recent or experimental options (`cacheComponents`, `partialPrefetching`,
  `experimental.proxyClientMaxBodySize`, `export const instant`).
- What `<Link>` prefetching costs with `partialPrefetching: true` and blocking segments (how many proxy and page
  renders one view triggers).
- Bundle sizes (no build), including whether `libphonenumber-js/min` is tree-shaken out of screens that import
  only date helpers from `@salon/format`.
- The production edge: only `infra/stack/edge/Caddyfile.staging` was read.
- API-side facts the fixes depend on: access-token lifetime, hold duration, and that every endpoint
  authenticates on its own so the `/me` pre-flight can be dropped.
- The prototype was skimmed for the Today/timeline/walk-in/bookings screens only (`index.html:1016-1060`,
  `:1564-1567`), not read in full.
