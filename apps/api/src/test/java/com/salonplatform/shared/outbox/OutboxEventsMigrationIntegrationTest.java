package com.salonplatform.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The events migration (V17) works while the previous version still runs (docs/engineering/backend.md,
 * "Persistence and migrations"): a message the previous version wrote before it is a delivery afterwards, and
 * the previous version's own statements, run as the application's and the platform's roles against the
 * migrated table, still write provider messages and never see an event as one of theirs.
 */
class OutboxEventsMigrationIntegrationTest {

    private static final MigrationVersion PREVIOUS_VERSION = MigrationVersion.fromVersion("16");

    /** The previous version's insert of a new message, as its entity wrote it: no kind and no event type. */
    private static final String PREVIOUS_INSERT = """
            INSERT INTO outbox_message (attempts, channel, created_at, last_error, lease_token, lease_until,
                    next_attempt_at, payload, sent_at, status, tenant_id, trace_parent, updated_at, id)
            VALUES (0, 'sms', ?, NULL, NULL, NULL, ?, '{}'::jsonb, NULL, 'pending', ?, NULL, ?, ?)
            """;

    private static TestDatabase database;

    private static JdbcTemplate owner;

    private static UUID salon;

    private static UUID writtenBefore;

    @BeforeAll
    static void migratedToThePreviousVersionWithAMessageAndThenToTheLatest() throws SQLException {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        owner = database.asOwner();
        migrate(PREVIOUS_VERSION);
        salon = UUID.randomUUID();
        owner.update("INSERT INTO tenant (id, slug, status) VALUES (?, ?, 'active')", salon, "migration-events");
        writtenBefore = UUID.randomUUID();
        writeAsThePreviousVersion(writtenBefore);
        migrate(MigrationVersion.LATEST);
    }

    @Test
    void aMessageWrittenBeforeTheMigrationIsADelivery() {
        assertThat(owner.queryForMap("SELECT kind, event_type FROM outbox_message WHERE id = ?", writtenBefore))
                .containsEntry("kind", "delivery")
                .containsEntry("event_type", null);
    }

    @Test
    void thePreviousVersionStillWritesProviderMessagesAsTheApplicationAndThePlatform() throws SQLException {
        UUID applications = UUID.randomUUID();
        writeAsThePreviousVersion(applications);
        UUID platforms = UUID.randomUUID();
        try (Connection platform = database.connectAsPlatform();
                PreparedStatement enqueue = platform.prepareStatement(
                        "SELECT outbox_enqueue_for_salon(?, ?, 'email', '{}'::jsonb, NULL, ?)")) {
            enqueue.setObject(1, salon);
            enqueue.setObject(2, platforms);
            enqueue.setTimestamp(3, Timestamp.from(Instant.now()));
            enqueue.execute();
        }

        assertThat(owner.queryForList(
                        "SELECT kind FROM outbox_message WHERE id IN (?, ?)", String.class, applications, platforms))
                .containsExactly("delivery", "delivery");
    }

    @Test
    void thePreviousVersionsDispatcherNeverClaimsAnEvent() throws SQLException {
        UUID event = UUID.randomUUID();
        owner.update(
                "INSERT INTO outbox_message (tenant_id, id, kind, channel, event_type, payload, status, attempts,"
                        + " next_attempt_at, created_at, updated_at)"
                        + " VALUES (?, ?, 'event', 'event', 'probe.Noted.v1', '{}', 'pending', 0, now(), now(), now())",
                salon,
                event);

        List<UUID> due = new ArrayList<>();
        try (Connection application = database.connectAsApplication();
                PreparedStatement asked = application.prepareStatement(
                        "SELECT message_id FROM outbox_due(?, ARRAY['sms', 'email'], 100, 100)")) {
            asked.setTimestamp(1, Timestamp.from(Instant.now().plusSeconds(1)));
            try (ResultSet rows = asked.executeQuery()) {
                while (rows.next()) {
                    due.add(rows.getObject(1, UUID.class));
                }
            }
        }

        assertThat(due).contains(writtenBefore).doesNotContain(event);
    }

    @Test
    void thisVersionAsksForDueEventsOfTheTypesItKnowsOnly() throws SQLException {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        for (Object[] event : new Object[][] {{known, "probe.Known.v1"}, {unknown, "probe.FromANewerVersion.v1"}}) {
            owner.update(
                    "INSERT INTO outbox_message (tenant_id, id, kind, channel, event_type, payload, status, attempts,"
                            + " next_attempt_at, created_at, updated_at)"
                            + " VALUES (?, ?, 'event', 'event', ?, '{}', 'pending', 0, now(), now(), now())",
                    salon,
                    event[0],
                    event[1]);
        }

        List<UUID> due = new ArrayList<>();
        try (Connection application = database.connectAsApplication();
                PreparedStatement asked = application.prepareStatement(
                        "SELECT message_id FROM outbox_due_events(?, ARRAY['probe.Known.v1'], 100, 100)")) {
            asked.setTimestamp(1, Timestamp.from(Instant.now().plusSeconds(1)));
            try (ResultSet rows = asked.executeQuery()) {
                while (rows.next()) {
                    due.add(rows.getObject(1, UUID.class));
                }
            }
        }

        assertThat(due).contains(known).doesNotContain(unknown, writtenBefore);
    }

    @Test
    void aDeliveryCannotTakeTheEventsChannelAndAnEventCannotTakeAProvidersChannel() {
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> owner.update(
                        "INSERT INTO outbox_message (tenant_id, id, channel, payload, status, next_attempt_at,"
                                + " created_at, updated_at) VALUES (?, ?, 'event', '{}', 'pending', now(), now(), now())",
                        salon,
                        UUID.randomUUID()));
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> owner.update(
                        "INSERT INTO outbox_message (tenant_id, id, kind, channel, event_type, payload, status,"
                                + " next_attempt_at, created_at, updated_at)"
                                + " VALUES (?, ?, 'event', 'sms', 'probe.Noted.v1', '{}', 'pending', now(), now(), now())",
                        salon,
                        UUID.randomUUID()));
        assertThatExceptionOfType(DataIntegrityViolationException.class)
                .isThrownBy(() -> owner.update(
                        "INSERT INTO outbox_message (tenant_id, id, kind, channel, payload, status, next_attempt_at,"
                                + " created_at, updated_at)"
                                + " VALUES (?, ?, 'event', 'event', '{}', 'pending', now(), now(), now())",
                        salon,
                        UUID.randomUUID()));
    }

    /** The previous version's application writing a message for the salon, in a transaction with it set. */
    private static void writeAsThePreviousVersion(UUID id) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection application = database.connectAsApplication()) {
            application.setAutoCommit(false);
            try (PreparedStatement setSalon =
                            application.prepareStatement("SELECT set_config('app.tenant_id', ?, true)");
                    PreparedStatement insert = application.prepareStatement(PREVIOUS_INSERT)) {
                setSalon.setString(1, salon.toString());
                setSalon.execute();
                insert.setTimestamp(1, now);
                insert.setTimestamp(2, now);
                insert.setObject(3, salon);
                insert.setTimestamp(4, now);
                insert.setObject(5, id);
                insert.executeUpdate();
            }
            application.commit();
        }
    }

    private static void migrate(MigrationVersion target) {
        Flyway.configure()
                .dataSource(
                        database.url(),
                        database.container().getUsername(),
                        database.container().getPassword())
                .placeholders(
                        Map.of("application_role", TestPostgres.APP_USER, "platform_role", TestPostgres.PLATFORM_USER))
                .target(target)
                .load()
                .migrate();
    }
}
