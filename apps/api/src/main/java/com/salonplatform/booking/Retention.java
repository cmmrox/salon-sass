package com.salonplatform.booking;

/**
 * What the salon keeps when a customer cancels after the free-cancellation window, or does not come (BKG-09).
 * A no-show keeps the deposit or a share, never nothing. When the rules ask for no deposit, keeping "the
 * deposit" keeps nothing.
 */
public sealed interface Retention permits Retention.Nothing, Retention.Deposit, Retention.Share {

    /** Nothing is kept. Only for a late cancellation. */
    record Nothing() implements Retention {}

    /** The deposit taken when the booking was made is kept. */
    record Deposit() implements Retention {}

    /**
     * A share of the booking's price is kept.
     *
     * @param percent 1 to 100
     */
    record Share(int percent) implements Retention {}
}
