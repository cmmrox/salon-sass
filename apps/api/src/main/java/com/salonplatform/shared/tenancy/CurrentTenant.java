package com.salonplatform.shared.tenancy;

import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

/**
 * The salon a request, a delivery or a job run is for, as the server resolved it. The id is never
 * shown to a customer.
 *
 * @param id the tenant's id
 * @param slug the salon's slug: its subdomain on the platform domain
 * @param timeZone the salon's time zone, in which its times are shown (BKG-15)
 * @param readOnly whether billing has made the salon read-only (F03-S02), as the registry said when
 *        it was resolved for this request or this unit of work
 */
public record CurrentTenant(UUID id, String slug, ZoneId timeZone, boolean readOnly) {

    /** A salon with full access. */
    public CurrentTenant(UUID id, String slug, ZoneId timeZone) {
        this(id, slug, timeZone, false);
    }

    public CurrentTenant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(timeZone, "timeZone");
        if (TenantContext.NO_TENANT.equals(id)) {
            throw new IllegalArgumentException("the nil UUID means no salon and cannot be a tenant");
        }
    }
}
