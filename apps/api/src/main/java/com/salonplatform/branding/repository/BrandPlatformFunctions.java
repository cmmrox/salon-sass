package com.salonplatform.branding.repository;

import com.salonplatform.shared.persistence.PlatformDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Branding's {@code SECURITY DEFINER} function on the platform connection, called inside the platform transaction
 * that creates a salon (docs/architecture/operator-console.md): the platform pool and a definer function, named
 * direct-SQL exceptions. The function is given the new salon and sets it for itself.
 */
@Component
@RequiredArgsConstructor
public class BrandPlatformFunctions {

    private final ObjectProvider<PlatformDatabase> platform;

    /** Inserts the salon's first brand through {@code branding_bootstrap_brand}, which refuses a second one. */
    public void bootstrapBrand(UUID salonId, String displayName, String colour, Instant at) {
        platform.getObject()
                .jdbc()
                .sql("SELECT branding_bootstrap_brand(?, ?, ?, ?)::text")
                .params(salonId, displayName, colour, Timestamp.from(at))
                .query(String.class)
                .optional();
    }
}
