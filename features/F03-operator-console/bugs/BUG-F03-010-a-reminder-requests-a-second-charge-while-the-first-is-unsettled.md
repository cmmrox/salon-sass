---
id: BUG-F03-010
title: "Each billing reminder records another full-amount charge attempt even when an earlier attempt for the same invoice is still waiting, so a salon can be charged more than once for one invoice"
severity: major
status: fixed
found_after_merge: 2026-11-10
found_by: "exploratory: the foundation review of 2026-11-10, by reading the code; not yet reproduced on the running product"
breaks: "PLT-05 (subscription billing); NFR-11 (every payment event is idempotent)"
found_on: 2026-11-10
fixed_in: 926cbca810d43b8e0b8d904f1263c5f8c5fbb171
fix_note: "A charge attempt is recorded only when no earlier attempt for the invoice is still requested (one statement, under the billing pass's subscription lock), so a reminder day retries only after the attempt before it failed; the first reminder day after a failure retries under the earliest occasion not yet used, as a late pass catches up. Reminder emails are unchanged."
developer_note: "Reproduced by SubscriptionBillingIntegrationTest.aReminderRequestsNoChargeWhileAnEarlierAttemptForTheInvoiceIsUnsettled: with the issue attempt still requested, the due-date pass recorded a second full-amount attempt. The existing retry test assumed each reminder retried without the earlier attempt settling; it now fails each attempt before the next reminder day, as the provider would, and keeps its four occasions."
verified_on: null
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

Not yet verified.
