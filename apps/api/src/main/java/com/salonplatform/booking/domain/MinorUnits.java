package com.salonplatform.booking.domain;

import java.util.Currency;

/**
 * Whole units of a currency as minor units (rupees as cents): the factor is 10 to the currency's ISO 4217
 * fraction digits, so 100 for LKR. A currency with none, or one the JDK does not know, counts in whole units.
 */
public record MinorUnits(String currency, long factor) {

    public static MinorUnits of(String currency) {
        int digits;
        try {
            digits = Math.max(0, Currency.getInstance(currency).getDefaultFractionDigits());
        } catch (IllegalArgumentException ex) {
            digits = 0;
        }
        long factor = 1;
        for (int i = 0; i < digits; i++) {
            factor *= 10;
        }
        return new MinorUnits(currency, factor);
    }

    /** Whole units in minor units. */
    public long minor(long units) {
        return Math.multiplyExact(units, factor);
    }

    /** Whether an amount in minor units is a whole number of units (whole rupees). */
    public boolean whole(long minor) {
        return minor % factor == 0;
    }
}
