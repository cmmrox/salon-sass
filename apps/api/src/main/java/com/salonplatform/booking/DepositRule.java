package com.salonplatform.booking;

/**
 * What a booking asks the customer to pay when it is made (BKG-09): nothing, a share of the price up to a cap,
 * or a fixed amount. Amounts are minor units of the currency of the rules they belong to.
 *
 * <p>A value type, so that a service can carry its own (the per-service override F05 sets) and
 * {@link BookingPolicies#depositFor} can choose between the two without the salon's rules changing.
 */
public sealed interface DepositRule permits DepositRule.None, DepositRule.Percentage, DepositRule.Amount {

    /** No deposit: the customer pays at the salon. */
    record None() implements DepositRule {}

    /**
     * A share of the booking's price, never more than the cap.
     *
     * @param percent 1 to 100
     * @param capMinor the most it can be, in minor units
     */
    record Percentage(int percent, long capMinor) implements DepositRule {}

    /**
     * The same amount for every booking.
     *
     * @param amountMinor the amount, in minor units
     */
    record Amount(long amountMinor) implements DepositRule {}
}
