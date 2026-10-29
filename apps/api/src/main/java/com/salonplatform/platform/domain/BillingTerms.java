package com.salonplatform.platform.domain;

import java.util.Locale;
import java.util.Set;

/**
 * Input rules of subscription billing (docs/architecture/operator-console.md): an operator-entered
 * monthly amount as an integer in minor units from 0 to 100,000,000 (every supported currency has two
 * minor digits), a payment method of {@code card} or {@code bank_transfer}, and the invoice number's
 * display form. No price, tier or discount is inferred (D-06 is open).
 */
public final class BillingTerms {

    public static final long MAX_AMOUNT = 100_000_000L;
    public static final String CARD = "card";
    public static final String BANK_TRANSFER = "bank_transfer";
    public static final Set<String> METHODS = Set.of(CARD, BANK_TRANSFER);
    public static final int REFERENCE_MAX = 120;

    private BillingTerms() {}

    public static boolean validAmount(Long amount) {
        return amount != null && amount >= 0 && amount <= MAX_AMOUNT;
    }

    public static boolean validMethod(String method) {
        return method != null && METHODS.contains(method);
    }

    /** {@code INV-000001}: the platform-wide sequential number, at least six digits. */
    public static String invoiceNumber(long number) {
        return String.format(Locale.ROOT, "INV-%06d", number);
    }

    /** {@code 2,500.00 LKR}-style plain text for emails: minor units with two decimals and the code. */
    public static String plainAmount(long minor, String currency) {
        return String.format(Locale.ROOT, "%s %,d.%02d", currency, minor / 100, minor % 100);
    }
}
