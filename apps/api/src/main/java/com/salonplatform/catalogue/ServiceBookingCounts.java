package com.salonplatform.catalogue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * The question the catalogue asks about bookings to show the salon's "Most booked" services (CAT-06, decision 095):
 * how many bookings each service has had between a moment and now. The catalogue asks for the last 90 days, which
 * end at now by the application's clock (decisions 092 and 095 (c)), so a booking still to come counts for nothing; it
 * ranks the services that are bookable online by it, most first, and shows up to six; when the answer is empty it
 * shows the services the salon chose instead.
 *
 * <p>Declared here and implemented by {@code booking} (F07-S01), so that dependencies point one way ({@code booking}
 * depends on {@code catalogue}; decision 087's pattern). A booking counts once it is confirmed, arrived, in service or
 * completed; a hold, a booking waiting for its payment, a cancellation and a no-show count for nothing. An
 * implementation reads the request's salon, opens its own read-only transaction and does not join the caller's.
 */
public interface ServiceBookingCounts {

    /**
     * For the request's salon, each service that has bookings (in the states that count) starting at or after
     * {@code from} and before now by the application's clock, mapped to how many it has. A booking still to come is not
     * counted. A service with none is absent; another salon's services never appear.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    Map<UUID, Long> countsSince(Instant from);
}
