package com.salonplatform.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.salonplatform.shared.infrastructure.PlatformDatabase;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Identity's narrow facade for a salon's closure (PLT-06; docs/architecture/operator-console.md):
 * every staff and customer session of the closed salon is revoked in the closing platform transaction,
 * through identity's own guarded function, which refuses a salon that is not closed. Platform never
 * writes identity's tables.
 */
@Service
public class IdentityLifecycle {

    private final ObjectProvider<PlatformDatabase> platform;

    public IdentityLifecycle(ObjectProvider<PlatformDatabase> platform) {
        this.platform = platform;
    }

    /** Revokes every unrevoked session of a closed salon; returns how many were revoked. */
    public int revokeClosedSalonSessions(UUID salonId, Instant at) {
        PlatformDatabase database = platform.getObject();
        database.requireTransaction();
        Integer revoked = database.jdbc().queryForObject("SELECT identity_revoke_closed_salon_sessions(?, ?)",
                Integer.class, salonId, Timestamp.from(at));
        return revoked == null ? 0 : revoked;
    }
}
