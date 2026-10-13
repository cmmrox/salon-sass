package com.salonplatform.identity;

import java.util.Set;
import java.util.UUID;

/** Opaque after-commit notification: personal values never cross this seam. */
public record CustomerDataErased(UUID tenantId, Set<UUID> challengeIds) {
    public CustomerDataErased {
        challengeIds = Set.copyOf(challengeIds);
    }
}
