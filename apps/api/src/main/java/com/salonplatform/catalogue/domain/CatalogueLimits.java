package com.salonplatform.catalogue.domain;

/**
 * The prototype's limits on categories and services (F05-S01 AC-2, AC-3; the prototype's {@code seErr} and
 * {@code ceSave}). Prices are whole units of the salon's currency, the rupee limits scaled by its fraction digits.
 */
public final class CatalogueLimits {

    public static final int CATEGORY_NAME_MIN = 2;
    public static final int CATEGORY_NAME_MAX = 30;
    public static final int MAX_CATEGORIES = 50;

    public static final int SERVICE_NAME_MIN = 2;
    public static final int SERVICE_NAME_MAX = 40;
    public static final int DESCRIPTION_MAX = 90;
    public static final long PRICE_MIN_UNITS = 100;
    public static final long PRICE_MAX_UNITS = 500_000;
    public static final int DURATION_MIN = 5;
    public static final int DURATION_MAX = 480;
    public static final int BUFFER_MAX = 60;
    /** Time and buffer are whole multiples of this many minutes. */
    public static final int MINUTE_STEP = 5;

    public static final int MAX_SERVICES = 300;

    private CatalogueLimits() {}
}
