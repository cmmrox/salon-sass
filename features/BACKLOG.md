# Backlog

Approved features are the folders beside this file; their order and status are in `README.md`
(generated). This file holds only unscheduled candidate features for Releases 2 and 3, and ideas
that came out of QA reviews. Nothing here is committed scope. A candidate becomes a feature through
`feature-new`, with the next free `F` number.

Requirement text is in `docs/brd/requirements.yaml`.

## Release 2 candidates

| Candidate feature | Requirements |
| --- | --- |
| Operator insight and message allowance | PLT-09, PLT-10, BRD-13 |
| Smarter booking: automatic waitlist offers, multi-professional bookings, risk rules, booking for someone else | BKG-18, BKG-19, BKG-20, IAM-12 |
| Reviews and ratings | APP-13, STF-07, NOT-09 |
| Customer campaigns | MKT-03, MKT-04 |
| Customer app extras: Sinhala and Tamil, favourites, gallery, saved payment method | APP-12, APP-14, APP-17, APP-19 |
| Payment operations: payment links, day-end summary, reconciliation, late fees | PAY-09, PAY-10, PAY-11, PAY-12 |
| Customer insight: segments and blocking | CRM-07, CRM-08 |
| Deeper reports | REP-05, REP-06 |
| Custom domains and app links | BRD-11, BRD-12 |

## Release 3 candidates

| Candidate feature | Requirements |
| --- | --- |
| Retail and inventory | INV-01, INV-02, INV-03 |
| Staff pay and attendance | STF-08, STF-09, REP-08, PAY-13 |
| Recurring appointments | BKG-21 |
| Memberships and referrals | MKT-07, MKT-08 |
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
| Return focus to the opener in the same frame a confirmation closes | SUG-F02-09 | Radix restores focus on a timer; one frame on the page body today |
| Send a credential the API calls non-renewable straight to sign-in on the server | SUG-F02-10 | Avoids briefly painting an empty workspace frame |
| Make the API integration tests bind and connect on the same loopback address | Developer, CR-017 F01-S03 gates | A random test port collided with an IDE language server on 127.0.0.1 and failed one test once |
| Give the phone navigation sheet a visible close button | SUG-F01-03 | A `SheetClose` icon button labelled from the translations; today it closes on Escape, backdrop or a route |
| Let an Admin archive plans so pickers and the plan directory list only plans still offered | SUG-F03-04 | Keeps existing assignments; also shortens the Create salon picker that wraps its pager at 360px |
| Cap a renewed operator token's `accessExpiresAt` at the session's absolute end | SUG-F03-05 | Return `min(now + 5 min, session end)`; the token is already refused at the end |
| Compact the stacked support and billing banners on phones | SUG-F03-06 | About 430px of an 844px screen before content when both apply |
| Take the close sheet's deletion date from the API's clock, not the browser's | SUG-F03-07 | The closure itself already uses the application clock |
