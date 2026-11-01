package com.salonplatform.catalogue.service.impl;

import com.salonplatform.catalogue.ServiceBookingCounts;
import com.salonplatform.catalogue.domain.CatalogueLimits;
import com.salonplatform.catalogue.domain.PopularModes;
import com.salonplatform.catalogue.entity.Category;
import com.salonplatform.catalogue.entity.SalonService;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The Popular list (CAT-06, F05-S02 AC-6), among the services bookable online only. "Chosen by you": the chosen ones
 * in the menu's order (category order, then name). "Most booked": up to six ranked by {@link ServiceBookingCounts}
 * for the last 90 days, most first, ties in the menu's order; when it reports none of them, the chosen ones.
 */
@Component
class PopularLists {

    /** A service bookable online, in the menu's order, and whether the salon chose it. */
    record Candidate(UUID id, boolean chosen) {}

    private final ServiceBookingCounts counts;
    private final Clock clock;

    PopularLists(ServiceBookingCounts counts, Clock clock) {
        this.counts = counts;
        this.clock = clock;
    }

    /** The services bookable online in the menu's order: their categories' order, then the given name order. */
    static List<Candidate> inMenuOrder(List<Category> categoriesInOrder, List<SalonService> bookableByName) {
        List<Candidate> ordered = new ArrayList<>();
        for (Category category : categoriesInOrder) {
            for (SalonService service : bookableByName) {
                if (category.getId().equals(service.getCategoryId())) {
                    ordered.add(new Candidate(service.getId(), service.isPopular()));
                }
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * The list. Asks the booking counts, in their own transaction, only for "Most booked": call it after the
     * caller's transaction has ended.
     */
    List<UUID> popular(String mode, List<Candidate> candidates) {
        List<UUID> chosen = candidates.stream()
                .filter(Candidate::chosen)
                .map(Candidate::id)
                .limit(CatalogueLimits.MAX_POPULAR)
                .toList();
        if (!PopularModes.MOST_BOOKED.equals(mode)) {
            return chosen;
        }
        Map<UUID, Long> booked =
                counts.countsSince(clock.instant().minus(Duration.ofDays(CatalogueLimits.MOST_BOOKED_DAYS)));
        List<UUID> ranked = candidates.stream()
                .map(Candidate::id)
                .filter(id -> booked.getOrDefault(id, 0L) > 0)
                .sorted(Comparator.comparingLong((UUID id) -> booked.get(id)).reversed())
                .limit(CatalogueLimits.MAX_POPULAR)
                .toList();
        return ranked.isEmpty() ? chosen : ranked;
    }
}
