package com.salonplatform.shared.tenancy;

import java.util.Optional;

/**
 * Where a slug is looked up. {@code shared} owns the question and {@code platform}, which owns the
 * tenant registry, answers it; {@code shared} never depends on {@code platform}.
 */
public interface TenantDirectory {

    /**
     * The salon with this slug, active or suspended, or nothing when no salon has it.
     *
     * @param slug a lowercase slug
     * @return the salon's id and status, or nothing
     * @throws org.springframework.dao.DataAccessException when the registry cannot be read
     */
    Optional<RegisteredTenant> find(String slug);
}
