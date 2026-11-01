package com.salonplatform.catalogue;

import java.util.List;
import java.util.UUID;

/**
 * One variant of a service, as one terms version holds it (CAT-03, decision 095): for example "Long" in a set
 * called "Hair length", with its own price and time.
 *
 * @param variantId the variant's stable id: the same in every terms version while the variant exists, so a
 *     booking (F07) names the variant it was made for
 * @param name up to 30 characters, unique in its set regardless of case
 * @param priceMinor its price in minor units of the version's currency, tax included
 * @param segments its time, in order: an active segment of the variant's time, then the service's buffer when there
 *     is one (decision 074)
 */
public record ServiceVariant(UUID variantId, String name, long priceMinor, List<ServiceSegment> segments) {

    public ServiceVariant {
        segments = List.copyOf(segments);
    }
}
