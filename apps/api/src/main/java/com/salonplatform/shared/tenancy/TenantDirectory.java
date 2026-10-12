package com.salonplatform.shared.tenancy;

import java.util.Optional;
import java.util.UUID;

/**
 * Where a slug is looked up. {@code shared} owns the question and {@code platform}, which owns the
 * tenant registry, answers it; {@code shared} never depends on {@code platform}.
 */
public interface TenantDirectory {

    /**
     * The id of the salon with this slug, when there is one and it is active. A slug that names no
     * salon, and one that names a suspended salon, both give nothing.
     *
     * @param slug a lowercase slug
     * @return the salon's id, or nothing
     * @throws org.springframework.dao.DataAccessException when the registry cannot be read
     */
    Optional<UUID> findActive(String slug);
}
