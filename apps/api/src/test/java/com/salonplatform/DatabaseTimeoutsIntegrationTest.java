package com.salonplatform;

import java.util.Properties;

import javax.sql.DataSource;

import com.salonplatform.testing.IntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every wait on the database has a limit in the configuration the product ships with: for a
 * connection from the pool, for the test that a pooled connection is alive, for opening a
 * connection, and for an answer on one that is open. A wait without one is how a database that
 * goes silent takes the service with it.
 */
class DatabaseTimeoutsIntegrationTest extends IntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void everyWaitOnTheDatabaseHasALimit() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        Properties driver = pool.getDataSourceProperties();

        assertThat(pool.getConnectionTimeout()).as("waiting for a connection from the pool, ms").isBetween(1L, 5_000L);
        assertThat(pool.getValidationTimeout()).as("testing that a pooled connection is alive, ms")
                .isBetween(1L, pool.getConnectionTimeout());
        assertThat(seconds(driver, "connectTimeout")).as("opening a connection, s").isBetween(1, 5);
        assertThat(seconds(driver, "loginTimeout")).as("signing in on a new connection, s").isBetween(1, 5);
        assertThat(seconds(driver, "socketTimeout")).as("waiting for an answer on an open connection, s")
                .isBetween(1, 60);
    }

    /** The driver's setting in seconds, or 0 (which the driver reads as "no limit") when it is not set. */
    private static int seconds(Properties driver, String name) {
        Object value = driver.get(name);
        return (value != null) ? Integer.parseInt(value.toString()) : 0;
    }
}
