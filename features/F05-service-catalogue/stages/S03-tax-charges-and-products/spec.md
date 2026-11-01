# F05-S03 Tax, service charge and products

## Objective

An Owner or Manager states in Settings whether the salon is registered for VAT and whether it
charges a service charge. Every price stays tax-inclusive: what the menu shows is what the customer
pays, and the catalogue works out the parts. Each save is an immutable version, so bookings and
sales keep the tax they were made under. The Services & pricing page gains a **Products** tab: a
simple list of what the desk sells (name, price, VAT), ready for F10's checkout, without stock.

## Read first

- `docs/brd/requirements.yaml`: CAT-05 and INV-01.
- `features/F05-service-catalogue/README.md`, and F05-S01's `spec.md` (with "Corrections at close")
  and `stage.yaml` `handoff`: the catalogue module, its page, permissions and patterns.
- `docs/decisions/DECISION_LOG.md`: decisions 073, 085, 089, 091, 092, 093 and 094. Decision 092
  holds the tax model the owner confirmed.
- `features/F04-salon-setup/stages/S04-booking-rules-and-policies/spec.md`: the Settings tab,
  versions, history and save-at-once pattern this tab follows.
- `docs/architecture/multi-tenancy.md`: "Adding a table", "Adding an endpoint", "Tests you must
  write".
- `docs/engineering/backend.md`: modules, "Provided interfaces", "Ownership of tables and paths"
  (settings prefixes), "Persistence and migrations" (money), "Tests".
- `docs/engineering/web.md`: BFF rules, "Styling" (the component map), "Tests".
- `docs/design/ui-ux-standards.md` and `docs/design/prototype/index.html`: the F05 block's
  `setTax`, `txSplit`, `taxNote`, `prodTab`, `pePaint`, `peErr` and `peSave`, and `aSet`'s tab order.
- `docs/operations/verification-evidence.md`: developer-gate evidence.

## Requirements covered

| Requirement | Text | Covered in full or in part |
| --- | --- | --- |
| CAT-05 | Tax and service charge settings per tenant, off unless the salon is registered for them, with labels from configuration and prices shown to customers inclusive of them. | Full as settings and arithmetic. F07, F08 and F10 record and print the parts |
| INV-01 | A product catalogue (name, price, tax) whose products can be sold at checkout. Stock is not tracked until INV-02. | Full as a catalogue. Selling at checkout is F10's |

## Scope

- **API (`catalogue`):** tax settings as immutable versions; the public current tax; the price
  breakdown; products; the provided interfaces `TaxSettings` and `Products`.
- **Configuration:** the tax names and rates under `salon.catalogue.tax.*`.
- **Web:** Settings → **Tax & charges**; the **Products** tab with its sheet; the tax banner on the
  Services and Products tabs.

## Out of scope

- Recording tax on a booking, a sale or a receipt (F07, F08, F10). Stock (INV-02).
- Taxes other than VAT and the service charge (decision 092), and per-service tax exemptions.
- More than one branch (F18): settings and products are salon-wide on the default branch.

## Acceptance criteria

- AC-1: An Owner or Manager opens **Settings → Tax & charges** (between Booking rules and Branding).
  Until the salon first saves, both are off and `GET /api/v1/catalogue/tax` returns `null`. The tab
  says the prototype's "Turn these on only if your salon is registered for them. Names and rates come
  from the platform for your country. Changes apply to new bookings and sales only." (CAT-05)
- AC-2: The VAT section uses the configured name, rate and registration label (for Sri Lanka "VAT",
  18% and "VAT registration number"). Turning on "My salon is registered for VAT" requires the
  registration number: 1 to 20 characters of letters, digits and hyphens. The service-charge
  section uses the configured name ("Service charge"): turning it on requires a rate of 1 to the
  configured maximum (20) whole percent, and "Also on products", off by default, applies it to
  products too. Every broken rule is a 400 field error, nothing is saved, and the screen shows the
  message beside the field. (CAT-05)
- AC-3: **Save tax and charges** creates a new version and makes it current, with the configured
  name and rate copied into it. The previous version is unchanged and readable by id through
  `TaxSettings`. An identical save is 409 `invalid_state`; a stale `version` is 409 `conflict`; of
  two saves of one version sent at once, exactly one succeeds. Settings users see the history:
  time, author ("You", the label, or "A team member", decision 091) and the fields that changed,
  newest first, paginated. When the configured VAT rate differs from the current version's, the tab
  says "The VAT rate is now X%. Save to apply it to new bookings and sales." (CAT-05)
- AC-4: Prices are inclusive. For a price P in minor units, with VAT rate v and service charge s
  applying: `beforeVat = round(P / (1 + v))`, `vat = P - beforeVat`, `net = round(beforeVat / (1 +
  s))`, `serviceCharge = beforeVat - net`, rounding half to even. The parts add up to P exactly. A
  unit test proves it over every price from Rs. 1 to Rs. 10,000 in Rs. 1 steps for every service
  charge rate, and the prototype's examples: Rs. 2,500 with 10% and 18% gives service charge Rs.
  192.60 and VAT Rs. 381.36; Rs. 3,200 with VAT only gives Rs. 488.14. (CAT-05)
- AC-5: "What customers pay" shows the salon's first service bookable online and first product sold
  at checkout (or "A service at Rs. 2,500" and "A product at Rs. 3,200" when there are none) with
  the parts each includes, updating as the form changes, and the prototype's note "Prices are always
  shown with tax and service charge included ...". (CAT-05)
- AC-6: `TaxSettings` gives, for the caller's salon only, the current version or a version by id,
  and the breakdown of a price for a service or a product (with the product's VAT choice) under a
  given version. Another salon's version id is "not found". `GET /api/v1/catalogue/tax` needs no
  sign-in and returns the current version's id, VAT name and rate, and service-charge name, rate and
  products setting, never the registration number. (CAT-05)
- AC-7: The **Products** tab of Services & pricing lists the salon's products: name, price, tax
  ("VAT 18%", "No VAT", or "—" while VAT is off) and "Sold" or "Not sold", as a table from 900px and
  as cards below, with the prototype's empty state. The header says "N products · M sold at
  checkout", and the banner says "Products you sell at the front desk, added to a sale at checkout.
  Stock isn’t tracked." with the VAT sentence. (INV-01)
- AC-8: An Owner or Manager adds, edits and deletes products in the product sheet:
  - name, 2 to 40 characters, unique in the salon regardless of case;
  - price, Rs. 1 to Rs. 500,000 in whole rupees;
  - "Charge VAT", on by default, shown only while VAT is on (the choice is kept while it is off);
  - "Sell at checkout", on by default.

  At most 300 products (409 `limit_reached`). A broken rule is a 400 field error with the
  prototype's message. Delete asks first ("It will be removed from checkout. Past sales keep it. This
  can’t be undone.") and frees the name. A stale `version` is 409 `conflict`. (INV-01)
- AC-9: `Products` gives F10, for the caller's salon only, a product by id (name, price, currency,
  whether it charges VAT, whether it is sold) and the products sold at checkout. A deleted product
  is not returned. (INV-01)
- AC-10: The Services and Products tabs show the tax banner: "Prices you enter include service
  charge (10%) and VAT (18%). Customers see the same price." with only the parts that are on, or
  "Tax and service charge are off. Turn them on in Settings if your salon is registered." (CAT-05)
- AC-11: Roles: the Tax & charges tab and its endpoints need `settings.read` and `settings.manage`
  (decision 085); products need `catalogue.read` and `catalogue.price.change`. Other roles get 403
  `permission_denied`, a support session 403 `support_read_only` on every change, and suspended
  and read-only salons answer as the settings endpoints do. (CAT-05, INV-01)
- AC-12: Tenant isolation: another salon's tax versions and products are never readable or
  changeable and answer "not found"; with no tenant context the new tables return no rows. Every
  save and every product change is in the audit trail with before and after values. (CAT-05,
  INV-01)
- AC-13: The tab, the Products tab and the product sheet provide, at every width
  `docs/design/ui-ux-standards.md` names, loading, empty, saved, field-error, conflict, refused and
  unavailable states, the leave-with-unsaved-edits guard, keyboard-only use, and no WCAG 2.2 AA
  violation in the accessibility check. (CAT-05, INV-01)

## Design notes

- **Module.** `catalogue` owns tax settings and products (add products to its row in
  `docs/engineering/backend.md` at close). Tax settings follow F04-S04's booking rules: one
  immutable row per version, the newest current, no update or delete grant.
- **Configuration** is `catalogue`'s prefix `salon.catalogue.tax.*`: VAT name, rate and
  registration label, service-charge name and maximum. Startup is refused for a rate outside 0 to
  100 or an empty name. Each version copies the name and rate it was saved with, so a later change
  in configuration never rewrites a booking's or sale's tax.
- **Arithmetic** is a pure domain function with the rule in AC-4, used by `TaxSettings`, the
  preview (the web may compute the same formula for the live preview, proved equal by a shared
  table of cases) and later F07, F08 and F10.
- **Products** are current-only rows; F10 copies a product's name, price and VAT onto the sale.
- **Branch scope.** Every new table carries `branch_id` (the default branch).

## API and data changes

| Method/path | Permission | Request / result |
| --- | --- | --- |
| `GET /api/v1/catalogue/tax` | none (public) | `{versionId, vat: {name, ratePercent} or null, serviceCharge: {name, percent, onProducts} or null}`, or `null` before the first save |
| `GET /api/v1/catalogue/tax/settings` | `settings.read` | `{version, settings or null, configured: {vatName, vatRatePercent, registrationLabel, serviceChargeName, serviceChargeMaxPercent}}` |
| `PUT /api/v1/catalogue/tax/settings` | `settings.manage` | `{version, vat: {registered, registrationNumber}, serviceCharge: {on, percent, onProducts}}` -> 200; 400; 409 `conflict` or `invalid_state` |
| `GET /api/v1/catalogue/tax/history?page=&size=` | `settings.read` | `{items: [{versionId, version, createdAt, createdBy, createdByLabel, changedFields}], page, size, total}` |
| `GET /api/v1/catalogue/products` | `catalogue.read` | `{items: [{id, name, priceMinor, currency, chargesVat, soldAtCheckout, version}]}` (capped list, decision 094) |
| `POST /api/v1/catalogue/products` | `catalogue.price.change` | `{name, priceMinor, chargesVat, soldAtCheckout}` -> 201 |
| `PUT /api/v1/catalogue/products/{id}` | `catalogue.price.change` | The same with `version` -> 200 |
| `DELETE /api/v1/catalogue/products/{id}?version=` | `catalogue.price.change` | 204 |

Append migrations with the "Adding a table" checklist. Add the tables and the settings prefix to the
ownership map, and validate every new response with the contract validator.

## UI and UX

Follow the prototype (`setTax`, `prodTab`, `pePaint`) and the component map:
- the tab as Settings' other save-at-once tabs (Booking rules): Field with Switch for "My salon is
  registered for VAT", "Charge a service charge" and "Also on products"; Field with Input for the
  registration number and the rate; a Card for "What customers pay"; **Save tax and charges**;
  **History** in a Sheet of Item rows;
- the Products tab: Table from 900px, Cards below, Badge for "Sold" / "Not sold", Empty;
- the product sheet: Field with Input, Field with Switch, Alert Dialog for delete;
- the banners: Alert `info`.

| Screen | Route | Behaviour |
| --- | --- | --- |
| Tax & charges | `/workspace/settings/tax` | As AC-1 to AC-5 |
| Products | `/workspace/services/products` | As AC-7 and AC-8, with **+ New product** |

The Settings tab bar shows Profile, Hours & closures, Booking rules, Tax & charges and Branding.
Messages go through next-intl. Every interactive element has a `data-testid`.

## Risks and test focus

- `data-integrity`: the arithmetic (exact sums, rounding, the prototype's examples); versions
  immutable and carrying their own name and rate; a configuration change leaving old versions
  intact; products' money in minor units.
- `api` (validation and contract): every boundary (registration number 0, 1, 20, 21 characters and
  bad characters; rate 0, 1, 20, 21; product name and price limits; 300 products); `null` before the
  first save; the public read never carrying the registration number.
- `concurrency`: two saves of one version; stale product edits.
- `tenant-isolation` and `permissions`: settings permissions on the tab, catalogue permissions on
  products, each role and a support session.
- `functional`, `responsive`, `accessibility`: the tab, the preview, the Products table and cards,
  the sheet.

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
- `TaxSettings` and `Products` are documented in their Javadoc as the contracts F07, F08 and F10 use.
