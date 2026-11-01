---
id: BUG-F05-001
title: A salon in another currency is told its price limits in rupees
severity: minor
status: fixed
found_by: exploratory
breaks: NFR-13
found_on: 2026-11-02
fixed_in: b6937a3
fix_note: The four sheet messages take their limits through formatMoney in the lists' currency; LKR wording unchanged.
developer_note: null
verified_on: 2026-11-02
approved_by: null
found_in_release: null
---

# BUG-F05-001 A salon in another currency is told its price limits in rupees

Found while re-verifying the PR #19 currency fix (TC-F05-S01-045). Case to write when it is fixed:
TC-F05-S01-047, deferred in F05-S01's `stage.yaml`, unblocked by this bug.

## Steps

Account: the Owner. Role: Owner. Data: a salon the operator provisions with currency USD and no
services or products.

1. Open Services and press **+ New service**. The field says "Price (USD)", correctly.
2. Type `99` in the price.
3. Open the Products tab, press **+ New product** and type `0` in the price.

## Expected

The limits in the salon's currency: AC-3 scales them for other currencies (decision 091), and
NFR-13 says currency is "configuration, not code". For USD, "Price must be 100 to 500,000 USD" or
as the lead words it; the API does apply the limits in whole USD.

## Actual

The sheets say "Price must be Rs. 100 to Rs. 500,000." and "Price must be Rs. 1 to Rs. 500,000."
beside a field labelled "Price (USD)". Happens every time. Amounts stored and shown are correct: this
is the wording only.

Four messages in `apps/web/messages/en.json` hard-code it:
`services.sheet.problems.price`, `.variantPrice`, `.depositAmount` and
`services.productSheet.problems.price`. The service and product price messages were seen on the
running product; the variant and deposit ones were read from the file. "Corrections at close" gives the rupee wording for the launch market; it
does not say what another currency reads.

## Evidence

Observed on build 8ddb623 with a throwaway check, not committed: the service sheet's problem list
read `Price must be Rs. 100 to Rs. 500,000.` and the product sheet's `Price must be Rs. 1 to Rs. 500,000.`
in a USD salon.

## Re-verification

On build 25b1381, tests 7000ac9, TC-F05-S01-047 (AC-19) passed in
`qa/reports/20261102-F05-reverify-7000ac9`: a USD salon reads the service price, a variant's price, a
fixed deposit and a product's price in USD, and each message clears at the limit where the case
checks; an LKR salon keeps "Price must be Rs. 100 to Rs. 500,000.".
