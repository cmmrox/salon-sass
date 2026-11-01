package com.salonplatform.catalogue.service;

import com.salonplatform.catalogue.ServiceSegment;
import java.util.List;
import java.util.UUID;

/**
 * The salon's services (CAT-01, CAT-04, F05-S01): read with {@code catalogue.read}, changed with
 * {@code catalogue.price.change}. A change of price, time or buffer creates a new immutable terms version and makes
 * it current; nothing else does. A broken rule is a 400 naming each field, and nothing is saved. Every change is
 * written to the salon's audit trail.
 */
public interface SalonServices {

    /**
     * A service as a request sends it, checked here. Wrappers, so that a missing value is told apart.
     *
     * @param priceMinor minor units of the salon's currency, a whole number of units
     * @param bookableOnline null on a creation means on
     */
    record ServiceInput(
            String name,
            UUID categoryId,
            String description,
            Long priceMinor,
            Integer durationMinutes,
            Integer bufferMinutes,
            Boolean bookableOnline) {}

    /** A service's current terms. */
    record TermsView(UUID versionId, long number, long priceMinor, String currency, List<ServiceSegment> segments) {

        public TermsView {
            segments = List.copyOf(segments);
        }
    }

    /**
     * A live service.
     *
     * @param description null when it has none
     * @param photoId its photo, null when it has none
     * @param version its own version, which a change is made from
     */
    record ServiceView(
            UUID id,
            String name,
            UUID categoryId,
            String description,
            boolean bookableOnline,
            UUID photoId,
            long version,
            TermsView terms) {}

    /** Every live service of the salon, by name. */
    List<ServiceView> list();

    /** 404 for an id that is not one of the salon's live services. */
    ServiceView get(UUID id);

    /**
     * Creates a service with its first terms version. 400 naming each bad field (a category that is not the
     * salon's included); 409 {@code limit_reached} at 300 services.
     */
    ServiceView create(ServiceInput input);

    /**
     * Saves every field of a service. 409 {@code conflict} for a stale version, {@code invalid_state} when nothing
     * changed. A new terms version only when the price, time or buffer changed.
     */
    ServiceView update(UUID id, ServiceInput input, Long version);

    /**
     * Copies a service, hidden online, named "(copy)" and upward, with its own first terms version and its own
     * copy of the photo. 409 {@code limit_reached} at 300 services.
     */
    ServiceView duplicate(UUID id);

    /** Changes only whether it is bookable online. 409 {@code conflict} or {@code invalid_state} as above. */
    ServiceView showOnline(UUID id, Boolean bookableOnline, Long version);

    /**
     * Deletes a service no upcoming booking uses: it leaves every list and frees its name, and its terms versions
     * stay readable. 409 {@code in_use} while upcoming bookings use it, {@code conflict} for a stale version.
     */
    void delete(UUID id, Long version);
}
