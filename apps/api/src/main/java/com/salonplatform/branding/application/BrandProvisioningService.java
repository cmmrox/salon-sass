package com.salonplatform.branding.application;

import com.salonplatform.branding.infrastructure.BrandPlatformFunctions;
import com.salonplatform.branding.inprocess.BrandProvisioning;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * The brand row is inserted by branding's own {@code branding_bootstrap_brand} function on the platform
 * connection, inside the platform transaction that creates the salon.
 */
@Service
@RequiredArgsConstructor
class BrandProvisioningService implements BrandProvisioning {

    /** Branding's one neutral token for a newly provisioned salon: the platform ink. */
    private static final String NEUTRAL_COLOUR = "#111827";

    private final ObjectProvider<PlatformDatabase> platform;

    private final BrandPlatformFunctions functions;

    @Override
    public void bootstrapBrand(UUID tenantId, String displayName, Instant at) {
        platform.getObject().requireTransaction();
        functions.bootstrapBrand(tenantId, displayName, NEUTRAL_COLOUR, at);
    }
}
