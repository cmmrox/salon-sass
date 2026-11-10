# Global market check of the requirement register

Date: 2026-11-10. Scope read: `docs/brd/requirements.yaml` (all 16 modules), `docs/ROADMAP.md`,
`features/README.md`, `features/BACKLOG.md`, CR-020, CR-023, BRD v1.0 sections 1, 5, 6 and Appendix A,
plus the F07 stage specs and the F08/F10 feature definitions to confirm what R1 actually builds.
Nothing in the repository was changed.

## How to read this

Evidence labels used throughout:

- **[V]** verified on a vendor's own page that I fetched in this session (URL in "Sources").
- **[V-search]** seen only in a search-result summary of a vendor or help-centre page; the page itself was not opened.
- **[3P]** third-party or vendor-written-about-competitors source; treat as directional.
- **[GK]** my general knowledge, not verified in this session.

Vendor statistics are the vendor's own claims. No independent study of booking-channel share was found.

### Web tool failures and gaps (not filled from memory)

| What failed | Effect |
| --- | --- |
| `fresha.com/for-business/features/online-booking` returned 404 | Used Fresha's pricing page and scheduling page instead |
| Fresha "Get booked on Reserve with Google" help article returned 404 | Fresha's country eligibility for Google bookings not confirmed |
| Google's Reserve with Google help page shows no country list | **Sri Lanka availability is unconfirmed.** A third-party list of 17 countries (Appointy) does not include it |
| Phorest branded-app feature page returned 404 | Phorest app-versus-web positioning taken from its pricing page only |
| Timely pricing and features pages returned only a page title (script-rendered) | **Timely is covered only by third-party pricing guides and its help-centre titles**; its feature set is mostly [GK] |
| Square Appointments pricing page showed tiers but no prices and few features | Square detail is partial; Instagram/Google booking and AI for Square not confirmed |
| Searches for Mangomint, Phorest and Square double-booking behaviour found no vendor page | Double-booking verified only for Fresha, Vagaro and Zenoti |
| Fresha Payments country list not found | Whether Fresha can take online deposits in Sri Lanka is unconfirmed |
| Mindbody | Pricing page fetched; little salon-specific depth. Mindbody is fitness-first |

---

## 1. What the market ships, by capability area

"Table stakes" = nearly all eleven leaders; "common" = most; "differentiator" = few.

### a. Booking and calendar

| Capability | Market position | Evidence | Our register |
| --- | --- | --- | --- |
| Several services in one appointment for one client | Table stakes | Fresha: "To add more services to the appointment, click the Add service button" [V]. Others [GK] | **Nowhere.** F07-S02 lists "Several services in one booking" as out of scope; BKG-19 (R2) covers only the multi-professional case |
| Multi-staff appointment | Common | Square "multi-staff appointment booking" on Plus [V]; Fresha per-service team member [V] | BKG-19, R2 |
| Processing / finishing time | Table stakes for hair | Fresha lists "processing and finishing times" as a core feature on both LKR plans [V]; Mangomint (CR-023 source) | CAT-12, R2 (engine already stores segments, decision 074) |
| Resources / rooms | Common | Fresha core [V]; Vagaro "calendar and resource management" included [V]; Square Premium [V]; Mindbody Accelerate+ [V] | CAT-09, R2 |
| Recurring appointments | Table stakes on the staff calendar | Fresha "Create repeat appointments", scheduled up to 12 months ahead [V-search]; Square [3P Capterra] | BKG-21, **R3 could** |
| Group bookings | Common | Fresha core, "one fast, unified payment" [V] | BKG-22, R2 |
| Staff double-booking with a warning | Common | Fresha: "you will be able to double book appointments within the same time slot" [V]; Vagaro "double- or triple-book when needed" [V-search, app listing]; Zenoti provider-level toggle [V-search]; Square shows a warning [3P community] | **BKG-05 forbids it absolutely**; CR-020 correction 3 says staff "never override hard conflicts" |
| Waitlist | Table stakes; automatic matching is common | Fresha automated, "first in line, high-value first, or offer to all" [V]; Vagaro, Booksy, Square Plus, GlossGenius Gold [V] | BKG-17 manual R1; BKG-18 automatic R2 |
| Walk-in queue with estimated wait | Differentiator among the leaders | Only Zenoti's kiosk among the eleven [V-search]; common in barber/walk-in tools (Meevo, DaySmart, SalonIQ) [V-search] | BKG-10, R1 must |
| Walk-in appointment with no client details | Table stakes | Fresha: staff can "leave the appointment as a Walk-in" [V] | **BKG-11 requires a mobile number**; F07-S03 AC-4 requires name and mobile for every source including Walk-in |
| "Any professional" | Table stakes | Fresha assignment settings [V-search] | BKG-03, R1 |
| Drag-and-drop, colour coding | Table stakes | [GK] | BKG-12 (drag); colour coding not named |
| Appointment notes | Table stakes | Fresha: "To add notes, forms, or apply payment policies to an appointment click Actions" [V] | **No requirement.** CRM-03 is notes on the customer; F07 has only a cancellation note |
| Lead time, cut-off, horizon | Table stakes | [GK] | BKG-02, R1 |
| Blocked clients | Common | [GK] | CRM-08, R2 |
| Shift scheduling by date, repeating patterns | Table stakes | Fresha "shift scheduling" [V] | STF-02 weekly pattern plus overrides; rotas are only a backlog idea (SUG-F06-05) |
| Staff calendar sync (Google) | Common | Square two-way sync on Plus/Premium [V-search] | Not in register (APP-05 is the customer's device calendar) |

### b. No-show protection

| Capability | Market position | Evidence | Our register |
| --- | --- | --- | --- |
| Deposits and cancellation policy | Table stakes | Fresha "card confirmation or a deposit" [V]; Square Plus "cancellation policy and no-show fees" [V]; Booksy [V] | BKG-09, PAY-01, PAY-09 R1 |
| Card on file and late fees | Table stakes globally | As above | PAY-12, R2 (PayHere limits, CR-023) - reasonable locally |
| Reminders | Table stakes | All [V] | NOT-03 R1 |
| Two-way confirmation | Common | "Reply C to confirm" verified for DaySmart and Rosy only [V-search]; leaders [GK] | NOT-03 "links to confirm, reschedule or cancel" - **the links have no destination in M1** (see 3.2) |

### c. POS, checkout and finance

| Capability | Market position | Evidence | Our register |
| --- | --- | --- | --- |
| Split tender, discounts, refunds | Table stakes | Fresha (CR-023 sources) | PAY-05, PAY-06 R1 |
| Tips | Table stakes | Fresha lists tips on both LKR plans [V] | PAY-13, **R3 could** |
| Retail with stock tracking | Table stakes | Fresha "retail and inventory" core [V]; Vagaro "inventory tracking" included [V]; GlossGenius Standard [V]; Mangomint [V] | INV-01 R1 (no stock); INV-02 **R3** |
| Printed receipts | Table stakes | Fresha "Share and print sale receipts", receipt printers [V-search] | **PAY-06 issues receipts by WhatsApp, SMS or email only** |
| Sale without an appointment | Table stakes | Fresha "Raise a sale" (already cited by CR-023) | Not stated; PAY-06 is checkout of a visit |
| Cash drawer / day-end close | Common | Mangomint (CR-023) | PAY-10 R1 should |
| Commission | Table stakes for teams | Fresha Team plan: sales commissions, wages, pay runs, timesheet clock-in/out [V]; Phorest all plans [V]; GlossGenius Platinum [V] | STF-08 R2 (unowned candidate); REP-08 R3 |
| Payroll processing | Differentiator, US-centric | Mangomint $50/mo + $8/worker; GlossGenius $40/mo + $6/seat [V] | Not planned - correct |
| Memberships | Common | Fresha core; Vagaro; Booksy; Mangomint; GlossGenius "NEW" [V]; Phorest add-on [V] | MKT-08, R3 could |
| Packages / prepaid series | Table stakes | Same sources | CAT-10, R2 |
| Gift cards | Table stakes | Same sources | MKT-09/10, R2 |
| Loyalty | Common, often paid | Fresha add-on LKR 6,050/location/month [V]; Vagaro included [V]; Phorest TreatCard [V] | MKT-06, R2 |
| Accounting integration | Common as an add-on | Fresha Xero LKR 3,099/location [V]; Boulevard QuickBooks $45/location [V] | Not in register (CSV only, NFR-19) |

### d. Client management

Profiles, history, notes, tags, duplicate merge: table stakes; we match in R1 (CRM-01 to CRM-05).
Consultation forms and patch tests are in Fresha's core plans [V], Booksy's base plan [V], GlossGenius Gold [V],
Phorest all plans [V], Boulevard as a $65 add-on [V]. Ours are R2 (CRM-06, APP-18, CAT-11). Formulas and
before/after photos are table stakes for colour work [GK]; ours are R2 (CRM-06).

### e. Marketing and growth

Campaigns and automated flows are included broadly: Fresha "marketing tools, deals and promotions" [V],
Vagaro [V], Mangomint "Automated Flows, Campaigns" [V], Boulevard "Automated Campaigns (free to send)" [V],
Booksy 2,000 free SMS a month [V]. Ours: MKT-03 and MKT-04 are R2 and have **no feature number**
(BACKLOG "Customer campaigns"). Reviews: Fresha core [V]; ours R2, unowned. Google review prompt: MKT-02 R1 - good.
Booking from Google, Instagram and Facebook: Fresha [V], Booksy [V], Vagaro (plus Apple Maps) [V], Zenoti [V],
Phorest Reserve with Google from Grow [V]. Marketplace: Fresha, Booksy, Vagaro, Mindbody [V]; we deliberately have none.

### f. Staff

Roles and permissions: table stakes; ours IAM-07 R1. Shifts: table stakes. Time clock: common (Fresha Team,
GlossGenius Gold, Phorest all plans [V]); ours STF-09 R3. **PIN switching on a shared front-desk device: common** -
Fresha "Set up and manage PIN switching" [V-search], Timely "PIN switching" [V-search], Square [3P community],
Zenoti [V-search]; ours: nothing. **Native staff app: table stakes** - Fresha "Business app for professionals"
on iOS and Android [V], Vagaro Pro [V-search], Mangomint "Mobile Apps" [V]; ours is responsive web only (STF-06).

### g. Reporting

Zenoti's 2025 Europe benchmark is built on online-booking share, rebooking rate and utilisation [3P, Professional
Beauty]: salons 77% online (top earners) against 40% (median); rebooking 41% against 24%. Our R1 reports
(REP-01 to REP-04, REP-10) cover revenue, source, service, professional, no-show rate and tender. Rebooking rate,
occupancy and new-versus-returning are R2 (REP-05). **Average ticket and client retention rate appear nowhere.**
Tax, discount and refund reports are R2 (REP-06) although tax, discounts and refunds all exist in R1.

### h. Multi-location

Common above the entry tier, priced per location (Mangomint, Boulevard, GlossGenius [V]). Franchise features
(royalties, centralised staff) are Zenoti's differentiator [3P, Zenoti's own comparison]. Ours PLT-11 R2 with
branch scope in the R1 data model - well placed.

### i. Onboarding

Self-serve sign-up with a trial at the low end: Fresha 7 days, Booksy 14, Vagaro 30 [V]. Done-for-you data
migration is included by Mangomint ("free data transfer"), GlossGenius ("we'll transfer your appointments, client
info, and more"), Phorest ("setup, data migration, and training" on all plans) and Mindbody [V]. Ours: operator
creates the tenant (PLT-01), guided checklist (PLT-02), self-serve spreadsheet import (PLT-08).

### j. Customer-facing channels - see 3.1

### k. AI shipped 2025 to 2026

| Vendor | What | Evidence |
| --- | --- | --- |
| Fresha | AI Concierge add-on, messaging LKR 6,600 per location a month; calls "coming soon" | [V] LKR pricing page |
| Vagaro | "Vera AI" assistant included in every plan | [V] |
| Mangomint | "Mira" AI receptionist for calls and texts | [V] promoted on pricing page |
| Boulevard | "AI Receptionist", "Boulevard AI" | [V] listed, no price |
| Phorest | Front Desk AI add-on; AI Client Cheat Sheet | [V] |
| GlossGenius | Growth Analyst (AI queries, 20 a month or unlimited); Reception add-on $50 a month | [V] |
| Booksy | AI Receptionist, beta | [V] |
| Mindbody | AI Concierge "24/7 AI front desk", add-on or Ultimate | [V] |
| Zenoti | AI Receptionist, Zeenie, Smartbot AI | [3P, Zenoti's own] |
| Square, Timely | Not found | - |

By late 2026 an AI receptionist is **common** (nine of eleven) and almost always a paid add-on. Our AST-01 to
AST-06 at R3 could is a reasonable position. The larger AI effect is on discovery: Fresha says AI and LLM
referrals are 25% of online booking referrals in APAC and growing 50% month on month, and that Google Search,
Maps and Gemini deliver 1.2 million appointments a month [V, Fresha press release, 24 Feb 2026]. Those referrals
land on web booking pages. An app-only salon cannot receive them.

---

## 2. MISSING - in neither requirements.yaml nor BACKLOG.md

Ranked by how soon a paying salon would hit it.

| # | Capability | Who has it | Why it matters | Suggested band |
| --- | --- | --- | --- | --- |
| 1 | **Several services in one booking for one professional** (cut, colour, blow-dry as one appointment with one time block, one deposit, one checkout) | Fresha [V]; all others [GK] | The ordinary hair appointment is two or three services. F07 holds "one professional, one service" (S01 AC-1) and S02 puts several services out of scope. BRD 6.1 filed it under "multi-service, multi-professional, R2" and only the multi-professional half got an id (BKG-19) | **R1 must**, before F10 and F15 |
| 2 | **A no-install way for a customer to act on a booking** (confirm, cancel, reschedule from a reminder; see a payment result) | All eleven | NOT-03, BKG-07, BKG-08 and BKG-17 are R1 but have no customer surface until F15, and decision 072 rejected guest lookup. See 3.1 and 3.2 | **R1 must** (web link or WhatsApp reply buttons) |
| 3 | **Printed receipt** (browser print to A4 or 80 mm thermal) | Fresha [V-search] | Cash-paying walk-ins without WhatsApp; VAT-registered salons | R1 should |
| 4 | **Appointment note** shown on the calendar and "my day" | Fresha [V] | "Wants fringe kept long", "running late" - different from a customer note (CRM-03) | R1 should |
| 5 | **Sale with no appointment** (retail only) and **walk-in with no customer details** | Fresha [V] | INV-01 puts products in R1 but checkout is tied to a visit; BKG-11 forces a mobile number | R1 should |
| 6 | **Staff override of a conflict with a warning** (audited, permission-gated) | Fresha [V], Vagaro, Zenoti [V-search] | See 3.3 | R1 decision; build with CAT-12 |
| 7 | **PIN switching on a shared front-desk device** | Fresha, Timely, Zenoti [V-search]; Square [3P] | IAM-11 audit, IAM-14 role limits and PAY-10 "by staff member" all assume the acting person is known; with one reception PC and password plus two-step sign-in (IAM-06), staff will share the Owner's session | R2 should (R1 could) |
| 8 | **Staff push notifications / installable staff app** | Fresha, Vagaro, Mangomint [V] | NOT-07 lists staff notifications but no channel that reaches a stylist's phone; STF-06 is a web page | R1: define NOT-07's channel (WhatsApp or web push). Native app R3 |
| 9 | **Rebook at checkout** (prompt for the next visit when completing) | Phorest, Zenoti emphasise rebooking rate [3P] | Top salons rebook 41% against 24% median; APP-03 "book again" is customer-side only | R1 could (small) |
| 10 | **Average ticket and client retention rate** | Standard KPIs [3P Zenoti benchmark] | Owners compare these first | Add to REP-05 |
| 11 | **Self-serve sign-up** | Fresha, Booksy, Vagaro [V] | PLT-01 is operator-only. Fine for pilots; a ceiling against a LKR 1,790 self-serve competitor | R2 should |
| 12 | **Migration of visit history, notes and prepaid balances; staff and product import** | Mangomint, GlossGenius, Phorest, Mindbody include a migration service [V] | PLT-08 imports customers, services and future appointments only. A switcher loses each client's history | R1: offer it as an operator service; R2 for the import types |
| 13 | **Accounting export in an accountant's format / Xero or QuickBooks** | Fresha, Boulevard add-ons [V] | An Accountant role exists (IAM-07) with nothing to export to | R3 could |
| 14 | **Staff calendar feed** (Google/iCal, read-only) | Square [V-search] | Professionals without a sign-in (STF-01) cannot see their day | R3 could |
| 15 | **Offline or degraded-connection behaviour for the staff workspace** | Not a feature of the global leaders [GK]; local competitors lead with offline POS (CR-023's own finding) | APP-08 covers only the customer app. A printable day sheet is the cheap answer | R2 could |

---

## 3. WRONG or QUESTIONABLE

### 3.1 No customer web booking, and no customer surface at all until F15 (APP-06, IAM-02, MKT-01, BRD-03, BRD-09, BRD-12, BKG-16, OCC-15; CR-010)

**How unusual: unique.** None of the eleven requires an app install to book.

| Vendor | Browser booking without install | Evidence |
| --- | --- | --- |
| Fresha | "direct booking links, Facebook and Instagram bookings (free)"; "website, social media, Google, or the Fresha Marketplace" | [V] |
| Booksy | Clients can book "without downloading an app"; Google Search, Maps, Instagram, Facebook | [V] |
| GlossGenius | "login-free client booking"; "no app downloads required" | [V] |
| Zenoti | "Clients don't need to download anything"; the browser portal is what "the majority of Zenoti salons use"; the branded app is for "larger salon groups and established brands" | [V] |
| Vagaro | Booking widget; Vagaro, Instagram, Facebook, Apple Maps; branded app is an extra | [V] |
| Square | "online booking site" on the entry tier | [V] |
| Mindbody | "Branded web booking widgets" in every plan; branded mobile app is an add-on from Accelerate | [V] |
| Phorest | Online booking in all plans; branded app is an optional add-on below Elite | [V] |
| Mangomint | Online booking and web chat included; no client app named | [V] |
| Boulevard | "Self-booking" listed | [V] |
| Timely | Web booking | [GK] - pages did not load |

The two vendors that sell salon-branded apps (Phorest, Mindbody) and the enterprise one (Zenoti) all keep web
booking as the default and position the app as an upgrade for established brands. That is the opposite of our order.

**Our own BRD said the same.** BRD v1.0 section 5.5: "The research supports online booking strongly; it does not
show that customers demand a separate app per salon. The booking site must therefore be a first-class channel."
Section 1 item 2 and D-01 recommended "a branded booking site and a place inside one shared container app from
day one". CR-010 removed the site; the register now says "app links have no customer website fallback" (APP-06)
and "No customer booking website or browser booking fallback is provided" (BRD-12).

**How risky.**

- Share of bookings that are online: 77% of appointments for top-earning European salons, 40% for the median
  (Zenoti Europe benchmark, 2025 data) [3P]. After hours: 32% (Booksy) [V]; 46% and "only 54% in opening hours"
  (Phorest, already in our BRD). While the stylist is with a client: 64% (Booksy) [V]. Vendor claims, consistent
  with each other.
- Channel split inside "online" (web link, Instagram, Google, app): **no reliable figure found.** Signals: Fresha's
  Google integration alone carries 1.2 million appointments a month across 30,000 businesses [V]; one Fresha case
  study attributes at least 80% of a salon's bookings to the Instagram Book button [V-search]; Phorest claims a
  branded app adds only about 4% to online bookings [V-search].
- During all of M1 (F01 to F14) the product has **no online booking of any kind**. The category's main promise -
  bookings while the salon is closed or busy - arrives only with F15, and then only for customers who install an app.
- "Book" buttons on Instagram and Facebook and Reserve with Google are partner integrations that open a web flow
  [V for Fresha, Booksy, Vagaro, Zenoti offering them; mechanism GK]. Our MKT-01 link can only open an app or a
  store listing. Links opened inside Instagram's in-app browser are a known weak point for app deep links [GK].
- AI and search referrals (section 1.k) need a crawlable web page.
- Reserve with Google is "only available in certain countries/regions" [V, Google]; Sri Lanka is unconfirmed, so
  the "Google" channel in BKG-16 is, for now, a plain link on a Business Profile.

**Recommendation.** Put a change request to the owner to reverse or narrow CR-010: a mobile-web booking flow on
the salon's subdomain, using the same APIs as F15, verification by one-time code at "Your details". If the owner
keeps CR-010, the minimum is (a) web pages for confirm, cancel, reschedule and payment result reached from message
links, and (b) a WhatsApp-native path (reply buttons) as the no-install channel. PAY-09 already sets the precedent
of a "narrow exception to CR-010" (decision 079).

### 3.2 R1 requirements that need a customer surface which M1 does not have (NOT-03, BKG-07, BKG-08, BKG-17)

NOT-03 (F11, R1): "Reminders include links to confirm, reschedule or cancel." BKG-17 (F09, R1): "the customer
must confirm." BKG-07 and BKG-08: customer reschedule and cancel. In M1 there is nowhere for these to land, and
after F15 only app users can use them. Either re-word them as staff-only until F15 or add the web links of 3.1.
A reminder that cannot be answered removes most of its no-show value.

### 3.3 BKG-05: "Two bookings or holds can never overlap for the same professional" with no staff override

Correct for customers and holds. As an absolute rule for staff it contradicts how the leaders work: Fresha lets
staff double-book from the calendar [V]; Vagaro advertises double- and triple-booking [V-search]; Zenoti has a
per-provider toggle [V-search]. Combined with CAT-12 being R2, an R1 colourist cannot take a cut while a colour
processes, and a late-running day cannot be squeezed. CR-020 correction 3 hardened this ("never override hard
conflicts") without market evidence. Suggest: keep the database guarantee for online bookings and holds; allow a
permission-gated, audited staff override, or pull processing time into R1 so the legitimate overlap is modelled.
The exclusion constraint is already built in F07-S01, so this needs a refactor decision before F10.

### 3.4 BKG-11: "a mobile number is required" for every staff-created booking

F07-S03 AC-4 requires name and mobile for all sources, including Walk-in. Fresha allows a walk-in with no client
[V]. Staff will type dummy numbers, which then pollute duplicate detection (CRM-02) and message cost (NOT-05).
Require the number only when a confirmation or deposit link is to be sent.

### 3.5 BRD-09 and IAM-03: a shared container app that shows only one salon

No leader uses this shape for independent salons. Marketplace apps (Fresha, Booksy, Vagaro, Mindbody) trade the
install for discovery; branded apps (Phorest, Mindbody, Zenoti) trade it for the salon's own icon. Ours has the
install cost of the first and the benefit of neither, under an operator brand that does not yet have a name. This
is analysis, not a verified market fact. It strengthens the case for web first, app second.

### 3.6 Internal contradictions found while comparing

- STF-08 (R2) says "with tips kept separate"; tips are PAY-13 (R3).
- STF-08 (R2) includes "a commission report"; REP-08 "Staff performance and commission" is R3.
- CAT-05 tax, PAY-06 discounts and PAY-05 refunds are R1; their reports are REP-06 (R2).
- BRD 5.5 still cites a booking site as first-class while the register forbids it.

### 3.7 Lower-confidence questions

- BKG-06 "cancellation is only before the start time": a customer who phones five minutes late to cancel becomes
  a no-show, which feeds BKG-20 risk rules. Not verified against the market.
- PLT-01 operator-only tenant creation (see Missing 11).
- PAY-02 requires each salon to hold its own PayHere merchant account before any deposit. Competitors embed
  payments [V for Booksy and Mangomint processing rates; V-search for Fresha Payments; onboarding speed is GK].
  Probably unavoidable locally; expect
  it to be the slowest onboarding step.

---

## 4. MIS-SCHEDULED - we have it, later than the market treats as launch-critical

| # | Requirement | Now | Market | Suggestion |
| --- | --- | --- | --- | --- |
| 1 | PAY-13 Tips | R3 could | Fresha lists tips on both plans [V] | R1 should, inside PAY-06. Without it tips distort the PAY-10 cash count |
| 2 | CAT-12 Processing time | R2 | Fresha core [V] | Staff side to R1; the engine already honours segments |
| 3 | BKG-21 Recurring appointments | R3 could | Fresha repeat up to 12 months [V-search] | R2 should, staff-created only |
| 4 | INV-02 Stock levels | R3 could | Fresha, Vagaro, GlossGenius, Mangomint include inventory [V] | R2 should; INV-01 already sells products in R1 |
| 5 | STF-08 Commission; REP-08 | R2 unowned; R3 | Fresha Team, Phorest all plans [V]; local rivals lead with it (CR-023) | Give it a feature number at the head of M3 |
| 6 | REP-06 Tax, discount, refund reports | R2 unowned | The data exists in R1 | R1, into F13 |
| 7 | MKT-03, MKT-04 Automated rebooking, win-back, campaigns | R2 unowned | Included broadly [V] | Give it a feature number ahead of F19 to F22 |
| 8 | CRM-06 Formulas, photos, patch tests; APP-18 forms | R2 (F17) | Forms and patch tests in Fresha core, Booksy base [V] | Keep R2 but early; allow a photo and a pinned formula note in R1 |
| 9 | REP-05 Occupancy, rebooking rate, new versus returning | R2 unowned | Core benchmark KPIs [3P] | R1 should for rebooking rate and occupancy |
| 10 | STF-09 Timesheets | R3 could | Fresha Team, GlossGenius Gold, Phorest [V] | R2 could, with commission |
| 11 | MKT-08 Memberships | R3 could | Common [V] | R2 could; lower priority than packages locally |
| 12 | BKG-18 Automatic waitlist offers | R2 unowned | Fresha automated [V] | Needs the customer surface of 3.1 first |

Correctly scheduled against the market: resources (CAT-09), group bookings (BKG-22), gift cards (MKT-09/10),
packages (CAT-10), loyalty (MKT-06), multi-branch (PLT-11), saved-card fees (PAY-12), AI (AST-*), payroll (none).

---

## 5. OVER-SCOPED in R1

Only unbuilt work is worth trading; F01 to F07 are done.

| Item | Why it could wait | Trade for |
| --- | --- | --- |
| BKG-10 queue "with an estimated wait" (F10) | Only Zenoti among the leaders has it; keep "start now" and "next free time", defer the queue and the estimate. The BRD calls it a local differentiator, so this is the owner's call | Multi-service bookings |
| BKG-17 waitlist confirmation flow (F09) | No customer surface in M1; a staff list is enough | Web manage-booking links |
| PLT-08 import of **future appointments** (F14) | Hardest import type; vendors do it as a service [V] | Operator-run migration |
| PAY-10 float, pay-ins and pay-outs, variance (F10) | "Should"; REP-10 by tender covers most of it. Defensible in a cash market - lowest-priority cut | Tips, printed receipts |
| NOT-08 notification centre; NOT-07 daily agenda (F11) | Nobody would miss them at launch | Staff push channel |
| CAT-06 "Popular" derived from volume | Manual pick is enough | - |
| BRD-09 container app, APP-11, APP-10, IAM-05, IAM-10 (F15) | Needed only because the first customer surface is a store app | A web flow needs none of them |
| Already built, noted only: desk booking as hold-then-confirm (BKG-04 applied to staff), PLT-05 billing automation, PLT-07 support sessions, BRD-01 draft and publish, NFR-22 to NFR-24 enforcement | Sunk cost; leaders let staff simply save an appointment | - |

---

## 6. What a salon owner switching from Fresha would notice missing on day one of our R1

Fresha's LKR plans (LKR 1,790 independent; LKR 1,190 per team member) include everything below except where marked [V].

1. Online booking from a link, Instagram, Facebook or Google with no install - and during M1, any online booking at all.
2. Booking a cut and a colour as one appointment.
3. Processing time, and the freedom to double-book.
4. Repeat appointments.
5. Tips at checkout.
6. Stock levels for retail.
7. Commission, wages, pay runs and timesheets (Fresha Team plan).
8. Consultation forms and patch tests.
9. Gift cards, packages and memberships.
10. Marketing campaigns, deals, and client reviews; loyalty (a paid Fresha add-on).
11. Resources and group bookings.
12. The iOS/Android business app and PIN switching.
13. Printed receipts and a sale without an appointment.
14. A walk-in without taking a phone number.
15. Automatic waitlist offers.
16. Multiple locations.
17. Marketplace discovery (deliberate).

What they gain: deposits by PayHere link, LANKAQR and bank-transfer tenders, the Poya calendar, a cash-drawer
close, WhatsApp-first messaging with fallback and visible cost (Fresha charges LKR 19 to 95 per WhatsApp message
after 20 free, and LKR 537 per marketing text [V]), no marketplace fee, and their own brand.

---

## Sources

Vendor pages fetched:

- Fresha pricing, Sri Lanka (LKR): https://www.fresha.com/pricing
- Fresha scheduling: https://www.fresha.com/for-business/features/scheduling
- Fresha, create appointments: https://www.fresha.com/help-center/knowledge-base/calendar/260-create-appointments-1
- Fresha AI and Google press release (24 Feb 2026): https://www.fresha.com/blog/fresha-ai-booking-growth
- Vagaro pricing: https://www.vagaro.com/pro/pricing
- Mangomint pricing: https://www.mangomint.com/pricing/
- Square Appointments pricing: https://squareup.com/us/en/appointments/pricing
- GlossGenius pricing: https://glossgenius.com/pricing
- Boulevard pricing: https://www.joinblvd.com/pricing
- Booksy pricing: https://biz.booksy.com/en-us/pricing
- Booksy online booking: https://biz.booksy.com/en-us/features/online-booking
- Phorest pricing: https://www.phorest.com/us/pricing/
- Mindbody pricing: https://www.mindbodyonline.com/business/pricing
- Zenoti salon booking app: https://www.zenoti.com/salon-management-software/salon-booking-app
- Zenoti comparison (vendor-written): https://www.zenoti.com/thecheckin/best-salon-management-software-2026
- Google, Reserve with Google: https://support.google.com/reserve/answer/9172607?hl=en

Seen in search results only:

- Fresha repeat appointments: https://www.fresha.com/help-center/knowledge-base/calendar/261-create-repeat-appointments-1
- Fresha PIN switching: https://www.fresha.com/help-center/knowledge-base/personal-account/234-set-up-and-manage-pin-switching
- Fresha print receipts: https://www.fresha.com/help-center/knowledge-base/sales/171-share-and-print-sale-receipts
- Fresha Instagram case study: https://www.fresha.com/blog/case-study-how-citryne-rose-gets-5x-more-bookings
- Zenoti overlapping appointments: https://help.zenoti.com/en/appointments/onboard-and-set-up/bookings/service-rules/book-overlapping-appointments-for-providers.html
- Zenoti kiosk: https://zenoti.com/kiosk-management
- Vagaro Pro app listing: https://apps.apple.com/app/id346778559
- Timely PIN switching: https://help.gettimely.com/hc/en-gb/articles/38617519054871-Introduction-PIN-switching
- Square Google Calendar sync: https://squareup.com/au/en/appointments/features/google-calendar-sync
- Square community, overlapping warning: https://community.squareup.com/t5/Archived-Discussions-Read-Only/Does-not-show-a-conflict-if-overbooking-an-appointment/td-p/81251
- DaySmart text confirmations: https://desktophelp.daysmartsalon.com/en/articles/9301713-text-message-confirmations-overview
- Appointy, Reserve with Google countries: https://help.appointy.com/hc/en-us/articles/360032230251-Which-countries-are-currently-supported-by-Reserve-with-Google
- Phorest online booking overview: https://phorest.helpjuice.com/onlinebooking/online-booking-overview

Third party:

- Professional Beauty on Zenoti's Europe benchmark (9 June 2026): https://professionalbeauty.co.uk/digital-booking-ai-tools-salon-business-growth-europe-benchmark-report
- Salon Today, Phorest booking data: https://www.salontoday.com/603348/data-confirms-demand-for-online-booking
- Pabau, Timely pricing guide: https://pabau.com/blog/timely-pricing/
- Capterra, Square Appointments: https://www.capterra.com/p/170263/Square-Appointments/
