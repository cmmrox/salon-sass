package com.salonplatform.shared.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;

/**
 * Migrations run as the owning role, so Spring Boot gives Flyway a connection of its own to the same
 * database. That connection is built from the address and the credentials alone, without the
 * driver's limits on every wait; this gives it the same limits as the pool (connect, login and
 * socket timeouts), so that a migration against a silent database fails instead of hanging the
 * start (docs/engineering/backend.md). The server's limits on its statements, lock waits and an idle
 * transaction are the migrations' own ({@code salon.database.migration.*}, decision 120), not a request's: a
 * migration may run longer than a request, and waits only so long for a lock behind the running version's
 * transactions rather than holding every later one up behind its own wait.
 */
@Configuration(proxyBeanMethods = false)
class MigrationDataSourceConfiguration {

    @Bean
    FlywayConfigurationCustomizer migrationConnectionLimits(DataSource applicationDataSource, DatabaseLimits limits) {
        return configuration -> {
            if (configuration.getDataSource() instanceof AbstractDriverBasedDataSource migration
                    && applicationDataSource instanceof HikariDataSource pool) {
                Properties connection = new Properties();
                connection.putAll(pool.getDataSourceProperties());
                connection.setProperty(DatabaseLimitsConfiguration.OPTIONS, limits.migrationOptions());
                migration.setConnectionProperties(connection);
            }
        };
    }
}
