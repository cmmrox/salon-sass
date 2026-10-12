package com.salonplatform.platform.infrastructure;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;

import com.salonplatform.shared.infrastructure.RequestDatabaseLimit;
import com.salonplatform.shared.tenancy.RegisteredTenant;
import com.salonplatform.shared.tenancy.TenantDirectory;
import com.salonplatform.shared.tenancy.TenantStatus;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The tenant registry, as the application reads it: through {@code tenant_by_slug()}, the one narrow
 * path its database role has to the {@code tenant} table, which it cannot read directly. One short
 * statement on a pooled connection, under the request's limit on database waits. Nothing is cached:
 * a salon's status is read where it is decided.
 */
@Component
class TenantRegistry implements TenantDirectory {

    static final String ACTIVE = "active";

    static final String SUSPENDED = "suspended";

    private static final String LOOKUP = "SELECT id, status FROM tenant_by_slug(?)";

    private final JdbcTemplate jdbc;

    private final RequestDatabaseLimit limit;

    TenantRegistry(JdbcTemplate jdbc, RequestDatabaseLimit limit) {
        this.jdbc = jdbc;
        this.limit = limit;
    }

    @Override
    public Optional<RegisteredTenant> find(String slug) {
        return jdbc.execute((ConnectionCallback<Optional<RegisteredTenant>>) connection -> {
            limit.applyTo(connection);
            try (PreparedStatement statement = connection.prepareStatement(LOOKUP)) {
                statement.setString(1, slug);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        return Optional.empty();
                    }
                    UUID id = row.getObject("id", UUID.class);
                    return statusOf(row.getString("status")).map(status -> new RegisteredTenant(id, status));
                }
            }
        });
    }

    /**
     * The database allows these two and no other. A status this version does not know is treated
     * as no salon at all, so that a salon is only ever served when the registry says it is active.
     */
    private static Optional<TenantStatus> statusOf(String status) {
        if (ACTIVE.equals(status)) {
            return Optional.of(TenantStatus.ACTIVE);
        }
        if (SUSPENDED.equals(status)) {
            return Optional.of(TenantStatus.SUSPENDED);
        }
        return Optional.empty();
    }
}
