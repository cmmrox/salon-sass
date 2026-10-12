package com.salonplatform.shared.infrastructure;

import java.util.Properties;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;

/**
 * Migrations run as the owning role, so Spring Boot gives Flyway a connection of its own to the same
 * database. That connection is built from the address and the credentials alone, without the
 * driver's limits on every wait; this gives it the same limits as the pool (connect, login and
 * socket timeouts), so that a migration against a silent database fails instead of hanging the
 * start (docs/engineering/backend.md).
 */
@Configuration(proxyBeanMethods = false)
class MigrationDataSourceConfiguration {

    @Bean
    FlywayConfigurationCustomizer migrationConnectionLimits(DataSource applicationDataSource) {
        return configuration -> {
            if (configuration.getDataSource() instanceof AbstractDriverBasedDataSource migration
                    && applicationDataSource instanceof HikariDataSource pool) {
                Properties limits = new Properties();
                limits.putAll(pool.getDataSourceProperties());
                migration.setConnectionProperties(limits);
            }
        };
    }
}
