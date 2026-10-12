package com.salonplatform.shared.infrastructure;

import java.time.Duration;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.util.DriverDataSource;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Makes the pool open its connections through {@link SteadyReconnectDataSource}, so that it finds
 * a database that answers again within {@code salon.database.reconnect-interval}, however long the
 * database was unreachable or silent.
 *
 * <p>The driver is reached exactly as the pool would reach it by itself: the same address, the same
 * role, and the same limits on every wait ({@code spring.datasource.hikari.data-source-properties}).
 * The pool is given its source of connections after Spring Boot has configured it and before it
 * opens its first connection.
 */
@Configuration(proxyBeanMethods = false)
class DatabaseReconnectConfiguration {

    static final String INTERVAL = "salon.database.reconnect-interval";

    @Bean
    static BeanPostProcessor steadyReconnect(Environment environment) {
        return new BeanPostProcessor() {

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource pool && pool.getDataSource() == null && pool.getJdbcUrl() != null) {
                    DataSource driver = new DriverDataSource(pool.getJdbcUrl(), pool.getDriverClassName(),
                            pool.getDataSourceProperties(), pool.getUsername(), pool.getPassword());
                    pool.setDataSource(new SteadyReconnectDataSource(driver,
                            environment.getRequiredProperty(INTERVAL, Duration.class), pool::isClosed));
                }
                return bean;
            }
        };
    }
}
