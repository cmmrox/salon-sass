package com.salonplatform.shared.tenancy;

import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * A salon as the registry knows it: that it exists, whether it is open, and its time zone.
 *
 * @param id the tenant's id
 * @param status whether the salon is active or suspended
 * @param timeZone the salon's time zone
 */
public record RegisteredTenant(UUID id, TenantStatus status, ZoneId timeZone) {

    public RegisteredTenant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(timeZone, "timeZone");
    }
}
