package com.salonplatform.branding.inprocess;

import java.time.Instant;
import java.util.UUID;

/**
 * Branding's provisioning contract for platform ({@code docs/architecture/operator-console.md}): the
 * minimal published name and neutral colour that a new salon's workspace hostname needs to resolve, and
 * the salon's default branch with its empty profile drafts (F04-S01, decision 083). It is a routing
 * prerequisite, not a brand editor and not a public salon site. Platform never writes branding's tables.
 *
 * <p>In-process only: the brand is inserted in the caller's platform transaction and never commits on
 * its own, so that a salon and its brand exist together or not at all. If branding is extracted, this
 * becomes an event that branding consumes after the salon is created, with a compensating action, not
 * a remote call.
 */
public interface BrandProvisioning {

    /**
     * Publishes the new salon's display name with the neutral colour, and gives the salon its default
     * branch, named after it, with empty profile drafts, all in the caller's platform transaction.
     *
     * @throws IllegalStateException outside a platform transaction
     */
    void bootstrapBrand(UUID tenantId, String displayName, Instant at);
}
