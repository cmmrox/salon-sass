# Backlog

Release 1 features are the folders beside this file; their order and status are in `README.md`
(generated). This file holds what comes after: candidate features for Releases 2 and 3, and ideas
that came out of QA reviews. Nothing here is committed scope. A candidate becomes a feature through
`feature-new`, with the next free `F` number.

Requirement text is in `docs/brd/requirements.yaml`.

## Release 2 candidates

| Candidate feature | Requirements |
| --- | --- |
| Multi-branch salons | PLT-11, APP-20, REP-07 |
| Operator insight and message allowance | PLT-09, PLT-10, BRD-13 |
| Richer service menu: add-ons, per-professional prices, resources, packages, service flags | CAT-07, CAT-08, CAT-09, CAT-10, CAT-11 |
| Smarter booking: automatic waitlist offers, multi-professional bookings, risk rules, booking for someone else | BKG-18, BKG-19, BKG-20, IAM-12 |
| Reviews and ratings | APP-13, STF-07, NOT-09 |
| Loyalty, offers and campaigns | MKT-03, MKT-04, MKT-05, MKT-06, APP-15, APP-16 |
| Customer app extras: Sinhala and Tamil, favourites, gallery, consultation forms, saved payment method | APP-12, APP-14, APP-17, APP-18, APP-19 |
| Payment operations: payment links, day-end summary, reconciliation, late fees | PAY-09, PAY-10, PAY-11, PAY-12 |
| Customer insight: structured records, segments, blocking | CRM-06, CRM-07, CRM-08 |
| Deeper reports | REP-05, REP-06 |
| Custom domains and app links | BRD-11, BRD-12 |

## Release 3 candidates

| Candidate feature | Requirements |
| --- | --- |
| Retail and inventory | INV-01, INV-02, INV-03 |
| Staff pay and attendance | STF-08, STF-09, REP-08, PAY-13 |
| Advanced booking: recurring, group, at the customer's location, processing time | BKG-21, BKG-22, BKG-23, CAT-12 |
| Memberships, gift cards and referrals | MKT-07, MKT-08, MKT-09, APP-21 |
| Conversational booking on WhatsApp and a shared inbox | NOT-10, NOT-11 |
| Smaller additions | PLT-12, IAM-13, APP-22, PAY-14, REP-09 |

## Ideas from QA reviews

Added by the lead at `stage-close` and `feature-close`, from suggestions classed as "idea".

| Idea | From | Note |
| --- | --- | --- |
| Tell a browser that runs no scripts something other than "checking the service" for ever | SUG-F01-S01-04 | Decide the no-script policy before the branded shell (F01-S03) |
| Do not call a request the server refuses to understand (501, 505) an `internal_error` | SUG-F01-S01-05 | It will be noise in error-rate alerting; fits F01-S04 |
| Set a page-weight budget and the security headers when the branded shell replaces the status page | SUG-F01-S01-06 | About 180 KB of compressed JavaScript today; fits F01-S03 |
| Give a light brand's sign-in initial a readable outer line on the photograph | SUG-F02-06 | Add an ink or white line when the brand is under 3:1 on the card; fits F04 branding |
| Name the first card on Staff access and Activity log for its content instead of repeating the page title | SUG-F02-07 | Screen readers hear the title twice |
| Add a visually hidden "Skip to content" link before the workspace navigation | SUG-F02-08 | Grows in value as F05-F10 add navigation |
| Let long words in headings wrap | SUG-F01-S01-07; SUG-F01-02 | Seen again in the F01 review at 320 wide with 200% text: the status heading extends beyond its notice background, with no page overflow. Proposal for a later approved visual change. |
| Put the time on the web app's log lines, and say what was left running when a start fails half way | SUG-F01-S01-08 | Local scripts |
| Give an automation check a way to report an environment fault to the runner, which today records only a missing tool that way | QA of F01-S01 | Lifecycle tooling |
| Agree client retry guidance for temporary 503 responses | SUG-F01-S02-01 | Consider with the public shell; requires owner approval before changing requirements |
| Evaluate tenant-aware conditional reads when the public brand payload grows | SUG-F01-S02-02 | Consider with F04 branding; cache and validator keys must include the salon |
