package com.salonplatform.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Platform review M3: what is due across salons is read from one index of waiting messages, which a trigger keeps
 * exact in the transaction of each change, instead of visiting every salon's messages on every poll of every
 * instance. On a database owned by an ordinary role, as everywhere but a developer's machine, so that forced
 * row-level security holds for the owner the functions and the trigger run as. The test writes messages as the
 * container's superuser; the trigger runs as the ordinary owner whatever role writes.
 */
class OutboxDueIndexIntegrationTest {

    private static TestDatabase database;

    private static JdbcTemplate owner;

    private static TestSalons salons;

    private static final List<TestSalon> idle = new ArrayList<>();

    private static TestSalon busy;

    @BeforeAll
    static void manySalonsOnlyOneOfWhichHasWork() {
        database = TestDatabase.createEmptyOwnedByAnOrdinaryRole(TestPostgres.shared());
        try (StartedApplication _ = database.start()) {
            // Started once to apply the migrations as the owning role.
        }
        owner = database.asOwner();
        salons = new TestSalons(owner);
        for (int i = 0; i < 30; i++) {
            idle.add(salons.create("Due Index Idle " + i, "#111111"));
        }
        busy = salons.create("Due Index Busy", "#222222");
    }

    @AfterAll
    static void forget() {
        idle.clear();
    }

    @Test
    void askingWhatIsDueReadsTheIndexAndNoSalonsMessages() throws SQLException {
        insert(busy, "probe", null, "pending", "2026-10-12T07:00:00Z", null);
        insert(busy, "event", "probe.Noted.v1", "pending", "2026-10-12T07:00:00Z", null);

        try (Connection connection = database.connectAsApplication()) {
            Map<String, Long> before = scans(connection);

            assertThat(rows(connection, "SELECT * FROM outbox_due('2026-10-12T08:00:00Z', ARRAY['probe'], 10, 100)"))
                    .isEqualTo(1);
            assertThat(
                            rows(
                                    connection,
                                    "SELECT * FROM outbox_due_events('2026-10-12T08:00:00Z', ARRAY['probe.Noted.v1'], 10, 100)"))
                    .isEqualTo(1);
            assertThat(rows(connection, "SELECT * FROM outbox_backlog('2026-10-12T08:00:00Z')"))
                    .isEqualTo(1);
            assertThat(rows(
                            connection,
                            "SELECT * FROM outbox_backlog('2026-10-12T08:00:00Z', ARRAY['probe.Noted.v1'])"))
                    .isEqualTo(1);

            Map<String, Long> after = scans(connection);
            assertThat(after.get("outbox_message"))
                    .as("no salon's messages were scanned, whatever the number of salons")
                    .isEqualTo(before.get("outbox_message"));
            assertThat(after.get("shared_outbox_due")).isGreaterThan(before.get("shared_outbox_due"));
        }
    }

    @Test
    void theIndexFollowsEachMessageThroughItsStatesAndForgetsItWhenItIsDone() {
        TestSalon salon = idle.getFirst();
        UUID message = insert(salon, "probe", null, "pending", "2026-10-12T07:00:00Z", null);
        assertThat(indexed(salon, message)).containsEntry("status", "pending").containsEntry("event_type", null);

        update(
                salon,
                message,
                "status = 'sending', lease_until = '2026-10-12T08:01:00Z', lease_token = gen_random_uuid()");
        assertThat(indexed(salon, message))
                .as("in flight: due again when its lease runs out")
                .containsEntry("status", "sending")
                .containsEntry("due_at", java.sql.Timestamp.from(java.time.Instant.parse("2026-10-12T08:01:00Z")));

        update(
                salon,
                message,
                "status = 'pending', next_attempt_at = '2026-10-12T09:00:00Z', lease_until = NULL, lease_token = NULL");
        assertThat(indexed(salon, message))
                .as("a retry: due at its next attempt")
                .containsEntry("status", "pending")
                .containsEntry("due_at", java.sql.Timestamp.from(java.time.Instant.parse("2026-10-12T09:00:00Z")));

        update(salon, message, "status = 'dead'");
        assertThat(indexed(salon, message)).as("dead: no longer waiting").isNull();

        UUID removed = insert(salon, "probe", null, "pending", "2026-10-12T07:00:00Z", null);
        owner.update("DELETE FROM outbox_message WHERE tenant_id = ? AND id = ?", salon.id(), removed);
        assertThat(indexed(salon, removed)).as("removed with its message").isNull();
    }

    @Test
    void theApplicationsRoleCannotReachTheIndexNorCallItsTrigger() throws SQLException {
        try (Connection connection = database.connectAsApplication();
                Statement statement = connection.createStatement()) {
            for (String sql : new String[] {
                "SELECT * FROM shared_outbox_due", "DELETE FROM shared_outbox_due", "SELECT shared_outbox_due_track()"
            }) {
                assertThatExceptionOfType(SQLException.class)
                        .as(sql)
                        .isThrownBy(() -> statement.execute(sql))
                        .satisfies(refused -> assertThat(refused.getSQLState()).isIn("42501", "0A000"));
            }
        }
    }

    /** Sequential and index scans of each table so far, flushed from this connection's statistics first. */
    private static Map<String, Long> scans(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_stat_force_next_flush()");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_stat_clear_snapshot()");
        }
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT relname, coalesce(seq_scan, 0) + coalesce(idx_scan, 0) AS scans FROM pg_stat_user_tables
                         WHERE relname IN ('outbox_message', 'shared_outbox_due')
                        """)) {
            Map<String, Long> scans = new java.util.HashMap<>();
            while (rows.next()) {
                scans.put(rows.getString(1), rows.getLong(2));
            }
            return scans;
        }
    }

    private static int rows(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            int count = 0;
            while (rows.next()) {
                count++;
            }
            return count;
        }
    }

    private static UUID insert(
            TestSalon salon, String channel, String eventType, String status, String dueAt, String leaseUntil) {
        UUID id = UUID.randomUUID();
        owner.update(
                "INSERT INTO outbox_message (tenant_id, id, kind, channel, event_type, payload, status, attempts,"
                        + " next_attempt_at, lease_until, lease_token, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, '{}'::jsonb, ?, 0, ?::timestamptz, ?::timestamptz, ?, now(), now())",
                salon.id(),
                id,
                eventType == null ? "delivery" : "event",
                channel,
                eventType,
                status,
                dueAt,
                leaseUntil,
                leaseUntil == null ? null : UUID.randomUUID());
        return id;
    }

    private static void update(TestSalon salon, UUID message, String assignments) {
        owner.update(
                "UPDATE outbox_message SET " + assignments + " WHERE tenant_id = ? AND id = ?", salon.id(), message);
    }

    private static Map<String, Object> indexed(TestSalon salon, UUID message) {
        List<Map<String, Object>> rows = owner.queryForList(
                "SELECT status, event_type, due_at FROM shared_outbox_due WHERE salon_id = ? AND message_id = ?",
                salon.id(),
                message);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
