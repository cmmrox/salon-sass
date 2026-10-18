package com.salonplatform.shared.tenancy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A verified, live support session behind the current request (F03-S04): an operator reading one
 * salon's workspace, read-only, until its expiry. It never becomes a staff session.
 *
 * @param sessionId the support session
 * @param salonId the one salon it may read
 * @param operatorId the operator who started it
 * @param expiresAt when it ends at the latest
 */
public record SupportGrant(UUID sessionId, UUID salonId, UUID operatorId, Instant expiresAt) {

    public SupportGrant {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(salonId, "salonId");
        Objects.requireNonNull(operatorId, "operatorId");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
