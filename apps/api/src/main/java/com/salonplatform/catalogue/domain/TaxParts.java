package com.salonplatform.catalogue.domain;

import com.salonplatform.catalogue.PriceParts;

/**
 * What a tax-inclusive price includes (CAT-05, decision 095, F05-S03 AC-4). VAT is charged on the price including
 * the service charge, so it is taken out first: for a price P in minor units, VAT rate v and service charge s where
 * they apply, {@code beforeVat = round(P / (1 + v))}, {@code vat = P - beforeVat}, {@code net = round(beforeVat /
 * (1 + s))}, {@code serviceCharge = beforeVat - net}, rounding half to even. Each part is the difference of two whole
 * amounts, so the parts add up to P exactly. Rates are whole percentages; 0 is a part that does not apply.
 */
public final class TaxParts {

    private static final long HUNDRED = 100;

    private TaxParts() {}

    /**
     * @param priceMinor the price, not negative
     * @param vatPercent the VAT rate that applies, 0 when none does
     * @param serviceChargePercent the service charge that applies, 0 when none does
     */
    public static PriceParts of(long priceMinor, int vatPercent, int serviceChargePercent) {
        if (priceMinor < 0 || vatPercent < 0 || serviceChargePercent < 0) {
            throw new IllegalArgumentException("a price and its rates are never negative");
        }
        long beforeVat = removed(priceMinor, vatPercent);
        long net = removed(beforeVat, serviceChargePercent);
        return new PriceParts(priceMinor, net, beforeVat - net, priceMinor - beforeVat);
    }

    /** {@code amount / (1 + percent / 100)}, rounded half to even. */
    private static long removed(long amount, int percent) {
        if (percent == 0) {
            return amount;
        }
        return halfEven(Math.multiplyExact(amount, HUNDRED), HUNDRED + percent);
    }

    /** {@code dividend / divisor} for a dividend not negative and a positive divisor, rounded half to even. */
    static long halfEven(long dividend, long divisor) {
        long quotient = dividend / divisor;
        long twiceRemainder = 2 * (dividend % divisor);
        if (twiceRemainder > divisor || (twiceRemainder == divisor && quotient % 2 != 0)) {
            return quotient + 1;
        }
        return quotient;
    }
}
