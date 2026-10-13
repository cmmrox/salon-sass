package com.salonplatform.shared.tenancy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The salons background work may act for, known by id rather than by hostname: the outbox
 * dispatcher and the recurring jobs, which set one salon at a time (T-7). {@code shared} owns the
 * question and {@code platform} answers it, through functions that return ids and nothing else of
 * any other salon.
 */
public interface ActiveTenants {

    /**
     * The salon with this id, when it exists and is active.
     *
     * @throws org.springframework.dao.DataAccessException when the registry cannot be read
     */
    Optional<CurrentTenant> findActive(UUID id);

    /**
     * The ids of every active salon, in a stable order.
     *
     * @throws org.springframework.dao.DataAccessException when the registry cannot be read
     */
    List<UUID> activeIds();
}
