package com.salonplatform.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.salonplatform.shared.web.DatabaseUnavailable;
import com.salonplatform.testing.IntegrationTest;
import com.salonplatform.testing.TestPostgres;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The database keeps its own limits on the application's work (decision 120): a statement that runs too long
 * and a wait for a lock are stopped by the server, which frees what they held and answers, before the driver's
 * three-second wait gives the connection up; and work that legitimately runs longer (jobs, exports) sets its own
 * longer limits, so the request's do not cap it.
 */
class DatabaseServerLimitsIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PlatformDatabase platform;

    @Autowired
    private Flyway flyway;

    @Test
    void everyTransactionOfEitherPoolHasTheServersLimits() {
        Map<String, String> tenant = transaction().execute(status -> settings(jdbc));
        Map<String, String> platformSettings = platform.inTransaction(status -> settings(platform.jdbc()));

        for (Map<String, String> each : List.of(tenant, platformSettings)) {
            assertThat(each)
                    .containsEntry("statement_timeout", "2500ms")
                    .containsEntry("lock_timeout", "2s")
                    .containsEntry("idle_in_transaction_session_timeout", "10s")
                    .containsEntry("client_connection_check_interval", "2s");
        }
    }

    @Test
    void aStatementPastItsLimitIsStoppedByTheDatabaseBeforeTheDriverGivesUpAndIsAnsweredUnavailable() {
        String marker = "slow-" + UUID.randomUUID();
        long started = System.nanoTime();

        assertThatThrownBy(() -> transaction()
                        .execute(status -> jdbc.sql("SELECT pg_sleep(4), '" + marker + "'")
                                .query()
                                .listOfRows()))
                .satisfies(failure -> {
                    assertThat(DatabaseUnavailable.isCauseOf(failure)).isTrue();
                    assertThat(sqlState(failure)).as("cancelled by the server").isEqualTo("57014");
                });

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .as("stopped at the statement limit, under the request's three-second wait")
                .isLessThan(Duration.ofMillis(2900));
        assertThat(owner().queryForObject(
                                "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND query LIKE ?",
                                Integer.class,
                                "%" + marker + "%"))
                .as("nothing is left running on the server")
                .isZero();
    }

    @Test
    void aWaitForALockEndsAtTheLockLimitAndIsAnsweredUnavailable() throws Exception {
        long key = Math.abs(UUID.randomUUID().getMostSignificantBits());
        try (var holder = new DriverManagerDataSource(
                        TestPostgres.shared().getJdbcUrl(),
                        TestPostgres.shared().getUsername(),
                        TestPostgres.shared().getPassword())
                .getConnection()) {
            try (var statement = holder.createStatement()) {
                statement.execute("SELECT pg_advisory_lock(" + key + ")");
            }
            long started = System.nanoTime();

            assertThatThrownBy(() -> transaction()
                            .execute(status -> jdbc.sql("SELECT pg_advisory_xact_lock(?)::text")
                                    .param(key)
                                    .query()
                                    .listOfRows()))
                    .satisfies(failure -> {
                        assertThat(DatabaseUnavailable.isCauseOf(failure)).isTrue();
                        assertThat(sqlState(failure)).as("lock not granted").isEqualTo("55P03");
                    });
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isBetween(Duration.ofMillis(1900), Duration.ofMillis(2900));
        }
    }

    @Test
    void longWorkSetsItsOwnLimitsSoTheRequestsDoNotCapIt() {
        Map<String, String> tenant = DatabaseLimits.longWork(() -> transaction().execute(status -> {
            jdbc.sql("SELECT pg_sleep(3.2)").query().listOfRows();
            return settings(jdbc);
        }));
        Map<String, String> platformSettings = DatabaseLimits.longWork(() -> platform.inTransaction(status -> {
            platform.jdbc().sql("SELECT pg_sleep(3.2)").query().listOfRows();
            return settings(platform.jdbc());
        }));

        for (Map<String, String> each : List.of(tenant, platformSettings)) {
            assertThat(each)
                    .containsEntry("statement_timeout", "5min")
                    .containsEntry("lock_timeout", "10s")
                    .containsEntry("idle_in_transaction_session_timeout", "1min");
        }
        Map<String, String> after = transaction().execute(status -> settings(jdbc));
        assertThat(after)
                .as("ended with the transaction: the next request's has the request limits")
                .containsEntry("statement_timeout", "2500ms");
    }

    @Test
    void migrationsHaveTheirOwnLimits() throws SQLException {
        try (var connection = flyway.getConfiguration().getDataSource().getConnection();
                var statement = connection.createStatement();
                var rows = statement.executeQuery(
                        "SELECT current_setting('statement_timeout'), current_setting('lock_timeout')")) {
            rows.next();
            assertThat(rows.getString(1)).isEqualTo("25s");
            assertThat(rows.getString(2)).isEqualTo("10s");
        }
    }

    private static Map<String, String> settings(JdbcClient client) {
        return Map.of(
                "statement_timeout", setting(client, "statement_timeout"),
                "lock_timeout", setting(client, "lock_timeout"),
                "idle_in_transaction_session_timeout", setting(client, "idle_in_transaction_session_timeout"),
                "client_connection_check_interval", setting(client, "client_connection_check_interval"));
    }

    private static String setting(JdbcClient client, String name) {
        return client.sql("SELECT current_setting(?)")
                .param(name)
                .query(String.class)
                .single();
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    private static JdbcTemplate owner() {
        return new JdbcTemplate(new DriverManagerDataSource(
                TestPostgres.shared().getJdbcUrl(),
                TestPostgres.shared().getUsername(),
                TestPostgres.shared().getPassword()));
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
