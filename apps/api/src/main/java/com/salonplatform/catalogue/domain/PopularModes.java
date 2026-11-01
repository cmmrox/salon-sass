package com.salonplatform.catalogue.domain;

/** How a salon's Popular list is made (CAT-06, decision 095), as stored and as the API names it. */
public final class PopularModes {

    /** The services the salon chose. */
    public static final String CHOSEN = "chosen";

    /** The most booked of the last 90 days, falling back to the chosen ones. */
    public static final String MOST_BOOKED = "most_booked";

    private PopularModes() {}
}
