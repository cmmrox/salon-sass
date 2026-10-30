package com.salonplatform.identity.inprocess;

import java.time.Instant;
import java.util.UUID;

/**
 * Identity's part of a salon's closure (PLT-06; {@code docs/architecture/operator-console.md}): every
 * staff and customer session of the closed salon is revoked through identity's own guarded function,
 * which refuses a salon that is not closed. Platform never writes identity's tables.
 *
 * <p>In-process only: the revocation joins the caller's platform transaction, so that a salon is
 * closed exactly when its sessions are revoked, and neither happens without the other. If identity is
 * extracted, this becomes an event that identity consumes after the closure commits, not a remote call.
 */
public interface IdentityLifecycle {

    /**
     * Revokes every unrevoked session of a closed salon, in the caller's platform transaction.
     *
     * @return how many sessions were revoked
     * @throws IllegalStateException outside a platform transaction
     */
    int revokeClosedSalonSessions(UUID salonId, Instant at);
}
