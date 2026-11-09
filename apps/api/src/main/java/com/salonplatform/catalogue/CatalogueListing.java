package com.salonplatform.catalogue;

import java.util.List;
import java.util.UUID;

/**
 * The salon's categories and services at one moment (decision 100).
 *
 * @param categories every category, in the salon's order
 * @param services every service that is not deleted, by name
 */
public record CatalogueListing(List<Category> categories, List<Service> services) {

    /**
     * A category.
     *
     * @param id the category
     * @param name its name
     */
    public record Category(UUID id, String name) {}

    /**
     * A service.
     *
     * @param id the service
     * @param categoryId the category it is in now
     * @param name its name
     * @param bookableOnline whether customers can book it online
     */
    public record Service(UUID id, UUID categoryId, String name, boolean bookableOnline) {}
}
