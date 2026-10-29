package com.salonplatform.identity.application;

import com.salonplatform.identity.inprocess.IdentityLifecycle;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
class IdentityLifecycleService implements IdentityLifecycle {

    private final ObjectProvider<PlatformDatabase> platform;

    IdentityLifecycleService(ObjectProvider<PlatformDatabase> platform) {
        this.platform = platform;
    }

    @Override
    public int revokeClosedSalonSessions(UUID salonId, Instant at) {
        PlatformDatabase database = platform.getObject();
        database.requireTransaction();
        Integer revoked = database.jdbcTemplate()
                .queryForObject(
                        "SELECT identity_revoke_closed_salon_sessions(?, ?)",
                        Integer.class,
                        salonId,
                        Timestamp.from(at));
        return revoked == null ? 0 : revoked;
    }
}
