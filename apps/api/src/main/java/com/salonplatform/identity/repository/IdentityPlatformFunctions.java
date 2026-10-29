package com.salonplatform.identity.repository;

import com.salonplatform.shared.persistence.PlatformDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Identity's {@code SECURITY DEFINER} functions on the platform connection, called inside the platform
 * transaction that creates or closes a salon (docs/architecture/operator-console.md): the platform pool and the
 * definer functions, named direct-SQL exceptions. A function returning nothing is read as zero. Each function is
 * given the salon and sets it for itself; none reaches another salon's rows.
 */
@Component
@RequiredArgsConstructor
public class IdentityPlatformFunctions {

    private final ObjectProvider<PlatformDatabase> platform;

    /** Inserts the salon's first Owner through {@code identity_bootstrap_owner}, which refuses a second one. */
    public void bootstrapOwner(UUID salonId, UUID ownerId, String name, String email, String passwordHash, Instant at) {
        database()
                .jdbc()
                .sql("SELECT identity_bootstrap_owner(?, ?, ?, ?, ?, ?)::text")
                .params(salonId, ownerId, name, email, passwordHash, Timestamp.from(at))
                .query(String.class)
                .optional();
    }

    /** The salon's active staff, counted by {@code identity_active_staff_count}. */
    public long activeStaffCount(UUID salonId) {
        return database()
                .jdbc()
                .sql("SELECT identity_active_staff_count(?)")
                .param(salonId)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    /** Revokes every session of a closed salon through {@code identity_revoke_closed_salon_sessions}. */
    public int revokeClosedSalonSessions(UUID salonId, Instant at) {
        return database()
                .jdbc()
                .sql("SELECT identity_revoke_closed_salon_sessions(?, ?)")
                .params(salonId, Timestamp.from(at))
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    private PlatformDatabase database() {
        return platform.getObject();
    }
}
