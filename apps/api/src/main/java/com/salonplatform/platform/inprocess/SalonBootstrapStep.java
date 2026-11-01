package com.salonplatform.platform.inprocess;

import java.time.Instant;
import java.util.UUID;

/**
 * A step another module adds to a new salon's bootstrap: what the salon has from its first moment, such as the
 * catalogue's default categories (F05-S01, decision 093). Platform runs every step after the salon's brand and
 * default branch exist, inside the platform transaction that creates the salon, in the order the implementations
 * declare with {@link org.springframework.core.annotation.Order}; a step that fails rolls the whole salon back.
 *
 * <p>In-process only: a step writes in the caller's platform transaction and never commits on its own, so that a
 * salon exists with everything its steps give it, or not at all. It must use the platform connection and its
 * module's own {@code SECURITY DEFINER} functions; it can see the salon's brand and branch only through SQL in
 * that same transaction.
 */
public interface SalonBootstrapStep {

    /**
     * Gives the new salon what this module gives every salon, in the caller's platform transaction.
     *
     * @param tenantId the salon being created
     * @param at the time of its creation, from the application's clock
     * @throws IllegalStateException outside a platform transaction
     */
    void bootstrap(UUID tenantId, Instant at);
}
