package com.salonplatform.shared.tenancy;

import java.util.UUID;

/**
 * Verifies a signed access credential without querying tenant data. Identity implements this seam.
 */
public interface SignedTenantVerifier {
    UUID tenantOf(String credential);
}
