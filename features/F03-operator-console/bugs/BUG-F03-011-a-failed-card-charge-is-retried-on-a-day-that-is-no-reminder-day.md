---
id: BUG-F03-011
title: "A failed card charge is retried by the next billing pass on any day, not on the next reminder day, and a reminder day skipped while an attempt was unsettled is made up later"
severity: minor
status: fixed
found_after_merge: 2026-11-10
found_by: TC-F03-S02-029
breaks: "F03-S02 AC-4 (a failed charge is retried on each reminder day); docs/architecture/operator-console.md, Payment; decision 049 (the reminder days)"
found_on: 2026-11-10
fixed_in: 16eb64e34d562bb2addd4357c8819732e71c0db3
fix_note: "A failed card charge is retried only by the billing pass that sends one of the invoice's reminders, at most once per invoice under the latest reminder it sends, and not while an attempt is in flight; a pass that sends no reminder requests no charge, and a blocked reminder day's retry is not made up."
developer_note: "Reproduced by SubscriptionBillingIntegrationTest.aFailedChargeIsRetriedOnlyByThePassThatSendsAReminderDaysReminder and aLatePassThatSendsSeveralMissedRemindersRetriesOnceUnderTheLatest. Recorded by the lead from the developer's report: the bug file was on QA's branch when the fix was made."
verified_on: null
approved_by: null
found_in_release: null
---

# BUG-F03-011 A failed card charge is retried by the next billing pass on any day, not on the next reminder day, and a reminder day skipped while an attempt was unsettled is made up later

Found while re-verifying BUG-F03-010. The lead ruled the rule on 2026-11-10: a retry is requested only together with a
reminder day's reminder, and only when no attempt is in flight. A reminder day whose retry could not be made because an
attempt was still unsettled is not made up later, and no pass on any other day requests a charge.

## Steps

Account: an operator Admin. Data: a card salon made for the test (TC-F03-S02-029 makes one); the provider fake, and the
run-job hook at the test-clock instant.

1. Suspend the salon, so its charge request waits in the outbox. Cross trial end, so the invoice is issued with one attempt
   (`issue`, requested). Then cross the due date and the 3-day reminder.
2. Set the provider fake to fail, and reactivate the salon. The one charge reaches the provider and fails (`issue`, failed).
3. Run a billing pass one day after the 3-day reminder, which is no reminder day.

## Expected

AC-4 and operator-console.md, "Payment": a failed charge is retried on each reminder day while unpaid. The days are the
due date and 3 and 10 days after it (decision 049). Step 3 requests nothing; the next attempt is requested on the 10-day
reminder.

## Actual

Step 3 requests a retry at once, under the `due` occasion: the attempts read `issue:failed, due:requested`. That happened
1 time in 1, in `qa/reports/20261110-121014-F03-S02-87eae546dd2b` (TC-F03-S02-029, "no retry between reminder days",
`qa/api/F03/S02-billing-cycle.spec.ts:416`).

The fix note of BUG-F03-010 describes this as intended: "the first reminder day after a failure retries under the earliest
occasion not yet used, as a late pass catches up". The pass catches up the due date's and the 3-day reminder's retries,
which could not be made while the first attempt was unsettled. Decision 051's catch-up is about a late pass still sending
the reminders; it never said a charge is made on a day that is not a reminder day.

The behaviour predates the fix: before it, a pass created every passed reminder day's attempt at once. By reading the fix
(`InvoiceIssuing.requestCharge` for each passed reminder occasion with no attempt yet, guarded only by "no attempt
requested"), after a first failure the catch-up can request up to three retries (`due`, `due_3`, `due_10`) on successive
passes, if each fails before the next pass.

## Evidence

- `qa/reports/20261110-121014-F03-S02-87eae546dd2b/001-F03-S02-billing-cycle.log`, failure 2 of TC-F03-S02-029: expected
  `["issue:failed"]`, received `["issue:failed", "due:requested"]` after the pass at the due instant + 4 days.
- The steps before it passed in the same run: one attempt and one charge request across the due date and the 3-day
  reminder (BUG-F03-010's own symptom, verified).
- Code: `apps/api/src/main/java/com/salonplatform/platform/service/impl/InvoiceIssuing.java` (the reminder pass's
  `requestCharge`) and `apps/api/src/main/java/com/salonplatform/platform/repository/InvoiceStore.java` (the `NOT EXISTS`
  guard on a requested attempt), as changed by `926cbca8`.

## Re-verification

Not yet verified. TC-F03-S02-029 keeps its strict check and fails until this is fixed.
