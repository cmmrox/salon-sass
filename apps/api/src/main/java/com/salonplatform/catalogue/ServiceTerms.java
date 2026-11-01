package com.salonplatform.catalogue;

import java.util.Optional;
import java.util.UUID;

/**
 * A service's bookable terms, as F07 books against them (CAT-04, decision 093). This is the only way another
 * module reads a service's price and time.
 *
 * <p>Every change of a service's price, time or buffer creates a new immutable terms version and makes it current.
 * A booking stores the {@link ServiceTermsVersion#versionId()} it was made on and later reads it with
 * {@link #version}, so a change the salon makes afterwards never rewrites the terms an existing booking keeps. A
 * version stays readable after its service is deleted.
 *
 * <p>The salon is the request's, never a parameter (docs/engineering/backend.md, "Provided interfaces"). Each call
 * opens its own read-only tenant transaction and does not join the caller's.
 */
public interface ServiceTerms {

    /**
     * The current terms of one of the salon's services. Empty for a deleted service, for another salon's service
     * and for an id that names no service: the three cannot be told apart.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    Optional<ServiceTermsVersion> current(UUID serviceId);

    /**
     * A terms version of the salon's services by its id, current or not, its service deleted or not, with exactly
     * the price and time it was made with. Another salon's version id is empty, exactly as an id that names no
     * version.
     *
     * @throws com.salonplatform.shared.tenancy.TenantNotFoundException when the request names no salon
     */
    Optional<ServiceTermsVersion> version(UUID versionId);
}
