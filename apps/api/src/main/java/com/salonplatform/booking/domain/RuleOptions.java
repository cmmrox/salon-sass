package com.salonplatform.booking.domain;

import java.util.List;

/**
 * The choices the salon has for each booking rule (F04-S04 AC-2, AC-3): exactly the prototype's ({@code
 * setRules}). Amount limits are in whole units of the rules' currency, written for rupees (R1's currency) and
 * scaled to minor units by {@link MinorUnits}.
 */
public final class RuleOptions {

    private RuleOptions() {}

    public static final List<Integer> SLOT_INTERVAL_MINUTES = List.of(10, 15, 20, 30);

    public static final List<Integer> MINIMUM_NOTICE_MINUTES = List.of(0, 30, 60, 120, 240, 1440);

    /** Two weeks, and one, two, three and six months. */
    public static final List<Integer> BOOKING_HORIZON_DAYS = List.of(14, 30, 60, 90, 180);

    public static final List<Integer> FREE_CANCELLATION_HOURS = List.of(2, 6, 12, 24, 48);

    /** 0 is not online; null (any number of times) is allowed beside these. */
    public static final List<Integer> RESCHEDULE_LIMITS = List.of(0, 1, 2);

    public static final List<Integer> RESCHEDULE_UNTIL_HOURS = List.of(2, 6, 12, 24);

    public static final int MIN_PERCENT = 1;

    public static final int MAX_PERCENT = 100;

    /** A percentage deposit's cap: at least Rs. 500, with no business maximum. */
    public static final long MIN_CAP_UNITS = 500;

    /** A fixed deposit: Rs. 100 to Rs. 100,000. */
    public static final long MIN_AMOUNT_UNITS = 100;

    public static final long MAX_AMOUNT_UNITS = 100_000;

    /**
     * The largest amount in minor units any client holds exactly: JavaScript's {@code Number.MAX_SAFE_INTEGER},
     * 2^53 - 1 (the lead's bound for the cap, which has no business maximum).
     */
    public static final long MAX_SAFE_MINOR = 9_007_199_254_740_991L;
}
