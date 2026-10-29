package com.salonplatform.branding.application;

import com.salonplatform.branding.inprocess.BrandProvisioning;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The brand row is inserted by branding's own {@code branding_bootstrap_brand} function on the platform
 * connection, inside the platform transaction that creates the salon.
 */
@Service
class BrandProvisioningService implements BrandProvisioning {

    /** Branding's one neutral token for a newly provisioned salon: the platform ink. */
    private static final String NEUTRAL_COLOUR = "#111827";

    private final ObjectProvider<PlatformDatabase> platform;

    BrandProvisioningService(ObjectProvider<PlatformDatabase> platform) {
        this.platform = platform;
    }

    @Override
    public void bootstrapBrand(UUID tenantId, String displayName, Instant at) {
        PlatformDatabase database = platform.getObject();
        database.requireTransaction();
        database.jdbc()
                .queryForObject(
                        "SELECT branding_bootstrap_brand(?, ?, ?, ?)::text",
                        String.class,
                        tenantId,
                        displayName,
                        NEUTRAL_COLOUR,
                        Timestamp.from(at));
    }
}
