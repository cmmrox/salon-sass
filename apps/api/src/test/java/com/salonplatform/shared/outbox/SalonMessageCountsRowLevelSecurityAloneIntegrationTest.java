package com.salonplatform.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * The local hook's message counts ({@link SalonMessageCounts}), with row-level security and the salon the
 * statement names as the only guards: no Hibernate in the path (docs/engineering/backend.md, "Persistence
 * and migrations"). They run as the application's role on a plain connection with only {@code app.tenant_id}
 * set.
 */
class SalonMessageCountsRowLevelSecurityAloneIntegrationTest {

    private static final String SET_TENANT = "SELECT set_config('app.tenant_id', ?, true)";

    private static TestDatabase database;

    private static TestSalon alpha;

    private static TestSalon beta;

    @BeforeAll
    static void twoSalonsWithWaitingMessages() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication _ = database.start()) {
            // Started once to apply the migrations.
        }
        JdbcTemplate owner = database.asOwner();
        TestSalons salons = new TestSalons(owner);
        alpha = salons.create("Alpha Studio", "#D6086A");
        beta = salons.create("Beta Lounge", "#1F2937");
        for (int i = 0; i < 2; i++) {
            waitingMessage(owner, alpha);
        }
        for (int i = 0; i < 3; i++) {
            waitingMessage(owner, beta);
        }
    }

    @Test
    void theMessageCountsReachOnlyTheSalonSetEvenWhenAskedForAnother() throws SQLException {
        Map<String, Long> alphas = asApplication(alpha, client -> new SalonMessageCounts(client).byStatus(alpha.id()));
        Map<String, Long> betasAskedByAlpha =
                asApplication(alpha, client -> new SalonMessageCounts(client).byStatus(beta.id()));
        Map<String, Long> withoutASalon =
                asApplication(null, client -> new SalonMessageCounts(client).byStatus(alpha.id()));

        assertThat(alphas).containsExactly(Map.entry("pending", 2L));
        assertThat(betasAskedByAlpha)
                .as("beta's messages, asked for with alpha set")
                .isEmpty();
        assertThat(withoutASalon).isEmpty();
    }

    private static void waitingMessage(JdbcTemplate owner, TestSalon salon) {
        owner.update("""
                INSERT INTO outbox_message (tenant_id, id, channel, payload, status, next_attempt_at, created_at,
                    updated_at)
                VALUES (?, ?, 'probe', '{}', 'pending', now(), now(), now())
                """, salon.id(), UUID.randomUUID());
    }

    /**
     * Runs the work as the application's role in one transaction on a plain connection, with the salon
     * set for that transaction only (or none), and rolls it back.
     */
    private static <T> T asApplication(TestSalon salon, Function<JdbcClient, T> work) throws SQLException {
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            if (salon != null) {
                try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
                    statement.setString(1, salon.id().toString());
                    statement.execute();
                }
            }
            T result = work.apply(JdbcClient.create(new SingleConnectionDataSource(connection, true)));
            connection.rollback();
            return result;
        }
    }
}
