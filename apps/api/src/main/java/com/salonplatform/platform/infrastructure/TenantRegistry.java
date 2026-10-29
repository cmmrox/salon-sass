package com.salonplatform.platform.infrastructure;

import com.salonplatform.shared.infrastructure.RequestDatabaseLimit;
import com.salonplatform.shared.tenancy.ActiveTenants;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.RegisteredTenant;
import com.salonplatform.shared.tenancy.TenantDirectory;
import com.salonplatform.shared.tenancy.TenantStatus;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The tenant registry, as the application reads it: through {@code tenant_by_slug()},
 * {@code tenant_by_id()} and {@code active_tenant_ids()}, the narrow paths its database role has to
 * the {@code tenant} table, which it cannot read directly. One short statement on a pooled
 * connection, under the request's limit on database waits. Nothing is cached: a salon's status is
 * read where it is decided.
 */
@Component
class TenantRegistry implements TenantDirectory, ActiveTenants {

    static final String ACTIVE = "active";

    static final String SUSPENDED = "suspended";

    /** The access state billing sets while an invoice is unpaid 14 days after its due instant (F03-S02). */
    static final String READ_ONLY = "read_only";

    private static final String BY_SLUG = "SELECT id, status, time_zone, access_state FROM tenant_by_slug(?)";

    private static final String BY_ID = "SELECT id, slug, status, time_zone, access_state FROM tenant_by_id(?)";

    private static final String ACTIVE_IDS = "SELECT id FROM active_tenant_ids()";

    private static final String SUSPENDED_IDS = "SELECT id FROM suspended_tenant_ids()";

    /** A salon closed by an Admin (F03-S03): unknown to every request, served nothing. */
    static final String CLOSED = "closed";

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
            try (PreparedStatement statement = connection.prepareStatement(BY_SLUG)) {
                statement.setString(1, slug);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        return Optional.empty();
                    }
                    UUID id = row.getObject("id", UUID.class);
                    ZoneId zone = timeZoneOf(row);
                    boolean readOnly = READ_ONLY.equals(row.getString("access_state"));
                    return statusOf(row.getString("status"))
                            .map(status -> new RegisteredTenant(id, status, zone, readOnly));
                }
            }
        });
    }

    @Override
    public Optional<CurrentTenant> findActive(UUID id) {
        return jdbc.execute((ConnectionCallback<Optional<CurrentTenant>>) connection -> {
            limit.applyTo(connection);
            try (PreparedStatement statement = connection.prepareStatement(BY_ID)) {
                statement.setObject(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next() || statusOf(row.getString("status")).orElse(null) != TenantStatus.ACTIVE) {
                        return Optional.empty();
                    }
                    return Optional.of(new CurrentTenant(
                            row.getObject("id", UUID.class),
                            row.getString("slug"),
                            timeZoneOf(row),
                            READ_ONLY.equals(row.getString("access_state"))));
                }
            }
        });
    }

    @Override
    public Optional<CurrentTenant> findClosed(UUID id) {
        return jdbc.execute((ConnectionCallback<Optional<CurrentTenant>>) connection -> {
            limit.applyTo(connection);
            try (PreparedStatement statement = connection.prepareStatement(BY_ID)) {
                statement.setObject(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next() || !CLOSED.equals(row.getString("status"))) {
                        return Optional.empty();
                    }
                    return Optional.of(new CurrentTenant(
                            row.getObject("id", UUID.class),
                            row.getString("slug"),
                            timeZoneOf(row),
                            READ_ONLY.equals(row.getString("access_state"))));
                }
            }
        });
    }

    @Override
    public List<UUID> suspendedIds() {
        return ids(SUSPENDED_IDS);
    }

    @Override
    public List<UUID> activeIds() {
        return ids(ACTIVE_IDS);
    }

    private List<UUID> ids(String query) {
        return jdbc.execute((ConnectionCallback<List<UUID>>) connection -> {
            limit.applyTo(connection);
            try (PreparedStatement statement = connection.prepareStatement(query);
                    ResultSet rows = statement.executeQuery()) {
                List<UUID> ids = new java.util.ArrayList<>();
                while (rows.next()) {
                    ids.add(rows.getObject("id", UUID.class));
                }
                return List.copyOf(ids);
            }
        });
    }

    /**
     * A closed or deleted salon (F03-S03), like a status this version does not know, is no salon at
     * all: its hostname answers exactly as one that never existed. A salon is only ever served when the
     * registry says it is active.
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

    /** The database accepts only names it knows as IANA time zones; one Java does not know is a fault. */
    private static ZoneId timeZoneOf(ResultSet row) throws SQLException {
        String name = row.getString("time_zone");
        try {
            return ZoneId.of(name);
        } catch (DateTimeException ex) {
            throw new IllegalStateException(
                    "The registry holds a time zone this application does not know: " + name, ex);
        }
    }
}
