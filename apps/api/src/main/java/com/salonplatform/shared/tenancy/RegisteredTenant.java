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
 * @param readOnly whether billing has made the salon read-only (F03-S02): its reads, sign-in and
 *        privacy rights still work, every other mutation is refused
 */
public record RegisteredTenant(UUID id, TenantStatus status, ZoneId timeZone, boolean readOnly) {

    public RegisteredTenant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(timeZone, "timeZone");
    }

    /** A salon with full access. */
    public RegisteredTenant(UUID id, TenantStatus status, ZoneId timeZone) {
        this(id, status, timeZone, false);
    }
}
