package com.salonplatform.platform.service;

import com.salonplatform.platform.dto.SalonCreated;
import java.util.UUID;

/**
 * Creating a salon with its first Owner (PLT-01; docs/architecture/operator-console.md). One platform
 * transaction writes the registry row and its provisioning profile, the immutable plan assignment,
 * the first Owner (identity's in-process provisioning contract), the minimal published name and neutral
 * colour (branding's), the platform audit entry and the idempotency receipt; any failure rolls back all
 * of it, so no request can resolve a partly created salon. No network call happens inside it, and the
 * Owner's password is validated and hashed before it opens. The operator is never signed in as the
 * Owner. The console reads salons through {@link SalonDirectoryService}.
 */
public interface SalonProvisioningService {

    record OwnerInput(String name, String email, String password) {}

    /** The subscription a new salon starts with (F03-S02): its monthly amount and payment method. */
    record SubscriptionInput(Long monthlyAmount, String paymentMethod) {}

    record SalonInput(
            String name,
            String slug,
            String country,
            String currency,
            String timeZone,
            String defaultLanguage,
            String contactEmail,
            String contactPhone,
            String address,
            UUID planId,
            OwnerInput owner,
            SubscriptionInput subscription) {}

    /** The result of a creation: whether it was a replay, and the nonsecret result. */
    record Created(boolean replayed, SalonCreated salon) {}

    /**
     * Creates the salon. Authorization comes first (host is checked before this), then the key and the
     * input, then the receipt; a replay with the same request answers the original result.
     */
    Created create(String idempotencyKey, SalonInput input);
}
