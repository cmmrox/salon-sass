---
id: CR-023
title: Adopt the expanded prototype and redesign the R1 salon workspace plan
status: approved
requested_by: owner
requested_on: 2026-11-01
decided_on: 2026-11-01
requirements: [PLT-02, PLT-08, PLT-15, IAM-14, CAT-01, CAT-05, CAT-08, STF-01, STF-04, STF-08, BKG-06, BKG-11, BKG-16, PAY-06, PAY-09, PAY-10, NOT-02, NOT-05, NOT-06, CRM-01, CRM-02, REP-06, REP-10, INV-01, BRD-02, BRD-05, BRD-06, BRD-07, BRD-08, BRD-09]
features: [F02, F04, F05, F06, F07, F08, F10, F11, F12, F13, F14, F15, F16, F17]
stages: []
---

# CR-023 Adopt the expanded prototype and redesign the R1 salon workspace plan

## Request

The owner, on 2026-11-01, pointing at `/Users/cmmrox/Downloads/Your Salon Name (Copy) (1)/`: "i have
update the existing design . therfore first you have ti read the current exiting design first and
then you have to read the provided design and then identify the new changes in the new design . then
i need you to do a research in simailer appication using subagehen and gathere information how they
are build the application then i need you to check the existing solution and then as a BA i need you
to design better solution for salon application and as a architect update the plan according to it
since some of the stages you alrady proceed therfor create a change request before goto next step is
there nayting need to change . then update in future steps according to the plan . ... you have to
stick with the themeing .. before update anyting give me you rplan".

The supplied `index.html` and `brand.json` are byte-identical (SHA-1 `ccb0e26c…` for the HTML) to the
candidate CR-020 analysed on 2026-10-26. The owner confirmed this is the design to adopt and asked for
the analysis CR-020 did not do: the R1 screens, the delivered features, and the R1 plan itself.

## Why

CR-020 added the candidate's new modules as F17–F22 but left R1 (F04–F16) unchanged and never made
the candidate the design reference. F04 was next, so R1 would have been built against superseded
screens. CR-020 also did not ask whether the R1 plan was good enough. The inventory and research
below found real gaps:

- R1 web takes only staff-entered bookings (CR-010), so PAY-01 deposits had no way to reach a customer.
- Checkout could not take part payments, close a cash drawer or sell a product.
- Nothing in the R1 data model anticipated branches (D-10 "model it in R1") or processing time,
  though both arrive in R2.
- Customer screens of several M1 requirements had no owner in F15.
- The M3 gate made all R2 staff work wait for the per-salon store pipeline (F16).
- D-01 and seven other BRD decisions were followed but never recorded.

## Design comparison (R1 and delivered screens)

The theme is unchanged: identical tokens, Poppins, and the `#D6086A` salon accent a salon can
override (CR-013). The changes below are on screens that R1 or the customer app needs. CR-020's
modules are excluded.

| Screen | Change in the adopted design | Where it lands |
| --- | --- | --- |
| Surface switcher | Customer, Salon and Platform views; it opened on Customer, and the "Customer is later" note was gone | Restored to Salon web first with the note; Platform added (decision 070) |
| Navigation | Items appear by plan module; Bridal, Gift cards, Offers and Add-ons are new | Decision 077; R1 shows only delivered features |
| Today | Branch filter, processing stripe ("staff free"), banner for services no longer offered | F10; processing is F17 |
| New appointment | A real desk booking: any open day, mobile number required, "Find free times", source "Front desk" | BKG-11, BKG-16 (F07/F10) |
| Appointment sheet | "Take payment · Rs X", "Taken at salon" row, final-price box | PAY-06 (F10); final price is CAT-14 (F17) |
| Take payment | Part payments up to the balance; Cash, Card, Bank transfer (reference of 3 or more characters); "Record payment and complete" | PAY-06 (F10), decision 076 |
| Services | Create, edit, duplicate (hidden), hide or show online, delete blocked by upcoming bookings, "changes apply to new bookings only" | CAT-01, CAT-04 (F05) |
| Team | Add, edit or remove a member; public profile; removal blocked by upcoming bookings. Hours are static text in both designs | STF-01 (F06); schedule editor to be drawn |
| Customers | Notes are saved; a client card; hair and skin records, patch tests and consultation answers | CRM-01/03 (F12); structured records are CRM-06 (F17) |
| Reports | Revenue by service line and branch; CSV named by salon | F13; line and branch reports are R2 (F18) |
| Settings | Business, Branches and Branding tabs; hours, policies and notifications still absent | F04 tabs; F18 branches |
| Platform console | Add salon with business type, branch limit and modules; activate or remove a module per salon | F18 (decision 077) |
| Customer booking | Customise sheet, team profiles, "Pay advance", pay-in-full hidden for variable prices, revalidation before paying | F15; options and price kinds are F17 |
| My booking | Guest "Find a booking" by booking ID and mobile | Not built (decision 072) |

**Demo-only:** the demo guide, reset, simulation tools, random payment results, seeded data,
in-memory state and the vendor name "Pointlabs" (the product has no name; replaced by "the
operator"). These are listed in `docs/design/ui-ux-standards.md`.

**Missing from both designs:** opening hours and closures, booking policies, schedule editor,
notification settings and message balance, cash drawer and day-end close, payment links, role
limits, and import. The lead draws them at each feature's `feature-new` (decision 070).

## Research

Two subagents read official product help centres on 2026-11-01. These are workflow references, not
claims about vendor quality.

| Finding | Products and sources | Consequence |
| --- | --- | --- |
| Fixed statuses: completed only through checkout, no-show only after the start | [Fresha statuses](https://www.fresha.com/help-center/knowledge-base/calendar/600-update-appointment-statuses), [no-shows](https://www.fresha.com/help-center/knowledge-base/calendar/30-mark-appointments-as-no-shows) | BKG-06, decision 075 |
| Processing time frees the stylist; blocked time does not | [Fresha services](https://www.fresha.com/help-center/knowledge-base/catalog/64-create-services-1), [Mangomint resources](https://www.mangomint.com/learn/help-articles/calendar/resources/) | Segments from F05 (decision 074) |
| Price per level rather than per person | [Phorest](https://phorest.helpjuice.com/47354-services/337546-how-do-i-set-a-service-price-per-staff-member), [Fresha advanced pricing](https://fresha.com/help-center/knowledge-base/catalog/76-set-advanced-pricing-and-durations-) | CAT-08, decision 081 |
| A closed period leaves its bookings to be moved by hand | [Fresha closed periods](https://fresha.com/help-center/knowledge-base/calendar/20-set-business-closed-periods) | STF-04 lists affected bookings |
| Split tender, custom manual tenders, products in the cart | [Fresha sales](https://www.fresha.com/help-center/knowledge-base/sales/101339-raise-a-sale), [split payments](https://www.fresha.com/help-center/knowledge-base/sales/114-collect-split-payments) | PAY-06, INV-01 |
| Cash drawer: float, pay-ins and pay-outs, expected against counted | [Mangomint close-out](https://www.mangomint.com/learn/cash-drawer-daily-close-out/) | PAY-10 to R1 |
| Discount and refund rights by role | [Fresha roles](https://www.fresha.com/help-center/knowledge-base/team/100687-create-permission-roles), [Phorest access](https://phorest.helpjuice.com/49574-access/understanding-access-levels) | IAM-14 |
| Duplicates flagged on create and import; merges are irreversible | [Fresha merge](https://www.fresha.com/help-center/knowledge-base/clients/57-merge-client-profiles), [import](https://www.fresha.com/help-center/knowledge-base/clients/326-import-your-existing-client-list) | CRM-02, PLT-08 |
| WhatsApp first, SMS fallback, charged on delivery; reminders stop when the balance runs out | [Fresha updates](https://www.fresha.com/help-center/knowledge-base/calendar/130-send-appointment-updates), [balance](https://www.fresha.com/help-center/knowledge-base/billing-and-fees/412-manage-your-communication-balance) | NOT-02, NOT-05 |
| WhatsApp needs opt-in and approved templates | [Meta opt-in](https://developers.facebook.com/documentation/business-messaging/whatsapp/getting-opt-in), [pricing](https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing) | NOT-06, decision 078 |
| Resumable setup checklist ending in a test booking | [Square Appointments guide](https://squareup.com/help/au/en/article/5354-square-appointments-guide) | PLT-02 |
| Payments summary by method; liabilities include deposits | [Fresha payments summary](https://www.fresha.com/help-center/knowledge-base/reports/345-payments-summary-article-1), [finance summary](https://www.fresha.com/help-center/knowledge-base/reports/335-finance-summary-article-1) | REP-10 |
| Template apps must come from the content owner's account | [Apple 4.2.6](https://developer.apple.com/app-store/review/guidelines/), [Phorest enrolment](https://support.phorest.com/hc/en-us/articles/360016308499-Apple-Developer-Enrollment-iOS-Salon-Branded-App-Setup) | D-01, decision 071 |
| Saved-card charging is Premium-only at PayHere; Lite has no card hold | [PayHere fees](http://www.payhere.lk/fees/), [charging API](https://support.payhere.lk/api-&-mobile-sdk/charging-api) | Deposits by link (PAY-09), not saved cards, in R1 |
| Local salon software leads with commission and offline POS | [PopitCart](https://popitcart.com/), [possystem.lk](https://possystem.lk/products) | STF-08 to R2 |
| Gazetted holidays and Poya dates change yearly; lists disagree | [Government calendar 2026](https://documents.gov.lk/view/calander/2026/2026_E.pdf) | Holiday calendar is operator-published data |

## Business-analysis outcome

R1's shape is kept: web first, no customer website (CR-010), F04–F14 in their order. Within it:

1. **Front desk money is complete.** Part payments by tender with references, balance due,
   completion on payment, a cash drawer close and simple product sales.
2. **Deposits reach phone and WhatsApp customers** through a payment link to the gateway's hosted
   checkout. This is the single, narrow exception to CR-010 (decision 079).
3. **Bookings follow one state model**, so reports, no-show fees and checkout agree.
4. **Catalogue and team management are full lifecycles** with guards that protect upcoming bookings.
5. **Messaging never fails silently.** There is a balance warning, quiet hours queue messages
   instead of dropping them, and opt-in is recorded.
6. **R2 is not retrofitted.** Branch scope, service segments, one payment record and module
   entitlements are built into R1's foundations.

## Approved requirement register changes

| Id | Change | Release / priority |
| --- | --- | --- |
| PLT-02 | Checklist can be left and resumed, and ends with a test booking | R1 / must |
| PLT-08 | Template, duplicate flags, downloadable error report | R1 / should |
| PLT-15 | **New:** staff workspace in Sinhala and Tamil | R2 / should, unowned candidate |
| IAM-14 | **New:** discount limits and refund rights by role, enforced by the API | R1 / should, F10 |
| CAT-01 | Create, edit, duplicate, hide or show; delete only without upcoming bookings | R1 / must |
| CAT-05 | Tax off unless the salon is registered; labels from configuration | R1 / must |
| CAT-08 | Price levels with an optional per-person override | R2 / should, F17 |
| STF-01 | Team add, edit, remove with guard; bookable without sign-in | R1 / must |
| STF-04 | Holiday calendar published yearly by the operator; closures list affected bookings | R1 / must |
| STF-08 | Commission per service and product, tips separate | R3 → R2 / should, unowned candidate |
| BKG-06 | State rules for completed, no-show and cancelled | R1 / must |
| BKG-11 | Any open day, mobile required, free times offered, source recorded | R1 / must |
| BKG-16 | Adds the "front desk" channel | R1 / must |
| PAY-06 | Part payments, tenders with references, balance due, products, completion | R1 / must |
| PAY-09 | Deposit payment link by WhatsApp or SMS; hosted checkout; branded result page | R2/should → R1/must, F08 |
| PAY-10 | Cash drawer and day-end close | R2 → R1 / should, F10 |
| NOT-02 | WhatsApp, SMS, email; push with F15; WhatsApp automation is a plan feature | R1 / must |
| NOT-05 | Usage visible; warning before allowance runs out | R1 / must |
| NOT-06 | WhatsApp opt-in recorded; quiet hours queue messages, never drop them | R1 / must |
| CRM-01 | Adds tags | R1 / must |
| CRM-02 | Duplicate flags on create and import; audited merge | R1 / must |
| REP-06 | Collections by tender move to REP-10 | R2 / should |
| REP-10 | **New:** payments report by tender and deposits held | R1 / must, F13 |
| INV-01 | Product list sold at checkout, without stock | R3 → R1 / could, F05 |
| BRD-02 | The "chat on WhatsApp" link is on every plan | R1 / must |
| BRD-05–08 | Dedicated apps follow D-01 as a plan feature | R1 → R2 / must, F16 |
| BRD-09 | The shared app is the standard route | should → must, R1, F15 |

No requirement is retired or renumbered.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | 27 changed and 3 added (PLT-15, IAM-14, REP-10), applied to `docs/brd/requirements.yaml` |
| Delivered features | F01, F02 and F03 are complete and merged; **no stage is reopened**. None of their screens changes in the adopted design. F03's immutable plans stay; per-salon add-ons arrive with F18 |
| Features and stages | F04–F14 are backlog with no stages: summaries and ownership updated in place (F05 +INV-01; F08 +PAY-09, −APP-08; F10 +PAY-10, IAM-14; F13 +REP-10). F15 owns APP-08 and extends PAY-01, PAY-04, BKG-04, BKG-07, BKG-08, BKG-17, NOT-03, NOT-06, CAT-06 and STF-01. F16 moves to `M3-growth`/R2. F17's CAT-08 text changes; its outlined stages keep their ids |
| Test cases and automation | One delivered case changes: TC-F02-S04-023 asserts two design platform buttons and must expect three (Customer mobile design (later), Salon web, Platform console). The Salon-first default and the note it checks are preserved. QA updates the case and re-runs only it (decision 069). F04 onward have no cases yet |
| Data, API and released apps | No migration now. From F04, new tables carry `branch_id`; from F05, service time is stored as segments; one payment record from F08. Nothing is released, so there is no compatibility constraint |
| Size and delay | R1 grows modestly: PAY-09, PAY-10, IAM-14, REP-10 and INV-01, plus clearer F04–F14 scope. R2 avoids retrofitting branches, processing time and ledgers. M3 no longer waits for F16 |
| Design | `docs/design/prototype/index.html` replaced (decision 070); `brand.json` is unchanged |

## Recommendation

Approve all nine owner decisions as presented on 2026-11-01:

1. Keep web first.
2. Payment link (PAY-09) into R1.
3. Cash close (PAY-10) and role limits (IAM-14) into R1/should; products (INV-01) into R1/could; commission (STF-08) into R2.
4. WhatsApp automation is a plan feature; the chat link is on every plan.
5. No guest booking lookup.
6. Price levels.
7. Settle D-01.
8. The staff workspace is English in R1.
9. M3 waits for F15, not F16.

## Decision

Approved by the owner on 2026-11-01 with "approve", accepting every recommendation. Applied to the
register, roadmap, feature definitions, backlog, design and architecture documents and the decision
log (070–082) on the same date. The CR stays `approved` until TC-F02-S04-023 is updated and passing,
and becomes `implemented` once the last affected feature (F16) is complete.
