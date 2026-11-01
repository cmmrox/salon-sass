package com.salonplatform.catalogue.service;

import java.util.List;
import java.util.UUID;

/**
 * How the salon's Popular list is made (CAT-06, F05-S02 AC-6): the services it chose, or the most booked of the last
 * 90 days, which shows the chosen ones until bookings exist. Read with {@code catalogue.read}, switched with
 * {@code catalogue.price.change}, saved at once; every switch is written to the salon's audit trail.
 */
public interface PopularSettings {

    /**
     * The setting and the list it makes now.
     *
     * @param mode {@code chosen} or {@code most_booked}
     * @param version the version a switch is made from; 0 before the first switch
     * @param popular the Popular services' ids in order, among those bookable online, at most six
     */
    record PopularView(String mode, long version, List<UUID> popular) {

        public PopularView {
            popular = List.copyOf(popular);
        }
    }

    PopularView setting();

    /**
     * Switches the setting. 400 for a mode that is not one of the two or a missing version; 409 {@code conflict}
     * for a stale version, {@code invalid_state} when it is already so.
     */
    PopularView switchTo(String mode, Long version);
}
