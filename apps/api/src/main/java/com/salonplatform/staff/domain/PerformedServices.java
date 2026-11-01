package com.salonplatform.staff.domain;

import com.salonplatform.catalogue.CatalogueListing;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What a person performs, worked out when read (decisions 099, 100): the whole categories and single services held for
 * them, against what {@code catalogue} lists now. A whole category means every service in it, including any added
 * later. A service moved to another category follows the category rules at once, and a deleted one disappears.
 */
public final class PerformedServices {

    private PerformedServices() {}

    /**
     * Whole categories and single services.
     *
     * @param categoryIds the categories performed whole
     * @param serviceIds the single services, none of them in a category performed whole
     */
    public record Choice(Set<UUID> categoryIds, Set<UUID> serviceIds) {

        public Choice {
            categoryIds = Set.copyOf(categoryIds);
            serviceIds = Set.copyOf(serviceIds);
        }
    }

    /**
     * What is held, as it reads now: a stale id is dropped, and so is a single service that has moved into a category
     * performed whole, so that what is read can be sent back as it is.
     */
    public static Choice current(CatalogueListing listing, Collection<UUID> categoryIds, Collection<UUID> serviceIds) {
        Set<UUID> knownCategories = categoryIdsOf(listing);
        Set<UUID> whole = categoryIds.stream().filter(knownCategories::contains).collect(Collectors.toSet());
        Set<UUID> single = listing.services().stream()
                .filter(service -> serviceIds.contains(service.id()))
                .filter(service -> !whole.contains(service.categoryId()))
                .map(CatalogueListing.Service::id)
                .collect(Collectors.toSet());
        return new Choice(whole, single);
    }

    /**
     * Whether a request's choice may be stored: every id is one of the salon's categories or services, and no single
     * service is inside a category also sent whole.
     */
    public static boolean acceptable(
            CatalogueListing listing, Collection<UUID> categoryIds, Collection<UUID> serviceIds) {
        if (!categoryIdsOf(listing).containsAll(categoryIds)) {
            return false;
        }
        Set<UUID> known =
                listing.services().stream().map(CatalogueListing.Service::id).collect(Collectors.toSet());
        if (!known.containsAll(serviceIds)) {
            return false;
        }
        return listing.services().stream()
                .filter(service -> serviceIds.contains(service.id()))
                .noneMatch(service -> categoryIds.contains(service.categoryId()));
    }

    /** The ids of the services this choice performs, in the listing's order. */
    public static Set<UUID> performed(CatalogueListing listing, Choice choice) {
        return listing.services().stream()
                .filter(service -> choice.categoryIds().contains(service.categoryId())
                        || choice.serviceIds().contains(service.id()))
                .map(CatalogueListing.Service::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<UUID> categoryIdsOf(CatalogueListing listing) {
        return listing.categories().stream().map(CatalogueListing.Category::id).collect(Collectors.toSet());
    }
}
