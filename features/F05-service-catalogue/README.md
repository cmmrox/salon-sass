# F05 Service catalogue

## What the customer gets

A salon's Owner or Manager keeps the salon's menu on one Services & pricing page with three tabs:

- **Services:** create, edit, duplicate (the copy starts hidden), hide or show online, and delete a
  service while no upcoming booking uses it. A service has a name, category, description, optional
  photo, price, time and buffer. It can instead have one set of variants (for example by hair
  length), each with its own price and time, and customers then see a "From" price.
- **Categories:** the salon names its categories and puts them in the order customers see.
- **Products:** a simple list of what the desk sells (name, price, tax), ready for checkout in F10.
  Stock is not tracked.

The salon chooses its Popular services or lets the most-booked ones show. A service can keep the
salon's deposit rule, set its own, or take no deposit. Settings gains a Tax & charges tab: VAT and
a service charge stay off unless the salon is registered, and every price customers see already
includes them. Price, time and buffer changes apply to new bookings only.

Customers browse the menu through the public API now. Their screens arrive with the app (F15).

## Requirements

- Services and categories: CAT-01, CAT-02
- Changes apply to future bookings only: CAT-04
- Variants and "from" prices: CAT-03
- Popular services: CAT-06 (the customer screen is F15's, decision 082)
- Tax and service charge: CAT-05
- Products: INV-01

BKG-09 is F04's. F05 adds its per-service deposit override, which F04 left room for (decision 089).
NFR-13 (currency and formats from configuration) applies throughout.

## How it is built

- **Surfaces:** API and staff web. There are no customer screens in R1 (CR-010); F15 reads the
  public menu API.
- **Design:** the reference is the prototype's Services & pricing screen and service sheet. The
  Categories and Products tabs, the Popular card, the sheet's photo, popular and deposit fields, and
  the Settings tab Tax & charges were drawn under CR-023 for the owner to confirm before planning
  (decision 070, `docs/design/ui-ux-standards.md`).
- **Scope inside the prototype's service sheet:** F05 builds name, price and time or one set of
  variants that set the price, category, description, photo, buffer, bookable online, popular and
  deposit. Kinds of pricing ("from", consultation), further and conditional option groups, add-ons,
  processing time and patch tests are F17's (CAT-07, CAT-11 to CAT-14). Service lines and the
  per-branch view are F18's.
- **Module:** `catalogue`, an empty module until now (`docs/engineering/backend.md`), owns
  services, categories, variants and tax settings, and products too (its module row gains them
  when S03 is planned). Its provided interface gives F07 a service's bookable terms; `booking` implements the questions the
  catalogue asks about bookings (decision 087's pattern):
  - Is any upcoming booking using this service? Until F07, none, so delete is never blocked.
  - Which services were booked most in the last 90 days? Until F07, none, so "Most booked" shows
    the salon's chosen services.
- **Segments (decision 074):** a service's (or a variant's) time is stored as ordered segments:
  active, processing and buffer. R1 screens edit time and buffer only.
- **Terms never rewrite a booking (CAT-04):** each change to price, time or buffer makes a new
  immutable version of the service's terms. F07 stores the version on each booking.
- **Branch scope (decision 073):** every new table carries `branch_id`. The menu is salon-wide in
  R1, under the default branch.
- **Photos:** stored and served like brand images (decision 086).
- **Tax:** names and rates come from configuration for the salon's country (VAT 18% for Sri
  Lanka). Prices are entered and shown tax-inclusive; the parts are worked out from the price.
- **Depends on:** F04 (the default branch, the salon's currency, booking rules' deposit, settings
  permissions, decision 085).

## Stages

The order and status of the stages is in `features/README.md` (generated). This section explains
why the feature is divided the way it is.

| Stage | Delivers | Why it is its own stage |
| --- | --- | --- |
| F05-S01 | The Services & pricing page with the Services and Categories tabs: categories created, renamed, ordered and deleted when empty; services created, edited, duplicated hidden, hidden or shown online, and deleted unless upcoming bookings use them, with photo and buffer. Time stored as segments, terms versioned. The public menu API. | The riskiest part: the data model F07 books against (segments, versioned terms) and the cross-module guard. Everything else extends it. |
| F05-S02 | Variants with their own price and time and the "From" price; the Popular card (chosen, or most booked); a service's own deposit or none. | Variants change how a service's terms and segments are stored and shown, and popular and deposit are read by later features (F07, F08, F15) through the same contract. |
| F05-S03 | The Settings tab Tax & charges (VAT and service charge, registration number, the "What customers pay" preview) and the Products tab. | Tax is a salon setting with its own rules and money arithmetic. Products need its VAT choice, and F10's checkout reads both. Independent of S02, so it can be built beside it. |

## Feature-level test focus

- **Journey:** an Owner builds a menu (categories in order, services with and without variants,
  a duplicate shown after review, a hidden service) and the public menu API returns exactly the
  services bookable online, in category order, with tax-inclusive prices and "From" prices.
- **Terms:** changing a price, time or buffer leaves the previous version intact and readable.
- **Isolation:** another salon's staff, and the operator outside a support session, can neither
  read nor change any of it. The public API of one salon never shows another's menu.
- **Roles:** only the Owner and Manager change the catalogue and tax settings; other roles can
  read what they need or are refused.
- **Guards:** delete is refused while an upcoming booking uses a service (proved through the
  interface until F07); a category with services cannot be deleted; names are unique.
- **Money:** VAT and service charge parts add up to the price to the cent; amounts follow the
  salon's currency (NFR-13).

## Decisions and open questions

- **Confirmed by the owner on 2026-11-01 (decision 092):** the drawn design, the outline, and the
  tax model: VAT applies to the price including the service charge; the service charge is on
  services only unless "Also on products" is on; R1 has VAT and service charge only.
- **Per-service deposit is built here,** not in F17. BKG-09 (R1, must) requires it, F04 left room
  for it, and CAT-11's "custom deposit" (R2) then needs no new work.
- **New salons start with suggested categories** (the prototype's set) that they rename, reorder
  or delete.
- **Popular** holds up to six services; "Most booked" counts the last 90 days and falls back to the
  chosen services until bookings exist.
