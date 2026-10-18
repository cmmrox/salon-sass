package com.salonplatform.shared;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The ways background work reaches across salons (decision 029): {@code SECURITY DEFINER} functions
 * that return ids, counts and times and nothing else, called as the application's role. Each case
 * runs twice: on a database owned by a superuser, as on a developer's machine, and on one owned by
 * an ordinary role, as everywhere else, where forced row-level security holds for the owner the
 * functions run as.
 *
 * <p>What matters most: a function never leaves another salon set in the caller's transaction, and
 * never widens what the caller can read.
 */
class CrossSalonFunctionsIntegrationTest {

    enum Owner {
        SUPERUSER, ORDINARY_ROLE
    }

    private static final Map<Owner, Fixture> FIXTURES = new EnumMap<>(Owner.class);

    /** A migrated database with three salons and their messages. */
    private record Fixture(TestDatabase database, TestSalon alpha, TestSalon beta, TestSalon closed, UUID alphaDue,
            UUID alphaLater, UUID alphaExpiredLease, UUID alphaHeld, UUID alphaSent, UUID betaDue, UUID betaOtherChannel,
            UUID closedDue) {
    }

    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");

    private static synchronized Fixture fixture(Owner owner) {
        return FIXTURES.computeIfAbsent(owner, kind -> {
            TestDatabase database = kind == Owner.SUPERUSER ? TestDatabase.createEmpty(TestPostgres.shared())
                    : TestDatabase.createEmptyOwnedByAnOrdinaryRole(TestPostgres.shared());
            try (StartedApplication migrated = database.start()) {
                // Started once to apply the migrations as the owning role.
            }
            JdbcTemplate superuser = database.asOwner();
            assertThat(superuser.queryForObject("SELECT r.rolsuper FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner "
                    + "WHERE c.relname = 'outbox_message'", Boolean.class)).as("the tables' owner is a superuser")
                    .isEqualTo(kind == Owner.SUPERUSER);
            TestSalons salons = new TestSalons(superuser);
            TestSalon alpha = salons.create("Functions Alpha", "#111111");
            TestSalon beta = salons.create("Functions Beta", "#222222");
            TestSalon closed = salons.create("Functions Closed", "#333333");
            Fixture created = new Fixture(database, alpha, beta, closed,
                    message(superuser, alpha, "probe", "pending", "2026-10-12T07:00:00Z", null),
                    message(superuser, alpha, "probe", "pending", "2026-10-12T09:00:00Z", null),
                    message(superuser, alpha, "probe", "sending", "2026-10-12T06:00:00Z", "2026-10-12T07:30:00Z"),
                    message(superuser, alpha, "probe", "sending", "2026-10-12T06:00:00Z", "2026-10-12T08:00:30Z"),
                    message(superuser, alpha, "probe", "sent", "2026-10-12T06:00:00Z", null),
                    message(superuser, beta, "probe", "pending", "2026-10-12T07:59:59Z", null),
                    message(superuser, beta, "sms", "pending", "2026-10-12T07:00:00Z", null),
                    message(superuser, closed, "probe", "pending", "2026-10-12T07:00:00Z", null));
            salons.suspend(closed);
            return created;
        });
    }

    private static UUID message(JdbcTemplate superuser, TestSalon salon, String channel, String status, String dueAt,
            String leaseUntil) {
        UUID id = UUID.randomUUID();
        superuser.update("INSERT INTO outbox_message (tenant_id, id, channel, payload, status, attempts, next_attempt_at, "
                + "lease_until, lease_token, sent_at, created_at, updated_at) VALUES (?, ?, ?, '{\"secret\":\"payload\"}', ?, 0, "
                + "?::timestamptz, ?::timestamptz, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz)",
                salon.id(), id, channel, status, dueAt, leaseUntil, leaseUntil == null ? null : UUID.randomUUID(),
                "sent".equals(status) ? dueAt : null, "2026-10-12T06:00:00Z", "2026-10-12T06:00:00Z");
        return id;
    }

    @ParameterizedTest
    @EnumSource(Owner.class)
    void whatIsDueIsIdsAndSalonIdsOfDueMessagesOfActiveSalonsOnTheGivenChannelsAndNothingElse(Owner owner)
            throws SQLException {
        Fixture fixture = fixture(owner);
        try (Connection connection = fixture.database().connectAsApplication()) {
            List<List<Object>> due = due(connection, 10, 100, "probe");

            assertThat(due).as("a pending message whose time has come, and one whose lease has run out")
                    .containsExactlyInAnyOrder(
                            List.of(fixture.alphaDue(), fixture.alpha().id()),
                            List.of(fixture.alphaExpiredLease(), fixture.alpha().id()),
                            List.of(fixture.betaDue(), fixture.beta().id()));
            assertThat(due(connection, 10, 100, "probe", "sms")).hasSize(4)
                    .contains(List.of(fixture.betaOtherChannel(), fixture.beta().id()));
            assertThat(due(connection, 1, 100, "probe")).as("one for each salon at most").hasSize(2);
            assertThat(due(connection, 10, 2, "probe")).as("two in all at most").hasSize(2);
            assertThat(due(connection, 10, 100, "no-such-channel")).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(Owner.class)
    void theFunctionsReturnNoColumnOfAMessageButItsIdAndItsSalon(Owner owner) throws SQLException {
        Fixture fixture = fixture(owner);
        try (Connection connection = fixture.database().connectAsApplication(); Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT * FROM outbox_due('2026-10-12T08:00:00Z', ARRAY['probe'], 10, 100)")) {
                assertThat(columns(rows)).containsExactly("message_id", "tenant_id");
            }
            try (ResultSet rows = statement.executeQuery("SELECT * FROM outbox_backlog('2026-10-12T08:00:00Z')")) {
                assertThat(columns(rows)).containsExactly("waiting", "oldest_due_at");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong("waiting")).as("pending or being delivered, across every salon").isEqualTo(7);
                assertThat(rows.getTimestamp("oldest_due_at").toInstant()).isEqualTo(Instant.parse("2026-10-12T07:00:00Z"));
                assertThat(rows.next()).isFalse();
            }
            try (ResultSet rows = statement.executeQuery("SELECT * FROM active_tenant_ids()")) {
                assertThat(columns(rows)).containsExactly("id");
                List<UUID> ids = new ArrayList<>();
                while (rows.next()) {
                    ids.add(rows.getObject(1, UUID.class));
                }
                assertThat(ids).containsExactlyInAnyOrder(fixture.alpha().id(), fixture.beta().id());
            }
            try (ResultSet rows = statement.executeQuery("SELECT * FROM tenant_by_id('" + fixture.closed().id() + "')")) {
                assertThat(columns(rows)).containsExactly("id", "slug", "status", "time_zone", "access_state");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("status")).isEqualTo("suspended");
            }
        }
    }

    /** The heart of it: asking what is due across salons never changes which salon the caller is. */
    @ParameterizedTest
    @EnumSource(Owner.class)
    void aFunctionLeavesTheCallersSalonAsItWasAndWidensNothing(Owner owner) throws SQLException {
        Fixture fixture = fixture(owner);
        try (Connection connection = fixture.database().connectAsApplication()) {
            connection.setAutoCommit(false);
            setSalon(connection, fixture.alpha());
            assertThat(salonsVisible(connection)).containsExactly(fixture.alpha().id());

            assertThat(due(connection, 10, 100, "probe")).as("it still answers for every salon").hasSize(3);
            backlog(connection);

            assertThat(currentSalon(connection)).as("the caller's salon after the calls").isEqualTo(fixture.alpha().id().toString());
            assertThat(salonsVisible(connection)).as("the caller still reads only its own salon's rows")
                    .containsExactly(fixture.alpha().id());
            connection.rollback();

            // With no salon set: nothing before, and still nothing after.
            assertThat(salonsVisible(connection)).isEmpty();
            assertThat(due(connection, 10, 100, "probe")).hasSize(3);
            backlog(connection);
            assertThat(currentSalon(connection)).as("no salon was left set").isNullOrEmpty();
            assertThat(salonsVisible(connection)).as("with no salon set the caller still reads nothing").isEmpty();
            connection.rollback();

            // On the same pooled-style connection, in the next transaction, nothing is carried over either.
            assertThat(currentSalon(connection)).isNullOrEmpty();
            assertThat(salonsVisible(connection)).isEmpty();
            connection.rollback();
        }
    }

    @ParameterizedTest
    @EnumSource(Owner.class)
    void theSchedulersBookkeepingIsReachedOnlyThroughItsFunctions(Owner owner) throws SQLException {
        Fixture fixture = fixture(owner);
        String job = "functions-" + owner.name().toLowerCase().replace('_', '-');
        try (Connection connection = fixture.database().connectAsApplication(); Statement statement = connection.createStatement()) {
            for (String sql : new String[] { "SELECT * FROM scheduled_job", "DELETE FROM scheduled_job",
                "INSERT INTO scheduled_job (name, next_run_at, created_at, updated_at) VALUES ('sneaky', now(), now(), now())",
                "UPDATE scheduled_job SET locked_by = NULL, locked_until = NULL" }) {
                assertThatExceptionOfType(SQLException.class).as(sql).isThrownBy(() -> statement.execute(sql))
                        .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
            }

            assertThat(claim(connection, job, "2026-10-12T08:00:00Z", "one")).as("the first claim takes the occurrence")
                    .isEqualTo(Instant.parse("2026-10-12T08:00:00Z"));
            assertThat(claim(connection, job, "2026-10-12T08:00:01Z", "two")).as("nobody else can").isNull();
            try (PreparedStatement finish = connection.prepareStatement("SELECT job_finish(?, 'two', ?::timestamptz)::text")) {
                finish.setString(1, job);
                finish.setString(2, "2026-10-12T08:00:02Z");
                finish.execute();
            }
            assertThat(fixture.database().asOwner().queryForObject("SELECT locked_by FROM scheduled_job WHERE name = ?",
                    String.class, job)).as("only the holder lets go of it").isEqualTo("one");
        }
    }

    private static Instant claim(Connection connection, String job, String at, String holder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT job_claim(?, ?::timestamptz, interval '1 hour', ?, ?::timestamptz + interval '10 minutes')")) {
            statement.setString(1, job);
            statement.setString(2, at);
            statement.setString(3, holder);
            statement.setString(4, at);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                Timestamp occurrence = row.getTimestamp(1);
                return occurrence == null ? null : occurrence.toInstant();
            }
        }
    }

    private static List<List<Object>> due(Connection connection, int perSalon, int max, String... channels)
            throws SQLException {
        Array channelArray = connection.createArrayOf("text", channels);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT message_id, tenant_id FROM outbox_due(?::timestamptz, ?, ?, ?)")) {
            statement.setString(1, NOW.toString());
            statement.setArray(2, channelArray);
            statement.setInt(3, perSalon);
            statement.setInt(4, max);
            List<List<Object>> due = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    due.add(List.of(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class)));
                }
            }
            return due;
        }
    }

    private static void backlog(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT waiting FROM outbox_backlog(?::timestamptz)")) {
            statement.setString(1, NOW.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
            }
        }
    }

    private static List<String> columns(ResultSet rows) throws SQLException {
        ResultSetMetaData meta = rows.getMetaData();
        List<String> names = new ArrayList<>();
        for (int column = 1; column <= meta.getColumnCount(); column++) {
            names.add(meta.getColumnName(column));
        }
        return names;
    }

    private static void setSalon(Connection connection, TestSalon salon) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, salon.id().toString());
            statement.execute();
        }
    }

    private static String currentSalon(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT current_setting('app.tenant_id', true)")) {
            row.next();
            return row.getString(1);
        }
    }

    private static List<UUID> salonsVisible(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT DISTINCT tenant_id FROM outbox_message")) {
            List<UUID> salons = new ArrayList<>();
            while (rows.next()) {
                salons.add(rows.getObject(1, UUID.class));
            }
            return salons;
        }
    }
}
