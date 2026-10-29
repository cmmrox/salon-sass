package com.salonplatform.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestPostgres;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * The product as it is configured opens its pool's connections through
 * {@link SteadyReconnectDataSource}, and that changes nothing about who it connects as or about the
 * limits on its waits.
 */
class DatabaseReconnectIntegrationTest extends IntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @Test
    void thePoolAsksForItsConnectionsThroughTheSteadySourceAtAShortInterval() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);

        assertThat(pool.getDataSource()).isInstanceOf(SteadyReconnectDataSource.class);
        assertThat(environment.getRequiredProperty(DatabaseReconnectConfiguration.INTERVAL, Duration.class))
                .as("how soon a database that answers again is found")
                .isBetween(Duration.ofMillis(1), Duration.ofSeconds(1));
    }

    @Test
    void aConnectionOpenedThatWayIsTheApplicationRolesAndCarriesTheDriversLimits() throws Exception {
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        int socketTimeoutSeconds = Integer.parseInt(
                pool.getDataSourceProperties().get("socketTimeout").toString());

        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            try (ResultSet role = statement.executeQuery("SELECT current_user")) {
                assertThat(role.next()).isTrue();
                assertThat(role.getString(1)).isEqualTo(TestPostgres.APP_USER);
            }
            // The driver was given the pool's data-source-properties: the limit on waiting for an
            // answer is the one set there, not the driver's default of no limit.
            assertThat(connection.getNetworkTimeout())
                    .as("the wait for an answer on an open connection, ms")
                    .isEqualTo(socketTimeoutSeconds * 1000);
        }
    }
}
