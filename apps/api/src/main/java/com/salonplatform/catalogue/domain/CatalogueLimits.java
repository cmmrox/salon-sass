package com.salonplatform.catalogue.domain;

/**
 * The prototype's limits on categories, services, variants, deposits and Popular (F05-S01 AC-2, AC-3; F05-S02 AC-1,
 * AC-5, AC-7; the prototype's {@code seErr} and {@code ceSave}), and on tax settings and products (F05-S03 AC-2,
 * AC-8; {@code setTax} and {@code peErr}). Prices are whole units of the salon's currency, the rupee limits scaled by its fraction digits.
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

    /** A set of variants (F05-S02 AC-1, the prototype's option group): its name, and 2 to 10 variants. */
    public static final int VARIANT_SET_NAME_MIN = 2;

    public static final int VARIANT_SET_NAME_MAX = 30;
    public static final int VARIANTS_MIN = 2;
    public static final int VARIANTS_MAX = 10;
    public static final int VARIANT_NAME_MAX = 30;

    /** A service's own deposit (F05-S02 AC-7): a percentage, or an amount of Rs. 100 to Rs. 100,000. */
    public static final int DEPOSIT_PERCENT_MIN = 1;

    public static final int DEPOSIT_PERCENT_MAX = 100;
    public static final long DEPOSIT_AMOUNT_MIN_UNITS = 100;
    public static final long DEPOSIT_AMOUNT_MAX_UNITS = 100_000;

    /** At most six services are chosen for Popular, and "Most booked" shows up to six (decision 095). */
    public static final int MAX_POPULAR = 6;

    /** "Most booked" counts the last 90 days of bookings (decision 092). */
    public static final int MOST_BOOKED_DAYS = 90;

    /** A product (F05-S03 AC-8, the prototype's {@code peErr}): its name, its price of Rs. 1 to Rs. 500,000. */
    public static final int PRODUCT_NAME_MIN = 2;

    public static final int PRODUCT_NAME_MAX = 40;
    public static final long PRODUCT_PRICE_MIN_UNITS = 1;
    public static final long PRODUCT_PRICE_MAX_UNITS = 500_000;

    /** At most 300 products per salon (decision 095). */
    public static final int MAX_PRODUCTS = 300;

    /** A VAT registration number (F05-S03 AC-2): 1 to 20 letters, digits and hyphens. */
    public static final String VAT_REGISTRATION_NUMBER = "[A-Za-z0-9-]{1,20}";

    /** The lowest service charge a salon may charge; the highest is configured. */
    public static final int SERVICE_CHARGE_MIN = 1;

    private CatalogueLimits() {}
}
