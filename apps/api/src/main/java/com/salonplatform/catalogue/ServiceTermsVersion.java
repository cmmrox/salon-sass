package com.salonplatform.catalogue;

import java.util.List;
import java.util.UUID;

/**
 * The terms a service is booked on, as one immutable version holds them (CAT-03, CAT-04, BKG-09).
 *
 * <p>A service without variants has one price and one time: {@code priceMinor} and {@code segments}, the lowest and
 * highest values equal to them, and an empty {@code variants}. A service with variants (2 to 10, decision 095) is
 * booked on one of its variants, each with its own price and segments; then {@code priceMinor} and
 * {@code segments} hold the lowest price and the shortest time, so a reader of F05-S01's values sees the "From"
 * values, and the range is given by the four lowest and highest values.
 *
 * @param serviceId the service
 * @param versionId this version, which a booking keeps (F07)
 * @param number this version's number for the service, from 1
 * @param name the service's name as it is now: a name is not part of the terms, so a rename makes no version
 * @param priceMinor the price in minor units of {@code currency}, tax included; with variants, the lowest
 * @param currency the ISO 4217 currency the price was set in: the salon's when the version was made
 * @param segments the service's time, in order: an active segment, then a buffer when there is one; with variants,
 *     the shortest variant's
 * @param bookableOnline whether customers can book the service online now; false once it is deleted
 * @param variantSetName the name of the set of variants ("Hair length"), or null when the service has none
 * @param variants the variants in the salon's order, empty when the service has none
 * @param priceMinMinor the lowest price
 * @param priceMaxMinor the highest price
 * @param durationMinMinutes the shortest customer's time: every segment but the buffer
 * @param durationMaxMinutes the longest customer's time
 * @param deposit the service's deposit: the salon's rule, none, a percentage or an amount
 */
public record ServiceTermsVersion(
        UUID serviceId,
        UUID versionId,
        long number,
        String name,
        long priceMinor,
        String currency,
        List<ServiceSegment> segments,
        boolean bookableOnline,
        String variantSetName,
        List<ServiceVariant> variants,
        long priceMinMinor,
        long priceMaxMinor,
        int durationMinMinutes,
        int durationMaxMinutes,
        ServiceDeposit deposit) {

    public ServiceTermsVersion {
        segments = List.copyOf(segments);
        variants = List.copyOf(variants);
    }
}
