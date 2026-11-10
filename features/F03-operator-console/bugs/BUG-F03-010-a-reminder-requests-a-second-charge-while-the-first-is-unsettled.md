---
id: BUG-F03-010
title: "Each billing reminder records another full-amount charge attempt even when an earlier attempt for the same invoice is still waiting, so a salon can be charged more than once for one invoice"
severity: major
status: verified
found_after_merge: 2026-11-10
found_by: "exploratory: the foundation review of 2026-11-10, by reading the code; not yet reproduced on the running product"
breaks: "PLT-05 (subscription billing); NFR-11 (every payment event is idempotent)"
found_on: 2026-11-10
fixed_in: 926cbca810d43b8e0b8d904f1263c5f8c5fbb171
fix_note: "A charge attempt is recorded only when no earlier attempt for the invoice is still requested (one statement, under the billing pass's subscription lock), so a reminder day retries only after the attempt before it failed; the first reminder day after a failure retries under the earliest occasion not yet used, as a late pass catches up. Reminder emails are unchanged."
developer_note: "Reproduced by SubscriptionBillingIntegrationTest.aReminderRequestsNoChargeWhileAnEarlierAttemptForTheInvoiceIsUnsettled: with the issue attempt still requested, the due-date pass recorded a second full-amount attempt. The existing retry test assumed each reminder retried without the earlier attempt settling; it now fails each attempt before the next reminder day, as the provider would, and keeps its four occasions."
verified_on: 2026-11-10
approved_by: null
found_in_release: null
---

# BUG-F03-010 Each billing reminder records another full-amount charge attempt even when an earlier attempt for the same invoice is still waiting, so a salon can be charged more than once for one invoice

Filed by the lead from `docs/reviews/2026-11-10-foundation-review/README.md` (decision 113). The developer reproduces it first, as `bug-fix` requires; if it
does not reproduce, say so in `developer_note` and leave it for QA.

## Steps

Account: an operator Admin. Data: a salon on a card plan with an issued invoice whose first charge attempt is still `requested` (for example the salon is suspended so its outbox is paused, or the provider fake is set to delay).

1. Move the test clock past the due date, then past each reminder date, letting the billing job run each time.
2. Let delivery resume and read the invoice's charge attempts and the provider fake's captured charges.

## Expected

One charge is in flight for an invoice at a time: a reminder requests a new attempt only after the earlier one has failed.

## Actual

By reading: an attempt is unique per invoice and occasion (`issue`, then each reminder kind), each with its own idempotency key, and the reminder path requests one without checking the earlier attempt's status. When delivery resumes, every waiting attempt is sent for the full amount. Raised as P1 by the automated review of PR #13 and never answered.

## Evidence

- `apps/api/src/main/java/com/salonplatform/platform/repository/InvoiceStore.java:137-146` (`ON CONFLICT (invoice_id, occasion) DO NOTHING`).
- `apps/api/src/main/java/com/salonplatform/platform/service/impl/InvoiceIssuing.java:90,99-120,136`.
- PR #13 review comment: "Retry only after the preceding charge has failed".
- No real provider is connected yet, so no salon has been charged; fix before billing is switched on.

## Re-verification

Verified on 2026-11-10 on `148bf6af` (fix `926cbca8`), QA instance, on this bug's own symptom: no second charge while one is unsettled.

- **The bug's own steps, as a case:** TC-F03-S02-029 (new, `qa/api/F03/S02-billing-cycle.spec.ts`, regression). A card salon made for the test is suspended, so its charge request waits in the outbox, and the clock crosses trial end, the due date and the 3-day reminder.
  - One attempt exists throughout (`issue`, requested), and there is one charge request.
  - When the salon is reactivated the provider fake receives that one charge, and fails it.
  - Passed to that point in `qa/reports/20261110-121014-F03-S02-87eae546dd2b`.
- The case fails without the fix on a temporary local build with the eight fix commits reverted (commit d99b85b5, deleted afterwards): a second attempt (`due`, requested) is made on the due date while the first is unsettled (`qa/reports/20261110-113827-F03-S02-7df67c1544f2`).
- **The retry's timing is BUG-F03-011.** After the failure, the next billing pass, on a day that is no reminder day, already requested the retry. The case's strict check of that stays, so TC-F03-S02-029 fails until BUG-F03-011 is fixed.
- **Neighbours, for the charge request:** TC-F03-S02-007, -008 and the rest of `billing-cycle` passed in `qa/reports/20261110-115714-F03-S02-1f38dc283f1d`. F03-S03 `lifecycle` (billing while suspended) passed in `qa/reports/20261110-115757-F03-S03-7ab9e7ae0a1a`.
