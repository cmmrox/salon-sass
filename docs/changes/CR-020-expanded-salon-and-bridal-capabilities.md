---
id: CR-020
title: Expanded salon, Bridal and customer capabilities
status: approved
requested_by: owner
requested_on: 2026-10-26
decided_on: 2026-10-26
requirements: [PLT-13, PLT-14, CAT-12, CAT-13, CAT-14, BKG-22, BKG-23, PAY-15, MKT-09, MKT-10, MKT-11, APP-21, OCC-01, OCC-02, OCC-03, OCC-04, OCC-05, OCC-06, OCC-07, OCC-08, OCC-09, OCC-10, OCC-11, OCC-12, OCC-13, OCC-14, OCC-15, OCC-16, OCC-17, OCC-18, AST-01, AST-02, AST-03, AST-04, AST-05, AST-06]
features: [F04, F05, F06, F07, F08, F09, F10, F11, F12, F13, F14, F15, F17, F18, F19, F20, F21, F22]
stages: [F17-S01, F17-S02, F17-S03, F18-S01, F18-S02, F18-S03, F19-S01, F19-S02, F19-S03, F19-S04, F20-S01, F20-S02, F20-S03, F21-S01, F21-S02, F21-S03, F22-S01, F22-S02, F22-S03]
---

# CR-020 Expanded salon, Bridal and customer capabilities

## Request

The owner supplied a new HTML prototype and `brand.json` and asked to compare them with the previous
design, identify every newly added or changed feature, research comparable applications, improve the
solution from a business-analysis perspective, redesign the business requirements with all features
included, and update the implementation plan.

## Why

The new prototype is substantially broader than the current design and requirement register. It adds
complex Bridal and occasion work, richer service configuration, branch operations, commerce and AI
assistance. These are valuable capabilities, but the prototype mixes them with demo shortcuts,
in-memory state and unsafe identity/payment assumptions. Approving the screens literally would create
ambiguous ownership, double-booking, financial and privacy risks.

The opportunity is to preserve the simple appointment journey while adding a complete, operationally
safe solution for higher-value occasion services and the other new capabilities. Comparable products
confirm the need for shared scheduling, intake, deposits, packages, gift cards and human support; they
do not justify copying any vendor's pricing, visual identity or unsupported AI claims.

## Prototype comparison

Baseline: `docs/design/prototype/index.html`. Candidate: owner-supplied
`/Users/cmmrox/Downloads/Your Salon Name (Copy)/index.html` and its adjacent `brand.json`.
The candidate has 2,933 lines against the heavily minified baseline's 536; a textual diff reports
2,527 insertions and 130 deletions. A function-name inventory found 508 additions and no removals.
These counts explain scale, not implementation effort or requirement count.

| Area | Previous design/requirements | Candidate design | Proposed treatment |
| --- | --- | --- | --- |
| Service lines and business type | General salon catalogue | Hair, Beauty and Bridal lines; business-type presets | Add configurable service lines and presets without making the sample types a closed list |
| Branches | Multi-branch already planned for R2 | Per-branch catalogue, contacts, staff allocation, calendars and reports | Retain and detail existing PLT-11, APP-20 and REP-07; make every branch scope tenant-safe |
| Plans and modules | Plan entitlements and billing in F03 | Module activation/removal, branch limits and module-specific workspaces | Extend generic entitlement administration; enforce it in the API, never only by hiding navigation |
| Catalogue pricing | Base service price and duration | Fixed, “from” and consultation pricing; final-price confirmation | Add explicit price kinds and a recorded final-price/customer-confirmation flow |
| Service customisation | Add-ons already planned | Conditional single/multi-select options affecting price, duration and buffer | Add versioned option groups, validation and deterministic checkout calculation |
| Treatment operations | Resources and processing time partly planned, some R3 | Rooms/resources, processing time that releases the professional, intake and patch-test readiness | Bring processing time into R2; integrate resources and readiness with booking rather than a separate flag |
| Staff | Staff, schedules and leave | Public profiles, photos, specialisms, consultations and specialist premiums | Extend staff/catalogue rules; price effects must be explicit and historically snapshotted |
| Bridal discovery | Not present | Published packages, gallery and customer enquiry | Add staff-managed versions and consented media; customer discovery belongs in mobile, not a new website |
| Bridal pipeline | Ordinary bookings only | Enquiry, assignment, notes, statuses, consultations and trials | Add a separate occasion workflow linked to customer and staff records |
| Quotes | Not present | Options, travel, discounts, inclusions/exclusions, expiry, approval and versions | Add immutable accepted snapshots; revisions supersede rather than overwrite |
| Occasion conversion | Ordinary appointment confirmation | Provisional holds, advance confirmation and accepted-quote conversion | Add expiring holds and idempotent conversion; only verified payment confirms where an advance is required |
| Party and events | Group bookings and on-location services are R3/could | Multi-event bookings, party members/tasks, ready-by times, shared conflicts and travel | Promote shared group scheduling to R2; make on-location work R2/should with travel buffers |
| Occasion money | Appointment payments, deposits and refunds | Balance ledger, payment schedules, security deposits and refunds | Reuse verified payment infrastructure; separate refundable security-deposit liability from service revenue |
| Occasion changes | Appointment cancellation/rescheduling | Cancellation, postponement and charge/refund/balance outcomes | Revalidate the whole event graph and preserve old dates, terms and financial audit |
| Occasion operations | Salon calendar and reports | Bridal workspace, calendar, operational settings and reports | Add filtered operational views and reporting without duplicating the booking source of truth |
| Gift cards | R3/could placeholders | Sale/issue, recipient delivery, scheduled delivery, pending payment, balance, partial redemption, expiry/extension/void | Promote to R2/should with an append-only ledger, reservations, safe restoration and liability reporting |
| Offers and loyalty | Offers/points already R2/should | Eligibility, first-visit/visit-count rules, pause/end, stacking, adjustments and refund reversal | Refine existing MKT/APP requirements and define deterministic stacking and restoration |
| AI assistants | Not present | Customer and staff assistance, price/availability answers, guided booking, scheduling previews and handoff | Add R3 guided assistants over authoritative APIs; no payment in chat and no unconfirmed mutation |
| Visual design | Existing salon screens | Gifts/Rewards hero, team profiles, dashboards and responsive states | Use as design input only; Tailwind/shadcn and accessibility standards remain authoritative |
| Brand | Placeholder sample brand | Name, tagline, colour, embedded logo and banner | Sample content only; `brand.json` adds no functional requirement and does not name the product |

The candidate HTML has a duplicated closing `</body></html>` and uses in-memory/demo behaviour. Its
reset buttons, demo subscriptions, random payment results and seeded businesses are prototype aids,
not requirements.

## Research and improved business solution

Official product material reviewed on 2026-10-26:

| Product and source | Relevant established workflow | Consequence for this product |
| --- | --- | --- |
| [Vagaro makeup artist software](https://www.vagaro.com/pro/makeup-artist-software) | Bridal parties, consultations, availability, event/allergy intake, preferences, deposits, loyalty and packages | Support the complete consultation-to-party workflow; capture only necessary health/event data with access and retention controls |
| [Fresha scheduling](https://www.fresha.com/for-business/features/scheduling) | Shared calendars, custom availability, clash prevention, breaks/blocked time, reminders and deposit/no-show protection | Use one authoritative scheduling and conflict engine for appointments and occasion tasks |
| [Fresha point of sale](https://www.fresha.com/for-business/features/point-of-sale) | Services, products, memberships, packages and gift cards in online/in-store checkout | Apply common totals, entitlements and tender rules across desk and customer checkout |
| [Mangomint memberships and packages](https://www.mangomint.com/features/memberships-and-packages/) | Bundles, balance/use tracking, self-service purchase and retained agreements | Preserve purchased terms and customer-accessible balances; do not let later package edits rewrite history |
| [Zenoti AI receptionist overview](https://www.zenoti.com/blog/zenotis-ai-receptionist-for-salons-and-spas-empowering-teams-to-deliver-exceptional-service) | AI booking/rescheduling/cancellation under business rules | Assist through the same availability, permission and confirmation boundaries; never maintain a shadow diary |
| [Mangomint phone and two-way texting](https://www.mangomint.com/features/phone/) | Direct human communication alongside digital workflows | Provide a visible human handoff; AI must not become the only support route |

These are workflow references, not claims about vendor quality, market share or outcomes.

### Needs by user

- **Salon owner/admin:** one configurable catalogue across branches; predictable prices, capacity and
  entitlements; occasion pipeline visibility; secure money and liability reporting; policy control;
  staff productivity; and historical records that survive package or module changes.
- **Salon manager/front desk:** fast ordinary booking, a guided exception path for complex events,
  reliable staff/room/travel conflicts, clear next actions and balances, safe changes, and human-owned
  customer conversations.
- **Professional:** an accurate daily schedule, preparation/processing/travel time, client preferences
  and authorised intake information, clear party assignments and ready-by targets, and no silent moves.
- **Customer:** transparent price ranges and final totals, suitable professional/service choices,
  easy enquiry and ordinary booking, clear quote/payment/cancellation terms, reliable reminders,
  accessible gift/loyalty balances, progress visibility and a human when automation cannot help.
- **Platform operator:** configurable plans/modules and limits without tenant-data access by default,
  retained legal/financial records, support/read-only enforcement, usage reporting and safe offboarding.

### Business-analysis corrections to the candidate

1. Keep ordinary appointments and Bridal/occasion work as related but distinct business aggregates.
   A haircut must not inherit a seven-step enquiry; an occasion must not be flattened into one appointment.
2. An accepted quote is an immutable snapshot of lines, options, prices, taxes/charges, travel, terms
   and cancellation/postponement policy. A revision creates a new version and approval state.
3. Database-backed allocation covers appointments, consultations, trials, party tasks, rooms, travel
   and processing intervals. Staff may acknowledge operational warnings, never override hard conflicts.
4. Provisional holds expire safely. Conversion and payment retries are idempotent; only verified server
   payment confirmation satisfies an advance-payment condition.
5. Security deposits have their own receipt, deduction and refund audit and are not service revenue.
6. Cancellation quotes show charge, refund and amount still owed. No silent saved-card charge is added;
   charging requires existing consent and PAY-12 capability.
7. Postponement records old dates, revalidates every event/task/resource and any accepted fee, and never
   silently moves staff.
8. Removing a module stops new sales/configuration but preserves historical records, exports, financial
   obligations and access needed to honour confirmed work.
9. Gift cards use an append-only ledger, atomic balance reservations, idempotent redemption/refund/
   restoration, masked rate-limited lookup and outstanding-liability reporting. Expiry/refund terms are
   disclosed and configurable for applicable law; “never refundable” is not a universal rule.
10. AI discloses automation, uses authoritative price/availability, requires confirmation before mutation,
    never takes payment in chat, respects tenant/role/read-only/entitlement state, offers human handoff,
    and has privacy, retention and provider-failure behaviour.
11. Customer photos, event details, allergies and formulas require purpose, consent, role visibility,
    export/deletion and retention treatment. Gallery publication requires separate explicit consent.
12. Currency, time zone, date formats, tax, expiry and displayed prices are configuration, not universal
    rules. The prototype's names, currency and amounts are examples only.
13. The prototype's phone-number lookup is rejected: CRM-02 permits shared numbers and requires deliberate
    staff merge. Match hints may be offered; same phone must never automatically identify or merge customers.
14. Payment buttons and simulated outcomes do not change PAY-03, PAY-04 or NFR-11: server notifications,
    idempotency and traceable gateway references remain mandatory.

## Approved requirement register changes

No requirement is retired. All NFRs continue to apply. The owner approved the priorities below;
the existing R1 behaviour of shared catalogue/booking requirements is not expanded implicitly.

| Id | Approved change | Release/priority |
| --- | --- | --- |
| PLT-13 | Add configurable service lines and business-type presets that seed, but do not restrict, catalogue and module defaults | R2 / should |
| PLT-14 | Add safe module offboarding: block new use while preserving confirmed work, records, exports and financial obligations | R2 / must |
| CAT-12 | Move processing time from R3 to R2; professional is released during processing while resource/event occupancy remains explicit | R2 / should |
| CAT-13 | Add conditional single/multi-select option groups and add-ons with validated price, duration and buffer effects | R2 / should |
| CAT-14 | Add fixed, “from” and consultation-required pricing plus recorded final-price confirmation | R2 / should |
| BKG-22 | Move group bookings to R2; coordinate multiple professionals, party tasks, resources and ready-by times atomically | R2 / must |
| BKG-23 | Move on-location services to R2; capture service address and travel buffers in conflict checks | R2 / should |
| PAY-15 | Add refundable security deposits as a separate liability with receipt, deduction and refund audit | R2 / should |
| MKT-09 | Expand and move gift cards to R2: sale/issue, recipient and scheduled delivery, verified-payment activation and balance access | R2 / should |
| MKT-10 | Add gift-card partial redemption, atomic reservations, expiry/extension/void, cancellation restoration and liability reporting | R2 / should |
| MKT-11 | Add deterministic offer/points/gift-card stacking, eligibility and use limits, plus refund/cancellation reversal | R2 / should |
| APP-21 | Move customer gift-card purchase, recipient delivery and balance/redemption access to R2 mobile | R2 / should |
| OCC-01 | Capture occasion enquiries with service, event date/venue, party size, contact preference and consent; acknowledge receipt | R2 / must |
| OCC-02 | Triage, assign and track enquiries with notes, ownership, status history and next action | R2 / must |
| OCC-03 | Schedule consultations/trials and record authorised outcomes, preferences and intake against the occasion | R2 / must |
| OCC-04 | Manage versioned occasion packages and a consent-controlled gallery; customer views see only published versions/media | R2 / should |
| OCC-05 | Build quotes from package/custom lines, options, party services, travel, discounts, inclusions/exclusions, taxes and payment schedule | R2 / must |
| OCC-06 | Version, send, expire, approve/reject quotes; accepted versions are immutable snapshots with evidence of terms accepted | R2 / must |
| OCC-07 | Convert an accepted quote once; support expiring provisional holds and verified-advance confirmation without duplicate bookings/payments | R2 / must |
| OCC-08 | Schedule multiple events, party members and professional tasks with ready-by times and shared staff/resource conflict prevention | R2 / must |
| OCC-09 | Show occasion payment schedule, verified transactions, security-deposit state and outstanding balance from one traceable ledger | R2 / must |
| OCC-10 | Plan on-location events with address, travel assignments/buffers and travel charges included in quote and conflict rules | R2 / should |
| OCC-11 | Provide staff occasion pipeline, detail, task and filtered calendar views with operational notes and entitlement/read-only enforcement | R2 / must |
| OCC-12 | Calculate and record cancellation outcomes from accepted terms, including charge, refund and amount still owed | R2 / must |
| OCC-13 | Postpone by requoting where needed and atomically revalidating all events/tasks/resources, preserving old dates and accepted fees | R2 / must |
| OCC-14 | Report enquiry conversion, quote ageing, upcoming workload, balances, refunds and occasion revenue by permitted branch/service line | R2 / should |
| OCC-15 | Let mobile customers discover published packages/gallery, submit an enquiry and manage their authenticated enquiries | R2 / must |
| OCC-16 | Let mobile customers review/accept quote versions, pay through verified checkout, track events/tasks/balances and request changes | R2 / must |
| OCC-17 | Record purpose and consent for occasion media/intake and separately for public gallery use; support access, export and retention/deletion policy | R2 / must |
| OCC-18 | Send customer/staff notifications for enquiry, quote, payment, upcoming event, task and change events using preference and retry rules | R2 / should |
| AST-01 | Guide customers with authoritative service/price/availability answers and a confirmed path into ordinary booking or occasion enquiry | R3 / could |
| AST-02 | Let authorised staff preview scheduling/front-desk actions and explicitly confirm before the same business APIs mutate data | R3 / could |
| AST-03 | Disclose automation, state limitations and transfer to a human with an authorised conversation summary | R3 / could |
| AST-04 | Enforce tenant, role, read-only, module and object-level access in every assistant retrieval and action; never take payment in chat | R3 / could |
| AST-05 | Define prompt/conversation retention, sensitive-data handling, provider failure, timeout and deterministic non-AI fallback | R3 / could |
| AST-06 | Manage subscription/trial/status/limits, usage and cost reporting, with graceful exhaustion and no loss of core booking access | R3 / could |

Existing MKT-05/MKT-06, APP-15/APP-16/APP-17/APP-18, CRM-06, PAY-09 and NOT-10/NOT-11 are refined or
reused, not duplicated. NOT-10/NOT-11 remain R3/could and provide communication/channel foundations for
the assistants. Existing CAT add-on/resource/package ids and PLT-04 entitlements remain their homes.
Customer browser screens in the prototype are explicitly mapped to mobile under CR-010 and NFR-21.

## Updated implementation and delivery plan

Keep R1 in its current order and release it before expanding this scope. F03 is not reopened and R1
does not become a mega-release. The approved features are registered as backlog with outlined stages;
their normal dependency and merge gates still control when each becomes planned.

| Feature | Scope and ownership | Depends on | Release/surfaces |
| --- | --- | --- | --- |
| F17 Service customisation and treatment readiness | CAT-07–14, CRM-06 and APP-18; options, add-ons, packages, resources, intake/patch tests, consultations and specialist pricing | F05, F06, F07, F08, F12 | R2 / api, web, mobile |
| F18 Multi-branch salons and module administration | PLT-11/13/14, APP-20, REP-07; branch catalogue/contacts/staff/calendar/report scopes, service-line presets and safe module offboarding | F03, F04, F05, F10, F13, F17 | R2 / api, web, mobile |
| F19 Bridal and occasion operations | OCC-01–14, OCC-17/18 staff side, BKG-22/23, PAY-15; enquiry, packages, quotes, conversion, party/event scheduling, ledger, changes and reporting | F09, F11, F17, F18 | R2 / api, web |
| F20 Bridal customer mobile experience | OCC-15/16 and customer-facing OCC-17/18; discovery, enquiry, quote acceptance, payment, tracking and change requests | F15, F19 | R2 / api, mobile |
| F21 Offers, loyalty and gift cards | MKT-05/06/09/10/11, APP-15/16/21; shared checkout calculation, ledger, lifecycle, delivery and reporting | F08, F09, F12, F14, F15, F18 | R2 / api, web, mobile |
| F22 AI-assisted booking and front desk | AST-01–06 with NOT-10/11 integration; guided customer/staff assistants, entitlements, safety, privacy, usage and handoff | F15, F19, F20, F21 | R3 / api, web, mobile |

Delivery rules:

1. The register, roadmap, requirement ownership and backlog stage outlines are applied under this CR.
   Each stage must be planned in detail and shown working on its own before closure.
2. Preserve the current F01–F16 order, including F16's R1/M2-mobile classification. F04–F16 remain
   backlog; new scope does not bypass their independent feature QA.
3. Design stage specs from the workflows above, not from JavaScript simulations. Establish versioned
   domain/API contracts and tenancy/permission tests before UI breadth.
4. Build shared scheduling and checkout primitives before occasion/gift/AI consumers. Reuse notification,
   customer, payment and reporting infrastructure rather than adding parallel ledgers or calendars.
5. Within each feature, sequence domain/migration and API work, staff workflow, customer surface where
   applicable, then reporting/offboarding and hardening. QA verifies the whole feature after all its stages.
6. Update the design reference only through the normal design/requirements authority. Preserve the
   previous prototype for comparison; any imported candidate must be cleaned of demo code and remain
   subject to Tailwind/shadcn, responsive and accessibility rules.

## Impact

| Area | Effect |
| --- | --- |
| Requirements | Added PLT-13/14, CAT-13/14, PAY-15, MKT-10/11, OCC-01–18 and AST-01–06; changed CAT-12, BKG-22/23, MKT-09 and APP-21 as above. No retirements. Applied to `docs/brd/requirements.yaml` under this CR |
| Features and stages | F03 is complete, QA pass, awaiting merge and is not reopened. F04–F16 remain backlog with unchanged R1 scope. F17–F22 are new backlog features with nineteen outlined stages and no test cases or implementation yet |
| Test cases and automation | Existing F03 cases/automation remain valid. New feature cases must cover cross-tenant access for every entity/file/export, role/entitlement/read-only enforcement, quote versions, simultaneous staff/task/room allocation, hold expiry, payment/refund idempotency, concurrent gift redemption, cancellation/postponement recalculation, AI confirmation/handoff/privacy, accessibility and the mobile/browser boundary |
| Data, API and released apps | Future migrations introduce enquiry, consultation, package/version, quote/version, occasion/event/party/task, payment/refund/security-deposit, gift-card/ledger/reservation, offer/loyalty ledger and AI usage/conversation concepts. APIs must be versioned/backward compatible and all files/exports tenant-scoped. No released customer app migration is currently implied |
| Size and delay | Large: six coherent features, most requiring several stages, plus shared-engine changes. Detailed estimates follow stage design. The approved allocation leaves R1 timing/scope untouched and expands R2 and R3 |
| Design and research | The supplied design differs substantially in workflow and visual breadth; brand assets are sample configuration only. Sources above establish comparable workflows; no unverified competitor metrics or copied branding are introduced |

## Approved scope packages

The owner approved all five packages together. Dependencies still control their detailed planning and
execution order.

| Package | Requirement/feature scope | Approved allocation |
| --- | --- | --- |
| A. Service and branch foundation | PLT-13/14, CAT-12/13/14, existing PLT-11/APP-20/REP-07; F17/F18 | R2, before Bridal |
| B. Bridal staff operations | BKG-22/23, PAY-15, OCC-01–14/17/18 staff side; F19 | R2, after A |
| C. Bridal customer mobile | OCC-15/16 and customer-facing OCC-17/18; F20 | R2, after F15 and B |
| D. Offers, loyalty and gift cards | MKT-09/10/11, APP-21 and refinements to existing MKT/APP commerce; F21 | R2; gift cards replace their former R3/could position |
| E. AI-assisted booking/front desk | AST-01–06 and NOT-10/11; F22 | R3, after authoritative workflows and human channels exist |

## Recommendation

Approve packages A–D for R2 and E for R3 using the allocation above. This includes every substantive
candidate capability while rejecting only unsafe/demo behaviour and the customer-website interpretation.
Keep ordinary booking simple, retain the existing R1 plan, and make immutable quotes, conflict-safe
scheduling, verified payments, auditable gift/deposit ledgers, privacy and human handoff acceptance
conditions rather than later enhancements.

## Decision

Approved by the owner on 2026-10-26 with “confirm”, accepting the recommendation in full: packages A–D
for R2 and E for R3, with R1 unchanged. Applied to the living requirement register, roadmap and backlog
feature/stage ownership on the same date. The CR remains `approved`, not `implemented`, until all affected
stages are complete.
