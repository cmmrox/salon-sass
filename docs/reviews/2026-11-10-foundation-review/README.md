# Foundation review, 2026-11-10

A review of the requirements, of F01 to F07 as built, and of the delivery process, done before F08 at the owner's
request (decision 113). Six reviewers worked read-only on `main` at `e5ccf3aa`; their reports are beside this file as
they were received. Code findings come from reading the code, not from running it, and performance figures are
reasoned, not measured. Market facts marked as secondary in the reports are unconfirmed.

| Report | Covers |
| --- | --- |
| `market-global.md` | The register against eleven leading salon products |
| `market-sri-lanka-and-bridal.md` | The Sri Lankan market, bridal practice, the white-label app model |
| `api-platform.md` | Tenancy, identity, the operator console, outbox, jobs (F01 to F03) |
| `api-domain.md` | Salon setup, catalogue, staff, the booking engine, and readiness for F08 to F22 |
| `web.md` | The Next.js workspace and console |
| `qa-and-delivery.md` | QA automation, lifecycle tooling, CI, and where time and rework went |
| `HANDOVER.md` | The work that follows, in order, for the developer and QA |

## Verdict

Sound: no path was found by which one salon reads another's data, and no race that double-books a professional.
Corrected before F08: the scope (no online booking, one service per booking), the booking model's shape, the web's
data handling, and the order of verification (review now comes before QA).

## What came of it

- **CR-028** (approved): requirement changes, the customer booking page (F24), F12 before F08, commission (F25).
- **CR-029** (approved): review before QA, a design and market check at `feature-new`, QA's scope, regression at close.
- **Decisions 113 to 123** in `docs/decisions/DECISION_LOG.md`.
- **Bugs filed:** BUG-F02-016, BUG-F03-009, BUG-F03-010, BUG-F04-001, BUG-F05-002, BUG-F06-001, BUG-F06-002, BUG-F07-006.

## Open checks for the owner

None of these is a requirement yet; each needs confirming, then its own change request.

- PayHere: whether partial refunds work (its support page says full refunds, cards only), the Lite plan's limits, and
  the server address whitelisting. Test in the sandbox before F08 is planned.
- The data-protection act's start date (reported as 1 January 2027), SMS sender registration, the VAT invoice format
  and the SSCL threshold, and WhatsApp's current pricing: confirm with an adviser or the primary source.
- The product name (D-02) blocks the store listing, the WhatsApp display name and the SMS sender.
- Change requests still `proposed`: CR-004, CR-006, CR-014, CR-015, CR-018, CR-019, CR-024, CR-027.
