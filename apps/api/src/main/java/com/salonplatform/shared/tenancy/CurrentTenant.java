package com.salonplatform.shared.tenancy;

import java.util.Objects;
import java.util.UUID;

/**
 * The salon a request is for, as the server resolved it. The id is never shown to a customer.
 *
 * @param id the tenant's id
 * @param slug the salon's slug: its subdomain on the platform domain
 */
public record CurrentTenant(UUID id, String slug) {

    public CurrentTenant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(slug, "slug");
        if (TenantContext.NO_TENANT.equals(id)) {
            throw new IllegalArgumentException("the nil UUID means no salon and cannot be a tenant");
        }
    }
}
