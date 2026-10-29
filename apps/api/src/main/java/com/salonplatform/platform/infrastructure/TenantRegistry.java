package com.salonplatform.platform.infrastructure;

import com.salonplatform.shared.persistence.RequestDatabaseLimit;
import com.salonplatform.shared.tenancy.ActiveTenants;
import com.salonplatform.shared.tenancy.CurrentTenant;
import com.salonplatform.shared.tenancy.RegisteredTenant;
import com.salonplatform.shared.tenancy.TenantDirectory;
import com.salonplatform.shared.tenancy.TenantStatus;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.stereotype.Component;

/**
 * The tenant registry, as the application reads it: through {@code tenant_by_slug()},
 * {@code tenant_by_id()} and {@code active_tenant_ids()}, the narrow paths its database role has to
 * the {@code tenant} table, which it cannot read directly: named direct-SQL exception 2 of
 * docs/engineering/backend.md. One short statement on a pooled connection (the transaction's own, inside
 * one), under the request's limit on database waits. Nothing is cached: a salon's status is read where it
 * is decided.
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

    private final JdbcClient jdbc;

    TenantRegistry(DataSource tenantDataSource, RequestDatabaseLimit limit) {
        this.jdbc = JdbcClient.create(new LimitedConnections(tenantDataSource, limit));
    }

    @Override
    public Optional<RegisteredTenant> find(String slug) {
        return jdbc
                .sql(BY_SLUG)
                .param(slug)
                .query((row, n) -> {
                    UUID id = row.getObject("id", UUID.class);
                    ZoneId zone = timeZoneOf(row.getString("time_zone"));
                    boolean readOnly = READ_ONLY.equals(row.getString("access_state"));
                    return statusOf(row.getString("status"))
                            .map(status -> new RegisteredTenant(id, status, zone, readOnly));
                })
                .list()
                .stream()
                .findFirst()
                .flatMap(found -> found);
    }

    @Override
    public Optional<CurrentTenant> findActive(UUID id) {
        return byId(id).filter(row -> statusOf(row.status()).orElse(null) == TenantStatus.ACTIVE)
                .map(ById::tenant);
    }

    @Override
    public Optional<CurrentTenant> findClosed(UUID id) {
        return byId(id).filter(row -> CLOSED.equals(row.status())).map(ById::tenant);
    }

    /** A registry row by id, its time zone checked only once the status says the salon is wanted. */
    private record ById(UUID id, String slug, String status, String timeZone, String accessState) {

        CurrentTenant tenant() {
            return new CurrentTenant(id, slug, timeZoneOf(timeZone), READ_ONLY.equals(accessState));
        }
    }

    private Optional<ById> byId(UUID id) {
        return jdbc
                .sql(BY_ID)
                .param(id)
                .query((row, n) -> new ById(
                        row.getObject("id", UUID.class),
                        row.getString("slug"),
                        row.getString("status"),
                        row.getString("time_zone"),
                        row.getString("access_state")))
                .list()
                .stream()
                .findFirst();
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
        return List.copyOf(jdbc.sql(query)
                .query((row, n) -> row.getObject("id", UUID.class))
                .list());
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
    private static ZoneId timeZoneOf(String name) {
        try {
            return ZoneId.of(name);
        } catch (DateTimeException ex) {
            throw new IllegalStateException(
                    "The registry holds a time zone this application does not know: " + name, ex);
        }
    }

    /**
     * The tenant pool's connections, each with the request's limit on database waits put on it before a
     * statement runs. Inside a transaction it is the transaction's own connection, released as the
     * transaction decides.
     */
    private static final class LimitedConnections extends TransactionAwareDataSourceProxy {

        private final RequestDatabaseLimit limit;

        LimitedConnections(DataSource tenantDataSource, RequestDatabaseLimit limit) {
            super(tenantDataSource);
            this.limit = limit;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            limit.applyTo(connection);
            return connection;
        }
    }
}
