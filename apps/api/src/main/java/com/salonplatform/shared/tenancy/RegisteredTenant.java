package com.salonplatform.shared.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * A salon as the registry knows it: that it exists, and whether it is open.
 *
 * @param id the tenant's id
 * @param status whether the salon is active or suspended
 */
public record RegisteredTenant(UUID id, TenantStatus status) {

    public RegisteredTenant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
    }
}
