package com.salonplatform.catalogue;

import java.util.List;
import java.util.UUID;

/**
 * The terms a service is booked on, as one immutable version holds them (CAT-04).
 *
 * @param serviceId the service
 * @param versionId this version, which a booking keeps (F07)
 * @param number this version's number for the service, from 1
 * @param name the service's name as it is now: a name is not part of the terms, so a rename makes no version
 * @param priceMinor the price in minor units of {@code currency}, tax included
 * @param currency the ISO 4217 currency the price was set in: the salon's when the version was made
 * @param segments the service's time, in order: an active segment, then a buffer when there is one
 * @param bookableOnline whether customers can book the service online now; false once it is deleted
 */
public record ServiceTermsVersion(
        UUID serviceId,
        UUID versionId,
        long number,
        String name,
        long priceMinor,
        String currency,
        List<ServiceSegment> segments,
        boolean bookableOnline) {

    public ServiceTermsVersion {
        segments = List.copyOf(segments);
    }
}
