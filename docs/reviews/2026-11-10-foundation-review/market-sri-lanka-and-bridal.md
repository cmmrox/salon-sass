# Market research: Sri Lanka, bridal and the white-label app model

Date: 2026-11-10. Scope read: `docs/brd/requirements.yaml`, `docs/ROADMAP.md`, `features/README.md`,
`features/BACKLOG.md`, `docs/qa/review-context.md`, CR-020, CR-023, F08/F11/F16/F19/F20 definitions,
`docs/architecture/tech-stack.md`, decision log 071-082, BRD text (sections 5, 6, 7.4, 7.7, 10, 13, 14).
Nothing in the repository was changed.

## How to read the evidence

| Mark | Meaning |
| --- | --- |
| **[V]** | Read on the source page in this session (URL given) |
| **[S]** | Secondary: a search-result summary or third-party page, not the primary source |
| **[GK]** | General knowledge, not checked on a page in this session |

Web tool failures and gaps (not filled from memory):

- `info.salobook.lk` (DNS failure) and `salobook.lk` (connection refused): SaloBook facts are from a search snippet only.
- `kocofay.com` (DNS failure), WedMeGood profile (HTTP 403), Check Cherry industry page (404): terms quoted from these are search snippets only.
- No primary TRCSL text on promotional SMS, no primary IRD/Gazette text, and the PDPA Gazette 2498/16 PDF itself was not readable; three independent secondary sources agree on it.
- Not found at all: Koko/Mintpay merchant fees, whether Fresha card payments work in Sri Lanka, published Sri Lankan salon commission norms, a source describing the bank-transfer-slip habit in salons specifically, Sri Lankan law on non-refundable advances.
- Zylu, MioSalon, DINGG, Invoay: only aggregator USD prices surfaced; no evidence they sell actively in Sri Lanka. Treated as marginal.

---

## 1. Findings ranked

| # | Finding | Type | Band |
| --- | --- | --- | --- |
| 1 | PDPA controller/processor duties start 1 January 2027; the BRD says "awaiting commencement" | Compliance, wrong | R1 |
| 2 | PayHere cannot do what PAY-05/PAY-09 assume for most salons: full refunds only, cards only, Lite caps, per-salon API key and IP whitelisting | Wrong / risky | R1 |
| 3 | No manual deposit path (bank transfer / LankaQR) for a future booking; LankaQR is now fee-free up to Rs 5,000 | Missing | R1 |
| 4 | WhatsApp pricing changed on 1 October 2026: in-window utility and service messages are no longer free; Sri Lanka got its own, much lower utility rate; marketing is ~36x utility | Wrong + opportunity | R1 |
| 5 | SMS sender IDs must be registered (blocking from 29 July 2026), are not dynamic, and replies are impossible | Missing | R1 |
| 6 | M1 reminders are one-way: NOT-03's confirm/reschedule/cancel links only arrive with F15 | Missing | R1 |
| 7 | App-only customer booking is an outlier; every branded-app vendor and the local entrant also give a web link. A landing page is technically unavoidable anyway | Roadmap risk | R1/M2 |
| 8 | Shared container app with no picker risks Apple 3.2/2.1/4.2 review problems; cold-start and deferred deep link are unspecified; D-02 (name) blocks store, WhatsApp and SMS identities | Missing / risk | M2 |
| 9 | Dedicated apps: sole proprietors show the owner's personal name as seller; organisation enrolment needs a real website, which we do not give them | Wrong assumption | R2 |
| 10 | Bridal: quote and enquiry need a link, not an app (one-off customers, payer is often a parent); F19 ships before F20 | Mis-scheduled | R2 |
| 11 | Bridal: work happens outside opening hours and beyond the booking horizon (early-morning dressing, dates 6-12 months out) | Missing | R2 |
| 12 | Bridal: tentative date holds, bundled pre-bridal treatments, jewellery/saree items, headcount changes, surcharges, off-site collection | Missing | R2 |
| 13 | VAT tax-invoice format mandatory from 1 October 2026; thresholds in flux | Compliance | R1 |
| 14 | Local rivals lead with offline billing, thermal printing, Sinhala UI, commission and payroll; ours are R2-unowned or absent | Mis-scheduled / missing | R1-R2 |
| 15 | Bridal is the differentiator (no rival goes past a six-person group booking) but is last in the roadmap and chained behind multi-branch | Roadmap risk | - |

---

## 2. (A) Sri Lanka / South Asia

### 2.1 What a Sri Lankan salon chooses between today

| Product | What was found | Evidence |
| --- | --- | --- |
| Fresha (LKR) | Independent LKR 1,790/month; Team LKR 1,190 per team member/month. Add-ons: Client Loyalty LKR 6,050/location, AI Concierge LKR 6,600/location, Team Connect LKR 995/member, Insights LKR 1,150/member, Smart Website LKR 3,300. Automated text/WhatsApp: 20 free a month, then LKR 19 to 95 per message. Direct booking links, Facebook and Instagram bookings are free. The page does not say card payments work in Sri Lanka | [V] https://www.fresha.com/pricing |
| PopitCart (Axcertro, Sri Lanka) | Cloud POS for salons: calendar booking, automated stylist commission splits, package vouchers, SMS reminders, receipts by WhatsApp/SMS, loyalty. Rs 2,500 / 5,000 / 8,000 a month plus Rs 50,000 set-up. Offline billing during power cuts; Pro has a standalone offline Windows POS, payroll, cash advances, commissions. Sinhala and English billing UI. 58/80 mm thermal printers, barcode scanners, cash drawers, hardware bundles with on-site set-up. No online booking page or bridal feature mentioned | [V] https://popitcart.com/ |
| SaloBook (Colombo) | Early access, "no per-staff pricing", an online booking page, automated SMS and email reminders (no WhatsApp mentioned), no published price | [S] search snippet of https://info.salobook.lk/ (site unreachable) |
| Local web agencies | Custom booking and payments build from about LKR 600,000 | [S] https://iasoft-dev.pages.dev/digital/appointments |
| Zylu, MioSalon, DINGG | Aggregator prices only: Zylu from US$19, MioSalon about US$24, DINGG US$49-149 a month | [S] https://www.capterra.com/p/247991/Zylu/pricing/ , https://www.saasworthy.com/product/dingg-app/pricing |

Reading:

- The two real alternatives pull in opposite directions. Fresha gives a **free customer booking link** and a full feature list. The local POS gives **offline, printing, Sinhala, commission and payroll**. Our M1 has neither group's headline strength: no customer self-booking (CR-010), and no offline mode, printed receipt, Sinhala UI (PLT-15, R2 unowned) or commission (STF-08, R2 unowned).
- Messaging is a price weapon we are not using. Fresha charges LKR 19-95 per automated message after 20 free. Our cost is about LKR 0.6 per WhatsApp utility message and LKR 0.64-0.84 per SMS (section 2.3). A generous allowance in PLT-04/PLT-10 costs little and is directly comparable.

### 2.2 Payments

**PayHere, verified on its own pages**

| Fact | Source |
| --- | --- |
| Lite: free, 3.30%, **Rs 50,000 per payment, Rs 200,000 a month**, one-time payments only. Plus: Rs 3,990/month, 2.99%, Rs 250,000 per payment, Rs 3,000,000 a month, recurring billing and "hold on card". Premium: Rs 9,990/month, 2.69%, Rs 1,000,000 per payment, no monthly limit, automated charging (saved cards). Amex/Diners +0.5%, foreign currency +1.0% | [V] https://www.payhere.lk/fees/ , https://support.payhere.lk/limits-and-fees |
| Over-limit payments fail with an "Over Limit payment Error" until the cycle ends | [V] https://support.payhere.lk/limits-and-fees |
| "PayHere supports only full refunds." Partial refunds must be done manually. Only card payments can be refunded; Frimi, Genie, eZcash, mCash and Vishwa cannot. Same-day refund is a void; after payout a refund takes 5-10 days and is recovered from the merchant's balance or future payouts, waiting in "Waiting funds" if short | [V] https://support.payhere.lk/refund-process |
| The Refund API page shows an `amount` field "for partial refunds", contradicting the support page. Live use needs an API key with the "Automated Charging API" permission, the merchant's whitelisted domain and **IP whitelisting by emailing PayHere support**; 20 requests per 10 seconds | [V] https://support.payhere.lk/api-&-mobile-sdk/refund-api |
| Daily payouts on a T+2 cycle. Methods: Visa, Mastercard, Amex, Discover, Diners, HelaPay, Frimi, Genie, iPay, Q Plus, Sampath Vishwa. LankaQR is not listed. Payment Links exist on all plans | [V] https://support.payhere.lk/general-questions |
| A business registration is required, including for sole proprietors and partnerships, plus a business bank account in the business name | [S] https://support.payhere.lk/application-process , https://blog.payhere.lk/?p=383 |

**Other payment facts**

| Fact | Source |
| --- | --- |
| LankaQR: merchant discount rate removed for transactions up to Rs 5,000 from 6 April 2026 under the National QR Payment Promotion Programme; about 450,000 merchant locations | [S] https://www.ft.lk/front-page/Govt-waives-QR-payment-fees-for-small-merchants-in-push-towards-cash-lite-economy/44-790530 , https://english.newsfirst.lk/2026/04/06/sri-lanka-pushes-cash%E2%80%91lite-economy-with-lankaqr-launch |
| Coverage of the launch said cash is still dominant (one piece: about 99% of transactions) | [S] same coverage |
| WebXPay: reported free set-up, LKR 1,000 a year, 2.5% local cards; Genie is the option for people without a registered business | [S] https://epceylon.com/?p=16399 , https://cartdna.com/en/resources/blog/no-business-needed-payment-gateway-for-sri-lankans |
| Koko: three payments over 60 days. Shoppers report shops adding 10-15% for Koko | [S] https://www.mobitel.lk/bnpl-koko ; anecdotal Reddit snapshot |
| Police and the Consumer Affairs Authority warn about advance payment by bank transfer to WhatsApp-only sellers; edited transfer screenshots are a known fraud | [S] https://www.sundaytimes.lk/250406/news/beware-of-online-shopping-scams-targetting-the-season-warns-caa-594301.html , https://blog.ikman.lk/en/complete-safety-guide-for-ikman-users/amp |

Consequences for the register:

1. **PAY-05 is optimistic.** A late-cancellation outcome under BKG-09 ("amount retained") is by definition a partial refund. On PayHere that is manual on the documented support position, and impossible for wallet payments. The manual path must be the designed default, not the exception: it needs the customer's refund destination (bank account or wallet), a reference, and a "waiting funds" state alongside processing/refunded/failed.
2. **PAY-08 under-describes onboarding.** "Credentials entered by the Owner and tested with a trial transaction" omits: creating an API key with the right permission, adding the platform's domain, and emailing PayHere to whitelist the platform's server IP. That last step implies **stable egress IP addresses** for the platform (relevant to D-08 hosting) and a lead time per salon. PLT-02's checklist and BO-1 ("bookable in under one working day") should account for it.
3. **PAY-02 / assumption A-03 exclude many salons.** A merchant account needs business registration and a business bank account. Lite's Rs 200,000 monthly cap is about 100 deposits of Rs 2,000, and its Rs 50,000 per-payment cap is below a bridal advance (section 3). Salons will need Plus at Rs 3,990 a month, on top of our subscription.
4. **PAY-12 and APP-19 need Premium** (Rs 9,990 a month). CR-023 already noted this; it means no-show fees on a saved card are unavailable to nearly all Sri Lankan salons and should not be sold as a general R2 feature.
5. **PAY-09 has no fallback.** With no merchant account there is no way to take a deposit. Missing requirement M-01 below.
6. **PAY-14 (additional gateways, R3/could) is too late.** A LankaQR-capable or bank IPG option is the natural second provider once the fee waiver makes QR the cheapest tender for typical salon tickets.

### 2.3 Messaging

**WhatsApp Business Platform, verified on Meta's pages**

| Fact | Source |
| --- | --- |
| Per-delivered-message pricing since 1 July 2025 by category (marketing, utility, authentication, service) and recipient market | [V] https://developers.facebook.com/docs/whatsapp/pricing |
| **From 1 October 2026:** utility templates sent inside an open customer-service window changed from free to regular pricing; service (free-form) messages are charged again, with a free tier of 1,000 delivered service messages per business phone number per month | [V] https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing.md |
| **From 1 October 2026:** Sri Lanka was moved out of "Rest of Asia Pacific"; its utility and authentication rates decreased; an authentication-international rate was added | [V] same page |
| Sri Lanka rates after that change: marketing US$0.0732; utility, authentication and service US$0.0020 (Rest of Asia Pacific for comparison: US$0.0842 / US$0.0113) | [S] https://zernio.com/whatsapp-api-pricing (cites Meta's rate card, checked 25 September 2026); not read on Meta's own card |
| Authentication-international applies only when the business sent more than 750,000 such messages in 30 days **and** its primary business location is another country. A Sri Lankan business sending codes to Sri Lankan numbers pays the domestic rate | [V] https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing/authentication-international-rates |
| Opt-in is required before messaging; it may be collected off WhatsApp, including in person or on paper; the business must be named as the sender | [V] https://developers.facebook.com/documentation/business-messaging/whatsapp/getting-opt-in |
| Utility must be non-promotional and tied to the user's own transaction. Promotional, mixed, retargeting (renewal prompts) and birthday greetings are marketing. Meta re-categorises approved templates automatically after one day's notice, with escalating penalties up to portfolio-wide restrictions. One-time codes may only use authentication templates from the library; no URLs or emojis | [V] https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/template-categorization |
| Coexistence lets one number run both the WhatsApp Business app and the Cloud API, through a solution provider; group chats and broadcast lists stop on that number | [S] https://chakrahq.com/article/whatsapp-coexistence-business-app-register-cloud-api/ |

Consequences:

1. **BRD section 7.7 is out of date** ("utility templates sent inside an open customer-service window are free"). Cost models for NOT-05, PLT-10 and NOT-11 (shared inbox) must use the October 2026 rules. A shared inbox is free only up to 1,000 replies per number per month.
2. **The cheapest reminder channel is now WhatsApp utility**, at roughly LKR 0.6, level with or below SMS. NOT-02's "WhatsApp, else SMS" order is right, and decision 078's reasoning ("template messages cost money") is weaker than when written: automated WhatsApp need not be reserved for upper plans on cost grounds.
3. **Category discipline is a money and account-safety issue.** MKT-02 (Google review prompt, R1), NOT-09 (review request), MKT-03 (rebooking, win-back, birthday) will be classed marketing: about 36 times the utility price and needing marketing consent (NOT-06). NOT-04 lets each salon edit template text; an edit that adds a promotion gets the template re-categorised and counts toward penalties.
4. **D-07 (shared platform sender) concentrates risk.** With one WhatsApp account for all salons, Meta's penalties for template misuse are portfolio-wide per its own text, so one salon's wording can restrict every salon's reminders. The shared sender also needs a display name, and the product has none (D-02). Coexistence makes "the salon's own number" realistic earlier than "a later option".
5. Meta bills in US dollars; allowances sold in rupees carry exchange-rate exposure (PLT-10).

**SMS**

| Fact | Source |
| --- | --- |
| Dialog, Etisalat and Hutchison require pre-registered sender IDs; unregistered and numeric senders are blocked from 29 July 2026. Dynamic alphanumeric sender IDs are not supported on those networks. Mobitel carries only OTP and transactional traffic. Two-way SMS is not supported. About three weeks to provision through an international aggregator | [V] https://www.twilio.com/en-us/guidelines/lk/sms |
| Local aggregator: Rs 0.64-0.84 per SMS unit, alphanumeric sender ID up to 11 characters, approval in 1-3 business days, one sender ID included | [S] text.lk's own pages via search: https://text.lk/faq , https://text.lk/guides/sender-id-request-process-with-text-lk/ |
| Promotional SMS carries a levy of LKR 0.25 per message under the Finance Act No. 35 of 2018; the advertiser is liable; transactional messages and OTPs are exempt | [V] https://text.lk/guides/sms-levy-in-sri-lanka/ (vendor page describing the statute) |
| Marketing messages recommended 9:00-19:00 only | [S] https://sent.dm/resources/sri-lanka-sms-pricing |
| Sinhala and Tamil SMS use Unicode, 70 characters per segment instead of 160 | [GK] |

Consequences: BRD-10 ("messages carry the salon's name") on SMS means **one registered sender ID per salon**, an onboarding step with a lead time that PLT-02 and BRD section 12.1 do not carry per salon. Because replies are impossible, NOT-06's opt-out and NOT-03's confirm/cancel cannot work by SMS reply; they need a link, which in M1 has nowhere to land. NOT-05 cost estimates and MKT-04's "cost shown before sending" must count segments by script and add the levy for promotional sends.

### 2.4 Law and tax

**Personal Data Protection Act No. 9 of 2022**

| Fact | Source |
| --- | --- |
| Extraordinary Gazette No. 2498/16 of 22 July 2026 appoints **1 January 2027** for sections 2 and 3, Part I (processing requirements) and Part III (controllers and processors) | [V] https://www.ft.lk/front-page/Data-protection-compliance-regime-takes-effect-on-1-Jan-2027/44-795776 ; [V] https://neelakandan.lk/insights/data-protection-in-transition-understanding-sri-lankas-new-legal-framework |
| Part II (data-subject rights), Part IV (solicited messages) and Part VII (administrative penalties) are not yet in force; the Authority cannot fine from 1 January 2027 | [V] https://www.dailymirror.lk/breaking-news/Legal-experts-flag-unique-compliance-landscape-in-staggered-PDPA-rollout/108-346685 |
| Part III covers a data protection management programme, records, DPO where applicable, impact assessments, breach notification, controller-processor contracts and cross-border processing | [V] ft.lk and neelakandan.lk above |
| Detail from DLA Piper (the page's commencement line is stale, the substance is the Act and draft rules): consent must be demonstrable and withdrawable; special categories include health data **and children's data**; a child's consent comes from a parent or guardian; draft rules give 72 hours for breach notice; transfers abroad need binding safeguards or a derogation; marketing messages need prior consent, a free opt-out and identification of the sender; penalties up to LKR 10 million per non-compliance; rights requests answered within one month | [V] https://www.dlapiperdataprotection.com/index.html?t=law&c=LK |

The BRD (section 10.3, risk R-11) says commencement was still awaited. It is now dated, and it falls inside the M1 delivery window.

**Tax**

| Fact | Source |
| --- | --- |
| The enacted VAT (Amendment) Act No. 14 of 2026 kept the registration threshold at LKR 60 million a year; the cut to LKR 36 million was dropped | [V] https://kpmg.com/us/en/taxnewsflash/news/2026/07/tnf-sri-lanka-value-added-tax-amendment-act-no-14-of-2026-introduces-vat-on-nonresident-digital-service-providers.html |
| SSCL registration threshold reported as Rs 9 million a quarter / Rs 36 million a year from 1 July 2026 under Amendment Act No. 10 of 2026; the article predates the date and other sources say the threshold was left unchanged. **Unresolved** | [V, but dated April 2026] https://www.taxadvisor.lk/article/tc1 ; [S] conflicting |
| A revised VAT tax-invoice format is mandatory for every VAT-registered person from 1 October 2026 (Gazette 2481/22, date moved by Gazette 2500/106) | [V] https://www.taxadvisor.lk/article/tif1 |
| A national e-invoicing system with a RAMIS web API starts with B2B for VAT-registered persons; B2C through POS linkage is a later phase; no mandated file format or fiscal device yet | [S] https://www.taxadvisor.lk/article/einvoicing , https://edicomgroup.com/blog/electronic-invoice-sri-lanka-evat |
| Non-resident digital service providers are brought into VAT; supplies to VAT-registered businesses are carved out | [V] KPMG link above |

Most single salons are below LKR 60 million and CAT-05 ("off unless registered") is right for them. The gap is for the ones who are registered (chains, larger bridal houses) and for the operator's own invoices under PLT-05.

**Consumer rules on deposits.** Every Sri Lankan bridal term found says the advance is non-refundable (section 3). No Sri Lankan source on whether such a term is enforceable was found. BRD section 10.5 already lists this for counsel; it stays open.

### 2.5 Local practice

| Topic | Evidence | Register position |
| --- | --- | --- |
| Holidays and Poya | Covered by CR-023's research | STF-04 is right. Bridal adds a twist: demand concentrates on auspicious dates |
| Language | Census 2024: national-language literacy 97.4%, English literacy reported at 57.3%, Sinhalese 74.1% of the population. [S] https://www.ft.lk/front-page/Burgher-English-literacy-falls-from-97-4-in-2012-to-79-3-in-2024/44-791090 . A local rival sells a Sinhala billing screen [V] popitcart.com | Assumption A-09 ("English is acceptable for pilot staff and customers") is risky outside Colombo. NOT-04 (templates per language, R1) helps customers; PLT-15 and APP-12 are R2 and unowned |
| Staffing and pay | PopitCart Pro: commission splits, payroll, cash advances [V]. Hairdresser pay band 33,499-80,587 LKR a month [S] https://www.paylab.com/lk/salaryinfo/service-industries/hairdresser . No source on typical commission percentages or chair rental | STF-08 is R2 with no feature. Salary advances and per-event pay are absent |
| Hardware | 58/80 mm thermal printers, cash drawers, barcode scanners are standard in local POS [V] popitcart.com | PAY-06 issues receipts by WhatsApp, SMS or email only. No printed receipt |
| Connectivity and power | A local rival leads with offline billing "during power cuts" [V] popitcart.com | BRD risk R-13 promised "today's schedule cached for reading"; no requirement carries it |
| How salons take bookings today | Salon pages route booking to WhatsApp and phone: Manthra (WhatsApp, phone, email), Naturals ("Book Now" opens a WhatsApp chat) [V] https://www.manthra.lk/bridal-dressing , https://www.naturals.lk/bridal | BKG-11/BKG-16 record these channels. Nothing shortens them (see M-03, M-04) |

---

## 3. (B) Bridal and occasion salons

### 3.1 How Sri Lankan bridal salons work, from their own pages

| Practice | Evidence |
| --- | --- |
| Package from **LKR 180,000+**, "in-house pricing only"; outstation visits and special requests cost extra. All-inclusive: bride in two looks (Western and Homecoming), two maids with dressing and jewellery, mother dressing, threading, waxing, **two complimentary facials and a gold facial for the bride**, bouquets and head-dress. **40% non-refundable advance** at booking, **balance no later than 14 days before the function**, advances "strictly non-refundable under any circumstances". Booking needs a consultation, confirmed timing and the advance; contact by WhatsApp, phone, email. Jewellery, flower details, timing and add-ons "must be confirmed with management" | [V] https://www.manthra.lk/bridal-dressing (a search snippet of the same page said 30%; the page read said 40%) |
| Bridal, engagement, homecoming, bridesmaid and **flower girl** packages, all "Request Quote"; **50% non-refundable deposit**; mobile service across Sri Lanka; travel charges outside Colombo and Gampaha; trial sessions, book 2-3 months ahead; online request form and phone | [V] https://www.aleebridals.com/ |
| Itemised list: Groom's Dressing (All Day) 25,000; Best Man's Dressing (All Day) 15,000; full dressing 6,500-10,300; saree draping 2,100; hair style 3,300; **Celebrity Make-up Artist 26,300**; **Early Morning Add-on (before 8:30am) 2,500**; member prices; a cash/card price that does not combine with other payment methods; 15% off pre-bridal services above 50,000 | [V] https://www.naturals.lk/bridal |
| 25% advance, non-refundable and non-transferable; balance one month before; artist travels to the client | [S] WedMeGood listing (page blocked) |
| 25% deposit; **a tentative hold lasts one week and becomes firm only on the advance**; deposit lost on cancellation or reschedule | [S] kocofay.com snippet (site unreachable) |
| Leading dressers are booked 6-12 months ahead | [S] search summary of Sri Lankan salon guides |
| An astrologer sets the auspicious time (nekath) for each step of a Sinhala Buddhist wedding, including when the couple steps onto the Poruwa | [S] https://www.sjp.ac.lk/blog/poruwa-ceremony-sinhalese-buddhists-marriage-ritual/ |
| Hotels bundle or host bridal salons (Hilton Colombo with an on-site named salon; Jetwing lists bridal beauty packages) | [S] https://bizenglish.adaderana.lk/celebrate-love-with-hiltons-exclusive-wedding-campaign-across-sri-lanka |

### 3.2 What other tools do

| Tool | Finding | Evidence |
| --- | --- | --- |
| Fresha | Group appointments: clients book for **up to six people** online; each appointment follows its own cancellation policy; clients cannot reschedule a group online; changes are made appointment by appointment. No quote, contract, multi-day event or payment schedule | [S] search summary of https://www.fresha.com/help-center/knowledge-base/calendar/262-create-and-manage-group-appointments |
| HoneyBook / Dubsado | Proposal with package tiers, contract and deposit in one sitting; questionnaires for trials; automated workflows. Guides stress a **minimum headcount and a drop deadline** as the defining bridal contract terms | [S] https://agiled.app/blog/best-crm-for-hair-and-makeup-artists , https://www.dubsado.com/blog/why-dubsado-is-the-best-crm-for-hair-and-makeup-artists |
| Vagaro | Reviewed in CR-020 | - |

No salon platform found combines quotes, staged payments and multi-event party scheduling. F19 is a real gap in the market, and the six-person, single-day ceiling at Fresha is the comparison to sell against.

### 3.3 Planned scope against practice

Well covered already: enquiry pipeline (OCC-01/02), consultations and trials (OCC-03), versioned quotes with immutable accepted terms (OCC-05/06), multi-event party scheduling with ready-by times (OCC-08, BKG-22), travel (OCC-10, BKG-23), ledger and security deposits (OCC-09, PAY-15), cancellation and postponement from accepted terms (OCC-12/13), consent for media (OCC-17).

Gaps, each a candidate requirement (ids B-01 to B-12 are this report's, not the register's):

| # | Missing capability | Why it matters | Nearest requirement | Band |
| --- | --- | --- | --- | --- |
| B-01 | **A link for the quote**: view, accept and pay the advance from a secure link sent by WhatsApp, with no app. Same for the enquiry form | A wedding is a one-off purchase, often paid by a parent. OCC-15/16 put all customer actions in the app (F20), and F19 ships first with no customer surface at all, so OCC-06's "evidence of the terms accepted" has no way to be given. Salons today send quotes over WhatsApp | OCC-06, OCC-07, OCC-16, PAY-09, decision 079 | R2, inside F19 |
| B-02 | **Occasion work outside opening hours and staff working hours**, with an early-start surcharge | BKG-01 derives availability from opening hours and each professional's hours. Dressing for a morning nekath starts before the salon opens; Naturals prices "before 8:30am" as an add-on | BKG-01, OCC-08 | R2 |
| B-03 | **Booking horizon for occasions** independent of the ordinary "how far ahead" limit | BKG-02 limits how far ahead customers book (F07 enforces a 90-day rule). Bridal dates are taken 6-12 months out | BKG-02 | R2; check F07's rule does not need rework |
| B-04 | **Tentative date hold before a quote is accepted**, with an expiry of days and a visible "date pencilled" state; a per-day cap on occasions per lead artist | OCC-07's provisional holds begin at conversion of an accepted quote. Practice is to pencil the date at enquiry for about a week and firm it on the advance | OCC-02, OCC-07 | R2 |
| B-05 | **Pre-bridal treatments included in the package**, booked as ordinary appointments over the weeks before, drawn down from the occasion at no charge | Manthra's package includes facials, threading and waxing. Without a link, the front desk charges for them or tracks them on paper | OCC-04, OCC-05, CAT-10 | R2 |
| B-06 | **Dated items**: jewellery sets, sarees, head-dresses, bouquets. One set cannot be at two weddings on a day; issue and return are recorded; damage draws on the security deposit; fresh flowers are an order to a supplier with a deadline | Packages include "full dressing and jewellery" and flowers. PAY-15 holds the deposit but nothing schedules the item | CAT-09, PAY-15 | R2 should |
| B-07 | **Headcount change rules**: per-person lines for maids, mothers, flower girls, groom's party; a minimum; a cut-off date; an amendment that adjusts the price without a full requote | Named in bridal contract guides as the main source of loss | OCC-05, OCC-06 | R2 should |
| B-08 | **Off-site collection**: staff record the balance collected at the venue (cash, transfer, QR) from a phone, and the cash reaches the day-end close | Some artists take the balance on the day. PAY-06 and PAY-10 are in-salon | PAY-06, PAY-10, OCC-09 | R2 should |
| B-09 | **Balance-due automation**: due date relative to the event (14 days, one month), reminders, overdue flag, and a rule for what happens to the hold | OCC-05 has a payment schedule and OCC-18 notifications, but no dunning or consequence | OCC-05, OCC-18 | R2 |
| B-10 | **Team roles and outside helpers**: lead artist, assistant, dresser/saree draper, hair; freelancers hired per event; per-event pay | STF-01 allows a bookable person without sign-in; STF-08 pays commission per service, not per event | OCC-08, STF-01, STF-08 | R2 could |
| B-11 | **Minors in the party**: flower girls and page boys are children; children's data is a special category needing a parent's consent | OCC-17 covers consent generally, not guardianship. Also applies to IAM-12 dependants | OCC-17, IAM-12 | R2 must |
| B-12 | **Lead source and partners**: Instagram, referral, wedding fair, hotel or planner partner; trial fee credited to the package | OCC-14 reports conversion but not source; BKG-16 channels are per booking | OCC-01, OCC-14 | R2 could |

Also check, not a new requirement: PayHere's caps against bridal amounts. A 40% advance on LKR 180,000 is LKR 72,000, above Lite's per-payment limit; a full balance can exceed Plus's Rs 250,000. OCC-07/OCC-09 should assume bank transfer as the main bridal tender (M-01) and allow an instalment to be split.

---

## 4. (C) White-label app model

### 4.1 Store rules, verified

| Rule | Text or fact | Source |
| --- | --- | --- |
| Apple 4.2.6 | Template apps are rejected "unless they are submitted directly by the provider of the app's content"; the alternative is "a single binary to host all client content in an aggregated or 'picker' model" | [V] https://developer.apple.com/app-store/review/guidelines/ |
| Apple 4.3(a) | "Don't create multiple Bundle IDs of the same app" | [V] same |
| Apple 2.1(a) | Reviewers need a demo account or demo mode, "login credentials or a sample QR code" | [V] same |
| Apple 5.1.1(v) | No forced login without significant account features; in-app account deletion | [V] same |
| Apple 3.2 in practice | Apps "intended to be used by a specific business or organization" are rejected from public distribution and pointed to private distribution | [V] https://developer.apple.com/forums/thread/783892 |
| Apple enrolment | A sole proprietor enrols as an individual and "your personal legal name will be listed as the seller". Organisations must be legal entities ("We don't accept DBAs, fictitious businesses, trade names, or branches"), with a D-U-N-S number, a work email on the organisation's domain and a website that is "publicly available and functional" ("links to social media webpages or websites that contain minimal content ... won't be accepted") | [V] https://developer.apple.com/help/account/membership/program-enrollment/ |
| Google | Recommends one developer account per client; in a single account "a policy violation on one app can affect every app"; each app needs its own description, icon, graphics and screenshots | [V] https://support.google.com/googleplay/android-developer/answer/15884185 |
| Google verification | Android developer verification: required from 30 September 2026 in Brazil, Indonesia, Singapore and Thailand, global expansion in 2027 | [S] https://android-developers.googleblog.com/2026/06/android-developer-verification.html |
| Vagaro | The business must own its Apple and Google accounts, registered as an organisation; store fees are extra; Vagaro notes its free channels already take bookings | [S] https://www.vagaro.com/en-ca/pro/branded-app |
| Mindbody | Branded app plus branded web tools included for all customers; clients "reserve from your branded app or website" | [S] https://www.mindbodyonline.com/business/branded-web-tools |
| Phorest | Online bookings "from any page of a business website, from social media, or through a custom, branded salon booking app" | [S] GetApp listing |
| Instagram | The official "Book now" button is for Meta's approved partners and shows only in the mobile app; everyone else uses a profile link | [S] https://support.booksy.com/hc/en-us/articles/16486489939602-How-do-I-take-bookings-from-Instagram |
| Deferred deep links | Firebase Dynamic Links shut down on 25 August 2025 with no direct replacement; native Universal Links and App Links do not carry a destination through an install | [S] https://blog.pragmaticengineer.com/google-shutting-down-firebase-dynamic-links/ |

Decision 071 (shared container app as standard, dedicated app in the salon's own accounts) is consistent with all of this. The problems are in the details below.

### 4.2 Problems with the current plan

1. **The container app is not a "picker".** BRD-09 promises that "no other salon is shown or suggested". Apple's permitted model is described as aggregated or picker. An app that shows nothing until a link, QR code or short code is supplied resembles the "specific business" apps rejected under 3.2, and a reviewer who opens it cold sees an empty shell (2.1, 4.2). Code-gated member apps do exist in the stores [GK], so this is a managed risk, not a blocker: it needs a reviewer demo salon, a designed cold-start screen (scan QR, enter salon code, "ask your salon for its link"), and review notes. BRD section 10.4 mentions a demonstration salon; no requirement or F15 scope carries the cold-start experience.
2. **A web page is unavoidable.** MKT-01's shareable link must be an HTTPS address. On a phone without the app it opens in the browser, so something must be served there; on iOS the destination is lost through the App Store install unless the customer taps the link again, scans the QR or types the code [GK on mechanics; S on the Firebase shutdown]. BRD-12 says "direct users to its store listing"; BRD-09 and MKT-01 say nothing about the not-installed case. A per-salon landing page (brand, hours, WhatsApp button, store buttons, the salon code) is a customer web surface in everything but name and should be an explicit, bounded exception like decision 079.
3. **The product has no name (D-02), and three registrations depend on it**: the store listing of the shared app, the WhatsApp display name of the shared sender (D-07), and the SMS sender ID. All have lead times. D-02 is on the critical path for M1's messaging (F11), not just M2.
4. **Dedicated apps fit few Sri Lankan salons.** Many salons are sole proprietorships; under Apple's rule their app would show the owner's personal name as the seller. Organisation enrolment needs a functioning website on the salon's own domain, and we provide no customer website. BRD-08 tracks enrolment but has no eligibility pre-check, and BRD-05 to BRD-08 are all "must" in R2. Android-only dedicated apps avoid most of this and suit a market that is about 79% Android (BRD section 5.1).
5. **App-only customer booking is an outlier.** Fresha gives free direct, Facebook and Instagram booking links; Mindbody and Phorest pair the branded app with web booking; Vagaro tells customers its free channels already take bookings; the local entrant SaloBook has a booking page. The BRD itself said "the booking site must be a first-class channel" and "build the booking site before the app" (sections 5.5 and 12); CR-010 reversed that. No head-to-head conversion data was found, so the size of the loss is unknown, but BO-3 (40% of appointments created by the customer at 90 days) was written for a world with a booking site.

---

## 5. Missing requirements (cross-area)

Ids M-01 onward are this report's.

| # | Capability | Evidence | Why it matters | Band |
| --- | --- | --- | --- | --- |
| M-01 | **Manual deposit against a future booking**: staff record a deposit received by bank transfer, LankaQR or cash before the visit, with tender, reference and an optional photo of the slip; the booking moves from pending payment to confirmed; the deposit request message carries the salon's bank details or QR; pending-payment expiry is configurable in hours | PayHere needs business registration and caps Lite; LankaQR is fee-free to Rs 5,000; salons take advances by transfer (section 3.1 terms; slip habit itself [GK]) | Without it a salon with no merchant account has no deposit, the main no-show control (BO-4). REP-10 already expects "deposits held" by tender but no requirement lets one be recorded outside checkout or the gateway | R1 must (F08/F10) |
| M-02 | **Pending-payment expiry for payment links**, separate from the 10-minute hold | BKG-04's hold is 10 minutes; a link sent by WhatsApp is paid hours later | PAY-09 does not say how long the slot is kept | R1 must (F08) |
| M-03 | **Reply buttons on WhatsApp reminders** ("Confirm", "Can't make it") handled as inbound messages that update the booking or raise it on the attention list | Two-way SMS is unsupported in Sri Lanka [V Twilio]; NOT-03's links are deferred to F15 (decision 082); WhatsApp templates support quick-reply buttons [GK] | Gives M1 a two-way reminder without any customer web surface | R1 should (F11) |
| M-04 | **Manual refund workflow as the default**: capture the customer's refund destination, record the outgoing transfer with a reference, a "waiting funds" state, and the wallet methods that cannot be refunded at all | [V] PayHere refund pages | BKG-08 shows "the exact refund"; PAY-05 cannot deliver a partial one through the gateway | R1 must (F08) |
| M-05 | **Gateway onboarding steps and static egress IPs**: API key, domain, IP whitelisting request, plan check (warn when a deposit or advance exceeds the plan's per-payment or monthly cap) | [V] PayHere refund API and limits pages | PAY-08 and PLT-02 under-describe it; an over-limit payment fails at the customer | R1 must (F08, F14) |
| M-06 | **Per-salon SMS sender ID and WhatsApp identity** as tracked onboarding items with status and lead time | [V] Twilio guidance; [S] text.lk | BRD-10 cannot be met otherwise; reminders silently go out under the platform's name or not at all | R1 must (F11, F14) |
| M-07 | **Message category on every template** (utility, authentication, marketing), a guard that stops a salon's edit from turning a utility template promotional, cost estimates by category, script and segment, and the promotional SMS levy | [V] Meta categorisation and pricing pages; [V] levy page | 36x price difference, portfolio-wide penalties, wrong cost previews in NOT-05/MKT-04 | R1 must (F11) |
| M-08 | **Printed receipt** on 58/80 mm thermal paper from the browser | [V] local POS norm | A cash customer at the desk expects paper; PAY-06 has only WhatsApp, SMS, email | R1 should (F10) |
| M-09 | **Read-only Today when offline**, and a clear queue for actions taken while offline | [V] rival's offline claim; BRD R-13 | Promised as a mitigation, never registered | R1 should (F10) |
| M-10 | **Per-salon landing page behind every shared link and QR** (brand, hours, WhatsApp, store buttons, salon code), plus the container app's cold-start and reviewer demo salon | Section 4.2 | MKT-01 and BRD-09 do not define the not-installed case; store review needs it | R1 must (F15) |
| M-11 | **Data-protection operations**: processing terms accepted by the salon at onboarding, sub-processor list, record of processing, breach register with affected-salon and affected-person lookup, retention periods a salon sets with automatic anonymisation, a staff-side path to answer a customer's access or erasure request before the app exists | PDPA Parts I and III from 1 January 2027 | BRD 10.3 lists these as intentions; the register has only NFR-09, CRM-04, IAM-05 (in-app, so M2) | R1 must |
| M-12 | **VAT tax invoice in the prescribed format** for registered salons and for the operator's own invoices; fields from Gazette 2481/22 | [V] taxadvisor.lk | PAY-07 says only "numbered per salon and show tax and service charge lines" | R1 must for registered tenants |
| M-13 | **Salary advances and per-event pay** alongside commission | [V] PopitCart Pro | Local expectation; STF-08 covers percentage commission only | R2 could |
| M-14 | **Eligibility pre-check for a dedicated app** (legal entity, D-U-N-S, own-domain website and email) before it is sold; Android-only option | [V] Apple enrolment page | Avoids selling a tier the salon cannot receive | R2 must (F16) |

---

## 6. Wrong or risky assumptions

| # | Where | Assumption | What the evidence says |
| --- | --- | --- | --- |
| W-01 | BRD 10.3, risk R-11 | PDPA core obligations await a commencement order | Parts I and III commence 1 January 2027 (Gazette 2498/16) |
| W-02 | BRD 7.7; decision 078 reasoning | Utility templates in an open service window are free; WhatsApp is the costly channel | Charged since 1 October 2026; service replies free only to 1,000 a month per number; Sri Lanka utility is about US$0.002 |
| W-03 | PAY-05 | Partial refunds go through the gateway | PayHere support: full refunds only, cards only; API docs disagree. Needs a sandbox test before F08 is planned |
| W-04 | PAY-02, PAY-08, A-03 | Any salon can connect its own PayHere account and take deposits | Needs business registration and bank account; Lite caps Rs 50,000 / Rs 200,000; IP whitelisting by email per merchant |
| W-05 | PAY-12, APP-19 | Saved-card fees and saved payment methods are a general R2 feature | Premium plan only, Rs 9,990 a month |
| W-06 | PAY-14 | A second gateway can wait for R3 | LankaQR is free to Rs 5,000 and absent from PayHere's method list |
| W-07 | D-07 | A shared platform sender is the safe launch choice | Shared template-misuse penalties, no product name for the display name, SMS masks are per sender and not dynamic |
| W-08 | NOT-03 (R1 must) | Reminders "include links to confirm, reschedule or cancel" in R1 | The links are deferred to F15; SMS replies are impossible; M1 reminders are one-way unless M-03 is added |
| W-09 | NOT-06 | "Always include a way to opt out" | By SMS that must be a link or a call; no landing surface in M1 |
| W-10 | MKT-02, NOT-09, MKT-03 | Review and rebooking prompts are ordinary notifications | Marketing category: consent, about 36x cost, 9:00-19:00 practice, SMS levy |
| W-11 | A-09, D-09 | English suffices for pilots | English literacy 57.3%; a local rival sells Sinhala screens |
| W-12 | BRD-05, BRD-08 | A salon can enrol with Apple as itself | Sole proprietors appear under the owner's personal name; organisations need a real website |
| W-13 | BRD-09 | A link-only container app with no other salon visible satisfies 4.2.6 | It is not the "picker" Apple describes; needs cold-start and reviewer design |
| W-14 | BO-3 | 40% of appointments created by the customer at 90 days | Written when a booking site existed; now depends on app installs alone |
| W-15 | BKG-01, BKG-02 (as foundations for F19) | Availability comes from opening hours and a booking horizon | Bridal work is outside both |
| W-16 | CAT-05 / SSCL | Thresholds are stable configuration | VAT threshold stayed at LKR 60 million; SSCL threshold may have dropped to LKR 36 million from July 2026. Confirm with a tax adviser |

---

## 7. Mis-scheduled items

| Item | Now | Suggested | Reason |
| --- | --- | --- | --- |
| Manual deposits (M-01), manual refunds (M-04) | Absent | R1, F08/F10 | Otherwise deposits work only for salons with a qualifying merchant account |
| Two-way reminder by WhatsApp buttons (M-03) | NOT-03 links wait for F15 | R1, F11 | The reminder is the main reason a salon pays in M1 |
| Data-protection operations (M-11) | Intentions in BRD 10.3 | Before the first pilot salon; in force 1 January 2027 | Legal date |
| PAY-14 second gateway / QR | R3 could | R2 should | W-06 |
| STF-08 commission, REP-08 | R2 unowned, R3 | First feature after M1, or a report in F13 | Local rivals lead with it; no feature owns it |
| PLT-15 staff Sinhala/Tamil; APP-12 | R2 unowned | Owned feature early in R2; Sinhala first | W-11 |
| NOT-10 / NOT-11 WhatsApp conversation and shared inbox | R3 could (F22) | R2 should, without AI | WhatsApp is where enquiries arrive; 1,000 free replies a month per number |
| F16 dedicated apps (BRD-05 to BRD-08 all must) | R2 must | R2 should, gated by M-14, Android first | W-12 |
| MKT-02 Google review prompt | R1 should | R1 could, or email/push only | W-10 |
| Quote and enquiry link (B-01) | Customer side waits for F20 | Inside F19 | F19 otherwise ships with no way for a customer to accept |
| F19 dependency on F18 multi-branch | After F09, F11, F17, F18 | Re-examine whether F18 is a true prerequisite | Bridal is the differentiator and currently the last staff feature |

---

## 8. Regulatory and compliance obligations not reflected in the register

1. **PDPA Part I and III from 1 January 2027**: lawful basis and purpose records, a data protection management programme, controller-processor contract with every salon (the operator is processor for customer data and controller for staff and billing data), breach notification (72 hours in draft rules), impact assessment where high-risk, DPO where special categories are processed at the prescribed scale. See M-11.
2. **Special categories**: health and allergy notes (CRM-06, APP-18, OCC-03 intake) and **children's data** (IAM-12 dependants, flower girls and page boys in OCC-08) need explicit consent, and a parent's or guardian's for a child. OCC-17 and CRM-04 do not mention guardians.
3. **Cross-border transfer**: hosting outside Sri Lanka (D-08, still open) needs a binding instrument or a derogation. The transfer rules' commencement status could not be confirmed (sources disagree on which Part holds them); treat as applying.
4. **Marketing messages**: prior consent, free opt-out and sender identification are in the Act (Part IV not yet in force) and already in Meta's and the operators' rules. NOT-06 covers consent; the opt-out mechanism for SMS is undefined (W-09).
5. **SMS**: registered sender ID per sender; promotional levy of LKR 0.25 per message, advertiser liable.
6. **VAT**: prescribed tax-invoice format from 1 October 2026 for registered persons; B2B e-invoicing through RAMIS in phases, which will reach the operator's subscription invoices (PLT-05) once the operator is registered.
7. **Consumer terms**: non-refundable advances are universal in bridal practice; their enforceability under the Consumer Affairs Authority Act was not established. OCC-12 should let a salon set its terms and keep evidence, and counsel should review default template wording (BRD 10.5).
8. **Store obligations for the container app**: reviewer demo tenant, in-app account deletion (IAM-05), and the yearly target-SDK rebuild now apply to the operator's own app in R1/M2, not only to dedicated apps.

---

## 9. Risks to the roadmap order (web for staff, then the app, then bridal)

1. **M1 alone is weakly differentiated in this market.** It has no customer self-booking, which Fresha gives free, and lacks the local POS strengths (offline, paper receipt, Sinhala, commission). What it can win on in M1 is deposits and reminders. Both are currently narrower than they look: deposits only through a qualifying PayHere account (W-04) and reminders one-way (W-08). M-01 and M-03 are the two cheapest fixes and both stay inside CR-010.
2. **Hard dates and lead times fall inside M1**: PDPA on 1 January 2027; SMS sender registration per salon; WhatsApp business verification and template approval; a product name for all three; PayHere whitelisting per salon. None is on a feature's plan.
3. **M2 depends on installs.** Customers reach the app from a WhatsApp or Instagram link, pass through a store, and on iOS lose the salon context unless they tap again. The landing page (M-10) is needed regardless. If install rates disappoint, there is no fallback channel, where every competitor has one. Options for the owner, least to most change: (a) landing page with a WhatsApp button only; (b) a "request a time" form on that page that creates a pending request for staff; (c) reopen CR-010 for a thin booking page.
4. **Store review of the container app** is a schedule risk for M2 (section 4.2, item 1). Submit a skeleton with a demo salon early, as BRD section 12 advised for dedicated apps.
5. **Bridal is last but is the clearest gap in the market** (packages from LKR 180,000 against haircuts of a few thousand; no rival goes past a six-person group). It waits behind the app and multi-branch, and its customer side is app-only. A staff-side slice (enquiry, date pencil, quote by link, advance by transfer, balance tracking) depends on little beyond F08-F12 and would give bridal salons a reason to adopt before M3.
6. **F16 may be built for a tier few can buy** (W-12). Validate demand and eligibility with pilot salons before committing the pipeline.
7. **Messaging economics moved in our favour.** WhatsApp utility at about LKR 0.6 against Fresha's LKR 19-95 per message makes "reminders included" an affordable headline for every plan.

---

## 10. Source list

Payments: https://www.payhere.lk/fees/ · https://support.payhere.lk/limits-and-fees · https://support.payhere.lk/refund-process · https://support.payhere.lk/api-&-mobile-sdk/refund-api · https://support.payhere.lk/general-questions · https://support.payhere.lk/application-process · https://www.ft.lk/front-page/Govt-waives-QR-payment-fees-for-small-merchants-in-push-towards-cash-lite-economy/44-790530 · https://english.newsfirst.lk/2026/04/06/sri-lanka-pushes-cash%E2%80%91lite-economy-with-lankaqr-launch · https://epceylon.com/?p=16399

Messaging: https://developers.facebook.com/docs/whatsapp/pricing · https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing.md · https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing/authentication-international-rates · https://developers.facebook.com/documentation/business-messaging/whatsapp/getting-opt-in · https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/template-categorization · https://zernio.com/whatsapp-api-pricing · https://www.twilio.com/en-us/guidelines/lk/sms · https://text.lk/faq · https://text.lk/guides/sms-levy-in-sri-lanka/

Law and tax: https://www.ft.lk/front-page/Data-protection-compliance-regime-takes-effect-on-1-Jan-2027/44-795776 · https://www.dailymirror.lk/breaking-news/Legal-experts-flag-unique-compliance-landscape-in-staggered-PDPA-rollout/108-346685 · https://neelakandan.lk/insights/data-protection-in-transition-understanding-sri-lankas-new-legal-framework · https://www.dlapiperdataprotection.com/index.html?t=law&c=LK · https://kpmg.com/us/en/taxnewsflash/news/2026/07/tnf-sri-lanka-value-added-tax-amendment-act-no-14-of-2026-introduces-vat-on-nonresident-digital-service-providers.html · https://www.taxadvisor.lk/article/tif1 · https://www.taxadvisor.lk/article/tc1 · https://www.taxadvisor.lk/article/einvoicing

Competitors and practice: https://www.fresha.com/pricing · https://popitcart.com/ · https://info.salobook.lk/ (unreachable) · https://www.manthra.lk/bridal-dressing · https://www.aleebridals.com/ · https://www.naturals.lk/bridal · https://www.fresha.com/help-center/knowledge-base/calendar/262-create-and-manage-group-appointments · https://agiled.app/blog/best-crm-for-hair-and-makeup-artists · https://www.ft.lk/front-page/Burgher-English-literacy-falls-from-97-4-in-2012-to-79-3-in-2024/44-791090

App model: https://developer.apple.com/app-store/review/guidelines/ · https://developer.apple.com/help/account/membership/program-enrollment/ · https://developer.apple.com/forums/thread/783892 · https://support.google.com/googleplay/android-developer/answer/15884185 · https://android-developers.googleblog.com/2026/06/android-developer-verification.html · https://www.vagaro.com/en-ca/pro/branded-app · https://www.mindbodyonline.com/business/branded-web-tools · https://support.booksy.com/hc/en-us/articles/16486489939602-How-do-I-take-bookings-from-Instagram · https://blog.pragmaticengineer.com/google-shutting-down-firebase-dynamic-links/
