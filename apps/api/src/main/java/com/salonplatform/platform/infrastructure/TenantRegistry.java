package com.salonplatform.platform.infrastructure;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;

import com.salonplatform.shared.infrastructure.RequestDatabaseLimit;
import com.salonplatform.shared.tenancy.TenantDirectory;

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

    private static final String LOOKUP = "SELECT id, status FROM tenant_by_slug(?)";

    private final JdbcTemplate jdbc;

    private final RequestDatabaseLimit limit;

    TenantRegistry(JdbcTemplate jdbc, RequestDatabaseLimit limit) {
        this.jdbc = jdbc;
        this.limit = limit;
    }

    @Override
    public Optional<UUID> findActive(String slug) {
        return jdbc.execute((ConnectionCallback<Optional<UUID>>) connection -> {
            limit.applyTo(connection);
            try (PreparedStatement statement = connection.prepareStatement(LOOKUP)) {
                statement.setString(1, slug);
                try (ResultSet row = statement.executeQuery()) {
                    if (row.next() && ACTIVE.equals(row.getString("status"))) {
                        return Optional.of(row.getObject("id", UUID.class));
                    }
                    return Optional.empty();
                }
            }
        });
    }
}
