package com.salonplatform.branding;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Branding's narrow public provisioning facade (docs/architecture/operator-console.md): the minimal
 * published name and neutral colour that a new salon's workspace hostname needs to resolve. The row
 * is inserted by branding's own {@code branding_bootstrap_brand} function on the platform connection,
 * inside the platform transaction that creates the salon. It is a routing prerequisite, not a brand
 * editor (F04) and not a public salon site.
 */
@Service
public class BrandProvisioning {

    /** Branding's one neutral token for a newly provisioned salon: the platform ink. */
    public static final String NEUTRAL_COLOUR = "#111827";

    private final ObjectProvider<PlatformDatabase> platform;

    public BrandProvisioning(ObjectProvider<PlatformDatabase> platform) {
        this.platform = platform;
    }

    /**
     * Publishes the new salon's display name with the neutral colour.
     *
     * @throws IllegalStateException outside a platform transaction
     */
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
