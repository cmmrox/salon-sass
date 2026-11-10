package com.salonplatform.shared.persistence;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The application's {@link DatabaseLimits}, and the tenant pool's connections opened with the server's limits:
 * given to the pool after Spring Boot has configured it and before it opens its first connection, so that every
 * connection has them, those of the platform pool too, which copies the tenant pool's driver settings.
 */
@Configuration(proxyBeanMethods = false)
class DatabaseLimitsConfiguration {

    /** The PostgreSQL driver's setting that passes server settings when a connection opens. */
    static final String OPTIONS = "options";

    @Bean
    DatabaseLimits databaseLimits(Environment environment) {
        return DatabaseLimits.from(environment);
    }

    @Bean
    static BeanPostProcessor serverLimitsOnEveryConnection(Environment environment) {
        return new BeanPostProcessor() {

            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource pool && pool.getJdbcUrl() != null) {
                    if (pool.getDataSourceProperties().containsKey(OPTIONS)) {
                        throw new IllegalStateException("The pool's driver options are set by the application"
                                + " (salon.database.*), not by spring.datasource.hikari.data-source-properties");
                    }
                    pool.addDataSourceProperty(
                            OPTIONS, DatabaseLimits.from(environment).connectionOptions());
                }
                return bean;
            }
        };
    }
}
