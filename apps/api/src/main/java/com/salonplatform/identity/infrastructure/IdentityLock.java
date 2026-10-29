package com.salonplatform.identity.infrastructure;

import com.salonplatform.shared.tenancy.TenantContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The salon's identity lock, held by every identity use case. Its row is created on the salon's first use
 * with an insert that does nothing when the row exists, which a repository cannot express: two first uses
 * at once must both go on to wait for the lock, not fail on the key. The insert names {@code tenant_id}.
 */
@Component
@RequiredArgsConstructor
public class IdentityLock {

    private static final String CREATE_ROW = """
            INSERT INTO identity_guard (id, tenant_id, created_at, updated_at)
            VALUES (?, ?, ?, ?) ON CONFLICT (tenant_id) DO NOTHING
            """;

    private final JdbcClient jdbc;

    private final IdentityGuardRepository guards;

    /** Holds the current salon's lock until the transaction ends. */
    public void lockCurrentSalon(Instant at) {
        UUID salon = TenantContext.require().id();
        jdbc.sql(CREATE_ROW)
                .params(salon, salon, Timestamp.from(at), Timestamp.from(at))
                .update();
        guards.lockCurrent().orElseThrow(() -> new IllegalStateException("The salon's identity lock row is missing"));
    }
}
