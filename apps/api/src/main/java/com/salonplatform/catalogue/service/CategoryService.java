package com.salonplatform.catalogue.service;

import java.util.List;
import java.util.UUID;

/**
 * The salon's categories in its own order (CAT-02, F05-S01): read with {@code catalogue.read}, changed with
 * {@code catalogue.price.change}. Every refusal is a 400 field error or a 409, and nothing changes. Every change is
 * written to the salon's audit trail.
 */
public interface CategoryService {

    /**
     * One category.
     *
     * @param serviceCount how many live services it has
     * @param version its own version, which a rename or delete is made from
     */
    record CategoryView(UUID id, String name, int position, long serviceCount, long version) {}

    /**
     * The categories in the salon's order.
     *
     * @param listVersion the list's version, which a move is made from; raised by every add, delete and move
     */
    record OrderedCategories(long listVersion, List<CategoryView> items) {}

    OrderedCategories list();

    /**
     * Adds a category at the end of the list. 400 on {@code name} when it is not 2 to 30 characters after trimming
     * or another category has it regardless of case; 409 {@code limit_reached} at 50 categories.
     */
    CategoryView add(String name);

    /**
     * Renames a category under the same rules. 404 for an id that is not the salon's; 409 {@code conflict} for a
     * stale version, {@code invalid_state} when the name is unchanged.
     */
    CategoryView rename(UUID id, String name, Long version);

    /**
     * Swaps a category with its neighbour above ({@code up}) or below ({@code down}), at once. 409
     * {@code conflict} for a stale list version, {@code invalid_state} when there is no neighbour that way.
     */
    OrderedCategories move(UUID id, String direction, Long listVersion);

    /**
     * Deletes a category with no services. 409 {@code in_use} while it has services, {@code invalid_state} for
     * the last category, {@code conflict} for a stale version.
     */
    void delete(UUID id, Long version);
}
