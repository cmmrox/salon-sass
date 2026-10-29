package com.salonplatform.shared.infrastructure;

import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

/**
 * The {@code db} component of {@code /actuator/health}. It replaces Spring Boot's own, which asks
 * the connection whether it is valid with no time limit: against a database that accepts
 * connections and no longer answers, that question waits for as long as the database stays silent.
 *
 * <p>This one asks with the limit the pool itself uses to decide that a connection is dead. A
 * connection that does not answer in time fails with an error, which is what makes the pool
 * discard it and open a new one; so the component reports {@code UP} again, with nobody's help, once
 * the database answers.
 */
@Component("dbHealthIndicator")
class DatabaseHealthIndicator extends AbstractHealthIndicator {

    private final DataSource dataSource;

    private final int timeoutMillis;

    DatabaseHealthIndicator(
            DataSource dataSource, @Value("${spring.datasource.hikari.validation-timeout:5000}") int timeoutMillis) {
        super("The database health check failed");
        this.dataSource = dataSource;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            // A limit on this connection's socket for as long as the check has it. The driver does
            // the waiting itself and needs no thread to do it on; the pool puts its own limit back
            // when the connection is returned.
            connection.setNetworkTimeout(Runnable::run, timeoutMillis);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT 1");
            }
        }
        builder.up();
    }
}
