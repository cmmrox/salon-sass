# F05 Service catalogue: product review

Reviewed as: a salon owner building the menu at the end of the day (desktop at 1280 and a small
Android phone at 360), a Manager sharing that work, a receptionist who reads prices but must not
change them, and the operator (support session). Customers meet the catalogue only through the
public menu and tax reads in R1 (their screens are F15's).
Compared with: Fresha and Booksy. **Not refreshed for this review:** web search was unavailable
during the pass (a session usage limit), so only F04's dated findings (Fresha's public help pages,
read 2026-11-01) are reused, where their scope still applies. Variants, Popular and tax were not
compared afresh.

## Test approach

| Type | Applies because | Ruled out because |
| --- | --- | --- |
| `functional` | The Services, Categories and Products tabs, the service, category and product sheets, the Popular card and Settings → Tax & charges | - |
| `responsive` | Those screens at the six widths of `docs/design/ui-ux-standards.md`, with long content (ten variants, a long menu, twelve products); the tab rails scroll sideways on phones as the prototype's do, and each tab is proved reachable | - |
| `accessibility` | Axe (WCAG 2.2 AA and best-practice) in each state, accessible names ("Move Nails up", "Variant 2 price", "Remove variant 2", the photo control, the Popular choice), keyboard-only paths and visible focus | - |
| `api` | New endpoints under `/api/v1/catalogue`: every boundary, every documented error code, the contract validator on every response | - |
| `tenant-isolation` | Every new endpoint and table: another salon's ids answer exactly as unknown ids, lists never cross, with no tenant context the tables return no rows, two salons' menus requested together never cross, photos only on their own hostname, and an operator outside a support session reaches nothing (TC-F05-002) | - |
| `permissions` | Each of the five roles on each change endpoint, signed out, a support session, suspended and read-only salons; settings permissions on the tax tab, catalogue permissions on products | - |
| `concurrency` | One case per invariant: two saves of one service version, two category moves, two services chosen as the sixth Popular, two tax saves | - |
| `data-integrity` | Terms versions immutable (the application role cannot update or delete them) and readable after later saves and after delete; variants keep stable ids; the default categories backfill for existing salons (scratch-database check); money in minor units; tax versions copy their name and rate, and a configured rate change leaves the earlier version intact | - |
| `security` | Photo uploads: content sniffing, size, minimum size, undecodable files, metadata removed; the public tax read never carries the registration number | - |
| `resilience` | - | No provider or job a user waits on is added; the browser's "unavailable" state is covered as a functional state (aborted data calls) |
| `load` | - | No endpoint here has a response-time target in the requirements or carries a booking |
| `branding` | - | F05 changes no brand; screens use the existing theme (an operator-provisioned salon's neutral brand) |
| `mobile` | - | No customer-app screen in F05 (F15) |
| `payment-integrity` | - | Deposits and tax are settings and arithmetic only; nothing is charged until F08 and F10 |
| `build` | - | No criterion about the build or the tooling |

Deliberate narrowing, as the specs and the catalogue allow: rules proved at the API are not proved
again in the browser. Java interfaces with no route (`ServiceTerms`, `ServiceBookingCounts`,
`TaxSettings`, `Products`, the deposit mapping) are the developer's integration and unit tests;
from outside, QA checks what they promise that a client can observe (stored versions, the menu,
the public tax). The tax arithmetic (S03 AC-4) is proved by the developer's unit test over its
range; from outside, "What customers pay" is compared with an independent half-to-even copy of
the rule, including the prototype's figures and a Rs. 100 tie at a 20% service charge.

Deferred, each agreed in its stage: TC-F05-S01-020 (delete refused while an upcoming booking uses the
service) and TC-F05-S02-015 (the "Most booked" ranking), both unblocked by F07. F07 has not started,
so neither was run.

New critical checks were shown to fail on a realistic violation before they were trusted (a mutated
copy, run once and removed): the journeys' category order and the operator refusal code.

## Verdict

Good enough to put in front of salon owners: the menu, variants, Popular, deposits, tax and products
all work as specified, the public menu returns exactly what customers may book, and the tax parts
add up to the price to the cent. One defect was found during the build's dry runs (a save someone
else overtook said nothing while the sheet was open) and was fixed in F05-S02 (0d5f9ac). The one
improvement that would help most is a quieter top of the Services and Products tabs on a phone,
where two information banners and the Popular card come before the first service.

## What works well

- The Services tab reads like the prototype: grouped cards, "From Rs. X", "45 to 90 min",
  "+10 min buffer", the options line with "+1", and the "From price" and "N options" badges.
- "What customers pay" shows real figures from the salon's own first service and product, updating
  as the form changes, and its amounts match the rule to the cent.
- Every refusal says what to do next ("Move its 2 services to another category first.", "6 already
  chosen. Remove one first.", "The deposit can’t be more than the service’s lowest price.").
- Price and time changes are versioned without the owner noticing; nothing about bookings is lost.
- Pages were usable in 140 to 260 ms on the local machine after navigation (hands-on timing).

## Suggestions

Ranked, most valuable first.

| Id | Class | Suggestion | Disposition |
| --- | --- | --- | --- |
| SUG-F05-01 | should | Merge the stacked information banners at the top of the Services and Products tabs into one | CR-025 |
| SUG-F05-02 | should | Fold empty categories on the Services tab into one line instead of a heading and "No services yet." each | CR-025 |
| SUG-F05-03 | idea | Write the preview's example rows as the spec words them ("A service at Rs. 2,500"), or update the spec to the two-column row | CR-025 (settled: two-column row) |

### SUG-F05-01 One information banner at the top of each tab

- **Observed:** on Products at 360, "Products you sell at the front desk ... Prices include VAT where it
  applies." and "Prices you enter include service charge (10%) and VAT (18%). Customers see the same
  price." are two separate banners before the first product; on Services, the services banner and the
  tax banner, then the Popular card, push the first service below the fold on a phone.
- **Why it matters:** a receptionist on a tablet or an owner on a phone scrolls past the same notes
  every visit; the tax sentence repeats what the Products banner already says about VAT.
- **Instead:** one Alert per tab carrying both sentences (the tax part only when it differs from
  the tab's own sentence), or the tax sentence once in the page header.
- **Evidence:** `ours-products-360.png` and `ours-services-360.png` against
  `proto-products-360.png` (QA scratchpad `f05-explore/`). The prototype also stacks two banners,
  so this is a change request, not a defect.

### SUG-F05-02 Fold empty categories

- **Observed:** a new salon's Services tab lists all ten default categories, each a heading with "No
  services yet." (`ours-services-1280.png`: the page is twice the screen's height with four services).
- **Why it matters:** an owner building the menu scrolls past eight empty headings to see what exists.
- **Instead:** one line, "No services yet in: Facials & skin, Brows & lashes, ..." with the
  categories as links that open New service in that category; or collapse empty groups.
- **Evidence:** the prototype groups by service line and shows only lines with services
  (`proto-services-1280.png`), so ours is longer than the reference for a new salon. The spec's
  AC-12 asks for "No services yet." per category, so this is a change request.

### SUG-F05-03 The preview's example rows

- **Observed:** with no service or product, "What customers pay" shows "A service" with "Rs. 2,500"
  in the amount column, not the spec's "A service at Rs. 2,500" (S03 AC-5).
- **Why it matters:** no one is misled: the row reads as the prototype's rows do. The spec's wording
  and the build differ, which a later reader may trip on.
- **Instead:** the lead corrects the spec's wording to the built row (no code change).
- **Evidence:** TC-F05-S03-017 checks the name and the price separately for this reason.

## Compared with other products

| Aspect of this flow | Ours | Others | Better for our users |
| --- | --- | --- | --- |
| A service's own deposit | Salon rule, a percentage, a fixed amount up to the lowest price, or none | Fresha: per-service custom deposit (F04 review's finding, [Set up payment policies](https://www.fresha.com/help-center/knowledge-base/payments/615-set-up-payment-policies), read 2026-11-01) | Same |
| Variants, Popular, tax-inclusive prices, products | As built | Not compared: no current public source read in this pass | Not judged |

Public help pages only; no account was created. The comparison was not refreshed in this pass.

## Hands-on check and design comparison

Walked as a new salon's Owner at 1280 and 360 on 2026-11-01, build 12cdf8e (product code identical to d8985b8): the Services tab with a
service with five variants, a plain service chosen for Popular, a service with a 30% deposit and a
hidden one; the Categories tab; the Products tab with a sold and an unsold product; the service sheet
with More options; Settings → Tax & charges with VAT and a 10% service charge.

Each screen was compared with the prototype (`aSvc`, `svTabs`, `catTab`, `prodTab`, `sePaint`,
`setTax`, opened with `S.m='a'`, `S.av='svc'` or `'set'`, `S.svt`, `S.stab='tax'`) at 360 and 1280.
Header, counts, tab chips, information banners, the Popular card and its segmented choice, the card
grid (two a row on desktop), card contents and badges, category rows with ↑ ↓ Rename Delete, the
products table and cards, the sheet's fields with More options, and the tax tab's preview card match.

Accepted differences, recorded at the stages' close: groups are the salon's categories, not service
lines (F18); Switches rather than tick rows; ink rather than the prototype's pink, because an
operator-provisioned salon has the neutral `#111827` brand; hidden cards and hint text in ink for
contrast. Screenshots are in the QA scratchpad (`f05-explore/`) and are not committed.

## Runs

All runs were on fresh databases on QA's own instance (8291/3291), against product code unchanged since
12cdf8e (no file under `apps`, `packages` or `infra` differs between 12cdf8e and d8985b8). Only QA's
tests changed between runs.
- S03 tests before the feature pass (the S03 dry run it never had), `qa/reports/20261101-220216-F05-S03-b82b29df8450`:
  21 of 24; the three failures were test defects, repaired in f735ecb and re-run 3 of 3.
- First feature pass, `qa/reports/20261101-220610-F05-cb818e8d1e03` (tests d3d70f3), 6 min 21 s: 85 of 88;
  the three failures were test defects (TC-F05-S01-030 counted later stages' tables, TC-F05-S01-032 still
  expected no Products tab, TC-F05-S03-019 signed in again within one authenticator step after a fast
  restart), repaired in f11b829 and re-run 3 of 3.
- **Recorded pass:** `qa/reports/20261101-224201-F05-14614a3cc4de`, one uninterrupted `qa/scripts/sdlc run
  --feature F05` at d8985b8 (API build identity d8985b8, database `f05qa_final`), 6 min 03 s: all 8 entries
  and all 88 automated cases passed (S01 API 29, S01 browser 11, backfill 1, S02 API 14, S02 browser 6,
  S03 API 15, S03 browser 9, journeys 3). No manual case; two cases deferred to F07.
- TC-F05-S03-019 restarts only QA's own API with `SALON_CATALOGUE_TAX_VAT_RATE_PERCENT=15` and puts it
  back.

### Re-verification after the PR #19 review fixes (2026-11-02, revision 866669b)

The fixes (product merge ad901d0) change the catalogue's API and web. A duplicate's name is cut in code
points. The services and products lists gain a required `currency`, which both sheets label amounts
with. A photo, removal or save result applies only to the sheet opening that sent it, and Save and
Delete wait for an upload while Close does not. `meta.test.ts` and `salon.test.ts` are developer tests only.

Targeted, not a full pass (decision 069); `evidence.reverification` names the selection and what was
left out and why. Six cases were added: TC-F05-S01-043 to 046, TC-F05-S03-025 and 026. On build
8ddb623, tests 866669b, fresh database, `qa/reports/20261102-F05-reverify-866669b`: 49 of 49 passed
in 6 min 05 s. While the new race case was written, its own hold helper unrouted a request still
pending; that test defect was repaired before the commit. Reopening a service during its own upload
keeps the result out of that sheet, and a later Save gets the conflict notice with Reload, as the fix says.

Limits: the new cases were run on the fixed build only, not seen failing on the old one (the emoji
duplicate's 409 on the old code is the lead's observation). A removal in flight when a sheet is
reopened shares the code path of the upload and was not raced separately; the developer's
`ServicesScreen.test.tsx` covers it.

Checking the USD labels found BUG-F05-001 (minor, open): a salon in another currency is told its price
limits in rupees. Wording only; amounts are right.

The full pass of record stays `qa/reports/20261101-224201-F05-14614a3cc4de` at d8985b8
(`evidence.full_pass_revision`); `integration.qa_revision` moved to 866669b, as F04's did for `pr-check`.

## Bugs filed from this review

- BUG-F05-001 (minor, open): a salon in another currency is told its price limits in rupees (found
  re-verifying the PR #19 fixes; see above).
- The one product defect found during the build (a conflict hidden behind the open sheet, F05-S01 dry
  run) was reported to the lead during the build, fixed in 0d5f9ac and confirmed by TC-F05-S01-039.
