package com.salonplatform.shared.infrastructure;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.util.DriverDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The platform runtime connection (docs/architecture/operator-console.md, "Platform database
 * boundary and atomic bootstrap"): a separately configured role and a small pool of its own, used
 * explicitly by operator and platform services, and by the identity and branding provisioning
 * facades inside a platform transaction. The default tenant datasource, its JPA transaction manager
 * and the tenant role are untouched: this pool is deliberately not a {@code DataSource} bean, and its
 * transaction manager is not a {@code PlatformTransactionManager} bean, so nothing reaches it by
 * accident.
 *
 * <p>The role must be configured explicitly ({@code salon.platform.datasource.*}); a missing value
 * fails the start rather than falling back to another role. At start the role is refused, like the
 * tenant role, when it is a superuser, can bypass row-level security or owns a table, and when it is
 * the tenant role or the owning role itself. Every wait has the tenant pool's limits, a transaction's
 * statements the request limit, and an unreachable database is found again at the same steady
 * interval.
 */
@Component
public class PlatformDatabase implements SmartInitializingSingleton, DisposableBean {

    private static final String ROLE = """
            SELECT r.rolname AS name, r.rolsuper AS superuser, r.rolbypassrls AS bypass,
                   (SELECT count(*) FROM pg_catalog.pg_class c
                     WHERE c.relowner = r.oid AND c.relkind IN ('r', 'p')
                       AND c.relnamespace NOT IN ('pg_catalog'::regnamespace, 'information_schema'::regnamespace))
                   AS owned_tables
              FROM pg_catalog.pg_roles r
             WHERE r.rolname = current_user
            """;

    private final HikariDataSource pool;

    private final JdbcTemplate jdbc;

    private final TransactionTemplate transactions;

    private final String tenantRole;

    private final String owningRole;

    PlatformDatabase(DataSource tenantDataSource, RequestDatabaseLimit limit, Environment environment) {
        String username = required(environment, "salon.platform.datasource.username");
        String password = required(environment, "salon.platform.datasource.password");
        this.tenantRole = environment.getProperty("spring.datasource.username", "");
        this.owningRole = environment.getProperty("spring.flyway.user", "");
        if (username.equals(tenantRole) || username.equals(owningRole)) {
            throw new IllegalStateException("The platform database role must be a role of its own, neither the "
                    + "tenant role (spring.datasource.username) nor the owning role (spring.flyway.user). "
                    + "See docs/architecture/operator-console.md.");
        }
        if (!(tenantDataSource instanceof HikariDataSource tenantPool)) {
            throw new IllegalStateException("The platform connection copies the tenant pool's limits; that pool is "
                    + "not the expected HikariCP pool");
        }
        pool = new HikariDataSource();
        pool.setPoolName("salon-platform");
        pool.setJdbcUrl(tenantPool.getJdbcUrl());
        pool.setDriverClassName(tenantPool.getDriverClassName());
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(
                environment.getProperty("salon.platform.datasource.maximum-pool-size", Integer.class, 4));
        pool.setMinimumIdle(0);
        pool.setConnectionTimeout(tenantPool.getConnectionTimeout());
        pool.setValidationTimeout(tenantPool.getValidationTimeout());
        pool.setDataSourceProperties(tenantPool.getDataSourceProperties());
        pool.setDataSource(new SteadyReconnectDataSource(
                new DriverDataSource(
                        pool.getJdbcUrl(),
                        pool.getDriverClassName(),
                        pool.getDataSourceProperties(),
                        username,
                        password),
                environment.getRequiredProperty(DatabaseReconnectConfiguration.INTERVAL, Duration.class),
                pool::isClosed));
        jdbc = new JdbcTemplate(pool);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(pool) {
            @Override
            protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
                    throws SQLException {
                super.prepareTransactionalConnection(connection, definition);
                limit.applyTo(connection);
            }
        };
        manager.afterPropertiesSet();
        transactions = new TransactionTemplate(manager);
    }

    private static String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Configure " + name + " (SALON_DB_PLATFORM_"
                    + name.substring(name.lastIndexOf('.') + 1).toUpperCase(Locale.ROOT)
                    + "): the platform runtime role is never inferred");
        }
        return value;
    }

    /** Runs work in one platform transaction, joining one already open on this thread. */
    public <T> T inTransaction(TransactionCallback<T> work) {
        return transactions.execute(work);
    }

    /** Statements on the platform connection; inside {@link #inTransaction} they join its transaction. */
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /**
     * Refuses to run outside a platform transaction: a provisioning facade must take part in the
     * transaction that creates the salon, never commit separately.
     */
    public void requireTransaction() {
        if (TransactionSynchronizationManager.getResource(pool) == null
                || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Provisioning runs inside the platform transaction that creates the salon");
        }
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, Object> role = jdbc.queryForMap(ROLE);
        Object name = role.get("name");
        if (Boolean.TRUE.equals(role.get("superuser"))) {
            throw refused(name, "is a superuser");
        }
        if (Boolean.TRUE.equals(role.get("bypass"))) {
            throw refused(name, "has BYPASSRLS");
        }
        if (((Number) role.get("owned_tables")).longValue() > 0) {
            throw refused(name, "owns tables");
        }
    }

    private static IllegalStateException refused(Object role, String why) {
        return new IllegalStateException("The platform database role '" + role + "' " + why
                + ". Connect as a role that owns no table, is not a superuser and does not have BYPASSRLS "
                + "(salon.platform.datasource.username). See docs/architecture/operator-console.md.");
    }

    @Override
    public void destroy() {
        pool.close();
    }
}
