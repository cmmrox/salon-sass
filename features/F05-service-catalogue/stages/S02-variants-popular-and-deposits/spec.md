# F05-S02 Variants, popular services and service deposits

## Objective

A service can have variants (for example by hair length), each with its own price and time, and
customers then see a "From" price and a range of times. The salon chooses up to six Popular
services, or lets the most-booked ones show once bookings exist. A service can keep the salon's
deposit rule, set its own, or take no deposit. All three reach F07, F08 and the customer app (F15)
through the terms versions and the public menu that F05-S01 built.

## Read first

- `docs/brd/requirements.yaml`: CAT-03, CAT-06, and BKG-09 (the per-service deposit override).
- `features/F05-service-catalogue/README.md`, and F05-S01's `spec.md` (with "Corrections at close")
  and `stage.yaml` `handoff`: the catalogue as built, its terms versions, `ServiceTerms`, the menu.
- `docs/decisions/DECISION_LOG.md`: decisions 074, 087, 089, 091, 092, 093 and 094.
- `apps/api/src/main/java/com/salonplatform/booking/BookingPolicies.java` and `DepositRule.java`:
  the deposit value type a service's own rule must map to.
- `docs/architecture/multi-tenancy.md`: "Adding a table", "Adding an endpoint", "Tests you must
  write".
- `docs/engineering/backend.md`: "Provided interfaces", "Persistence and migrations", "Booking
  integrity", "Tests".
- `docs/engineering/web.md`: "Styling" (the component map), "Tests".
- `docs/design/ui-ux-standards.md` and `docs/design/prototype/index.html`: the service card
  (`aSvc`'s `card`: options line, "From price" and "N options" badges), the service sheet's option
  group (`sePaint`, `seAbs`, `seAddC`, `seDelC`, `seErr`, the price list preview `svCombos`), the
  F05 block's `popCard`, `popTop` and its additions to `sePaint` (Show in Popular, Deposit), and the
  customer home's Popular list (`vLanding`).
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| CAT-03 | A service can have variants with their own price and duration (for example by hair length), and can display a “from” price. | Full |
| CAT-06 | “Popular” services on the home screen are chosen by the salon or derived from booking volume. | Full on the catalogue side: the choice, the setting and the menu's Popular list. "Most booked" ranks through an interface `booking` implements in F07; the customer home screen is F15's (decision 082) |
| BKG-09 (F04's) | Policies are set per salon: deposit (percentage or amount, cap, per-service override), ... | The per-service override, which F04 left to F05 (decisions 089, 092) |

## Scope

- **API (`catalogue`):** variants inside a terms version; the Popular choice and setting; a
  service's deposit as part of its terms; the menu's variants, "from" values and Popular list;
  `ServiceTerms` extended; a declared interface for booking counts with an implementation that
  answers "none".
- **Web:** the Variants section of the service sheet with its price list preview; the card's
  options line and badges; "Show in Popular" and Deposit in the sheet; the Popular card on the
  Services tab.

## Out of scope

- More than one option group, add-on groups, "show only when" rules, pricing kinds ("Starting
  from", "On consultation") and processing time (F17: CAT-13, CAT-14, CAT-12). The sheet shows
  one variants section only.
- Price levels per professional (F17, CAT-08).
- Taking a deposit, and booking a variant (F07, F08). Counting bookings for "Most booked" (F07).
- Tax and products (F05-S03).

## Acceptance criteria

- AC-1: In the service sheet an Owner or Manager turns on **Variants**, names the set (2 to 30
  characters, for example "Hair length" or "Who is it for?"), and gives 2 to 10 variants. Each has a
  name of up to 30 characters, unique in the set regardless of case, a price of Rs. 100 to Rs.
  500,000 in whole rupees, and a time of 5 to 480 minutes in steps of 5. Variants keep the order
  given. While variants are on, the service's own price and time are not shown, and the sheet says
  "Prices come from the variants below (From Rs. X)." Every broken rule is a 400 field error on that
  variant's field, with the prototype's message, and nothing is saved. (CAT-03)
- AC-2: Variants are part of the service's terms. Adding, removing, renaming or reordering a variant,
  or changing its price or time, creates a new terms version; earlier versions, with their
  variants, stay unchanged and readable. Each variant keeps a stable id across versions while it
  exists, so a booking can name the variant it was made for. Each variant's time is stored as
  segments: its active time, then the service's buffer when above zero. Turning variants off
  returns the service to one price and time, which the request must then give. (CAT-03, CAT-04)
- AC-3: Where a service has variants, the workspace card, the menu and `ServiceTerms` give its
  lowest and highest price and time. The card and the menu show "From Rs. X" when the prices
  differ and "Rs. X" when they are all the same, and "30 to 45 min" when the times differ. The card
  lists the set's name and its variants' names (the first four, then "+N"), with "From price" and
  "N options" badges. The sheet's **Price list preview** lists each variant with its price and
  time. (CAT-03)
- AC-4: The menu returns each service's variants (id, name, price, the customer's time) in order,
  and `ServiceTerms` returns each version's variants with their segments. A service without
  variants returns an empty list and is unchanged from F05-S01. (CAT-03)
- AC-5: **Show in Popular** marks a service as chosen. At most six services are chosen; a seventh
  is 409 `limit_reached` and the sheet says "6 already chosen. Remove one first." A duplicate is
  not chosen. Choosing or unchoosing does not create a terms version. (CAT-06)
- AC-6: The **Popular on your home screen** card on the Services tab switches between "Chosen by
  you" and "Most booked", saved at once, and states the current list with the prototype's
  sentences. The menu returns `popular`, the ids of the Popular services in order, among services
  bookable online only:
  - "Chosen by you": the chosen services, in category order then name order;
  - "Most booked": up to six services ranked by the interface's booking counts for the last 90
    days, most first; when it reports none (always, until F07), the chosen services.

  An integration test with an implementation that reports counts proves the ranking, the 90-day
  window it asks for, and that hidden and deleted services are left out. While "Most booked" is on,
  the sheet's Show in Popular is disabled with "Popular is set to “Most booked”". (CAT-06)
- AC-7: The sheet's **Deposit** offers Salon rule, Custom and No deposit. Salon rule shows the
  salon's current rule in words ("Uses your booking rules: 20%, up to Rs. 2,000.", or "no deposit",
  or "Not set yet" before F04's rules are first saved). Custom is a percentage of the price (1 to
  100) or a fixed amount (Rs. 100 to Rs. 100,000, and not more than the service's lowest price). No
  deposit says "Customers book this service without paying a deposit." A broken rule is a 400 field
  error. (BKG-09)
- AC-8: The deposit is part of the service's terms: changing it creates a new terms version.
  `ServiceTerms` returns it as "salon rule" (none set), "none", a percentage, or an amount. A
  documented mapping, tested, turns it into `booking.DepositRule` for
  `BookingPolicies.depositFor`: none to `None`, a percentage to `Percentage` with the cap at the
  safe-integer bound (decision 091), an amount to `Amount`. (BKG-09)
- AC-9: Duplicate copies the variants (with new ids) and the deposit, and not the Popular choice.
  (CAT-01, CAT-03)
- AC-10: Roles, isolation and audit as in F05-S01: only `catalogue.price.change` changes variants,
  Popular, the setting and deposits (others 403 `permission_denied`, a support session 403
  `support_read_only`); another salon's variants, choices and setting are never readable or
  changeable; every change, including the Popular setting, is in the audit trail with before and
  after values. (CAT-03, CAT-06, BKG-09)
- AC-11: The sheet's Variants section, Popular row and Deposit control, and the Popular card,
  provide the states, widths, keyboard use and accessibility of F05-S01's AC-16, with accessible
  names for each variant's fields ("Variant 2 price") and its remove button, and for the Popular
  setting. No WCAG 2.2 AA violation in the accessibility check. (CAT-03, CAT-06, BKG-09)

## Design notes

- **Variants inside the terms version (F05-S01's room for them).** A child of the terms version
  holds each variant's stable id, position, name, price and segments. For a service with variants,
  the terms version's own price and segments hold the lowest price and the shortest time, so
  F05-S01's readers keep working; document that in `ServiceTermsVersion`'s Javadoc.
- **The set's name** is part of the terms version.
- **`ServiceTerms`** gains `variants` (empty when none), the set's name and `deposit`. The records
  stay values only.
- **No dependency on `booking`.** `catalogue` declares its own deposit value. The mapping to
  `booking.DepositRule` lives in `booking` (F07/F08 call it), or as a documented, tested static
  method `booking` can call; `catalogue` never imports `booking`.
- **Booking counts (decision 087's pattern).** `catalogue` declares `ServiceBookingCounts` (for
  example `countsSince(Instant from)` returning service id to count for the caller's salon) with
  an implementation answering none, replaced by `booking` in F07.
- **The Popular setting** is a salon-level catalogue row with `branch_id`, saved at once, with a
  version for concurrency.
- **Rule text** for "Uses your booking rules: ..." comes from the public booking rules the web
  already reads (F04-S04) and `formatMoney`.

## API and data changes

Routes stay under `/api/v1/catalogue`.

| Method/path | Permission | Change |
| --- | --- | --- |
| `POST`/`PUT /api/v1/catalogue/services[/{id}]` | `catalogue.price.change` | Accept `variants: null` or `{name, items: [{id?, name, priceMinor, durationMinutes}]}`, and `deposit: {kind: "salon_rule" or "none"}`, `{kind: "percentage", percent}` or `{kind: "amount", amountMinor}`. `priceMinor` and `durationMinutes` are required only without variants |
| `PUT /api/v1/catalogue/services/{id}/popular` | `catalogue.price.change` | `{popular, version}` -> 200; 409 `limit_reached` |
| `GET /api/v1/catalogue/popular-setting` | `catalogue.read` | `{mode: "chosen" or "most_booked", version, popular: [serviceId]}` |
| `PUT /api/v1/catalogue/popular-setting` | `catalogue.price.change` | `{mode, version}` -> 200 |
| `GET /api/v1/catalogue/services[/{id}]` | `catalogue.read` | A service gains `popular`, `deposit`, and in `terms`: `variants`, `variantSetName`, `priceMinMinor`, `priceMaxMinor`, `durationMinMinutes`, `durationMaxMinutes` |
| `GET /api/v1/catalogue/menu` | none | A service gains `variants`, the min and max values and `deposit`; the response gains `popular` |

A variant without `id` is new; an `id` the current version does not have is 400. Append migrations
with the "Adding a table" checklist, validate every changed response with the contract validator,
and keep F05-S01's responses valid for a service without variants.

## UI and UX

Follow the prototype and the component map:
- the Variants section as a Card in the sheet: a Switch "This service has variants", the set's name
  (Field with Input), variant rows (three Inputs and a remove Button), **+ Add variant**, and the
  price list preview as Item rows;
- "Show in Popular" as Field with Switch, with its description;
- Deposit as a Toggle Group (Salon rule, Custom, No deposit), and for Custom a Select (Percentage,
  Fixed amount) with an Input and its suffix;
- the Popular card as a Card with a Toggle Group ("Chosen by you", "Most booked");
- Badge for "From price" and "N options".

The prototype's pricing-kind selector, "Sets the starting price", "Pick one"/"Add-ons"/"Required"
chips and "Show only when" are not shown. Messages go through next-intl. Every interactive element
has a `data-testid`.

## Risks and test focus

- `data-integrity`: variant ids stable across versions; old versions with their variants and
  deposits unchanged; min and max values; segments per variant with the service's buffer; the
  deposit mapping.
- `api` (validation and contract): every variant boundary (2 and 10 variants, 1 and 11; names 30
  and 31, duplicate in any case; price and time limits), an unknown variant id, deposit limits
  including an amount above the lowest price, the menu with and without variants.
- `concurrency`: two saves of one version; two services chosen at once as the sixth (exactly one
  wins).
- `tenant-isolation` and `permissions`: the new endpoints and the setting.
- `functional`, `responsive`, `accessibility`: the Variants section with ten rows on a phone, the
  Popular card, the Deposit control.

## Verification commands

```bash
cd apps/api && ./mvnw -q verify
pnpm -r lint
pnpm -r typecheck
pnpm -r test
pnpm --filter @salon/web test:a11y
pnpm --filter @salon/web build
pnpm contracts:check
```

## Done criteria

- The developer has checked every acceptance criterion on the running product with two synthetic
  salons, each role and a support session, and recorded it in the handoff.
- The verification commands are green.
- `ServiceTerms`, `ServiceBookingCounts` and the deposit mapping are documented in their Javadoc
  as the contracts F07 and F08 use.

## Corrections at close

Recorded by the lead when the stage closed. No requirement changed. Items 1 to 6 were given to the
developer during the build, in answer to QA's preparation:

1. **Defaults and replacement.** A service saved without `deposit` has "salon rule"; a salon starts
   in "Chosen by you". PUT replaces the service: an omitted `variants` means none, an omitted
   `deposit` means "salon rule".
2. **Variant messages** are R1's: "Use 2 to 10 variants.", "Name the variants, 2 to 30
   characters.", "Each variant needs a name up to 30 characters.", "“<name>” appears twice.",
   "Price for “<name>” must be Rs. 100 to Rs. 500,000.", "Time for “<name>” must be 5 to 480
   minutes, in steps of 5."
3. **Badges.** "From price" only when the variants' prices differ; "N options" whenever there are
   variants.
4. **Salon rule in words** when the rule is no deposit: "Uses your booking rules: no deposit."; before
   the rules are first saved: "Uses your booking rules: not set yet."
5. **A fixed deposit above the lowest price,** also after a price or variant change, is a 400 on
   `deposit.amountMinor`: "The deposit can’t be more than the service’s lowest price."
6. **Unchanged saves** of a Popular choice or the Popular setting are 409 `invalid_state`.
7. **As built (decision 096).** `booking` maps the deposit (`domain.ServiceDeposits`); the tables
   `catalogue_terms_variant` and `catalogue_popular_setting`; `priceMinor` and `durationMinutes`
   required only without variants; the menu's `variantSetName`; a public BFF read of the booking
   rules; new variant rows named "Option N"; Show in Popular saved with Save; ink for unselected
   segments; the audit actions `catalogue.service_popular_changed` and
   `catalogue.popular_setting_changed`.
8. **Fix to F05-S01 made in this stage** (0d5f9ac): conflict, refusal and unavailable notices show
   inside the open service or category sheet, and Reload refreshes it without saving.
