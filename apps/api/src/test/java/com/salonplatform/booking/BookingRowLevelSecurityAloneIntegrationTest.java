package com.salonplatform.booking;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The second layer on its own for F07-S01's tables (AC-14): row-level security, with Hibernate nowhere in the path. Plain
 * SQL as the application's role, which in this database may also delete from both tables, and with the trigger that
 * refuses every update of the history dropped, so that the policy and nothing else stands between one salon and another.
 * With both salons' bookings and history present: with no salon set nothing is seen or written; alpha sees only its own,
 * even asking for beta's; alpha cannot change or remove beta's; and a row for beta, or one of alpha's moved to beta, is
 * refused. {@link BookingHibernateFilterAloneIntegrationTest} is the other layer alone.
 */
class BookingRowLevelSecurityAloneIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** Each table and the column that tells one of a salon's rows from another. */
    static final Map<String, String> KEYS =
            new LinkedHashMap<>(Map.of("booking_appointment", "id", "booking_state_change", "id"));

    static final List<String> TABLES = List.copyOf(KEYS.keySet());

    private static TestDatabase database;
    private static TestSalon alpha;
    private static TestSalon beta;

    @BeforeAll
    static void migratedDatabaseWithTwoSalonsAndEveryWriteGranted() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication _ = database.start()) {
            // Started once to apply the migrations as the product applies them.
        }
        JdbcTemplate owner = database.asOwner();
        for (String table : TABLES) {
            owner.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON " + table + " TO " + TestPostgres.APP_USER);
        }
        owner.execute("DROP TRIGGER booking_state_change_immutable ON booking_state_change");
        TestSalons salons = new TestSalons(owner);
        alpha = salons.create("Booking Alpha", "#111111");
        beta = salons.create("Booking Beta", "#222222");
        for (TestSalon salon : List.of(alpha, beta)) {
            UUID booking = UUID.randomUUID();
            UUID branch = salons.defaultBranch(salon);
            owner.update(
                    "INSERT INTO booking_appointment (tenant_id, id, branch_id, reference, state, member_id, member_name,"
                            + " service_id, service_terms_id, service_name, rules_version_id, starts_at, ends_at,"
                            + " segment_kinds, segment_minutes, occupied, price_minor, currency, customer_name,"
                            + " customer_mobile, channel, hold_expires_at, idempotency_key, request_fingerprint,"
                            + " created_by, version, created_at, updated_at) VALUES (?, ?, ?, 'K7M4QX', 'confirmed', ?,"
                            + " 'Nimali Perera', ?, ?, 'Cut and finish', ?, '2086-06-16T04:30:00Z',"
                            + " '2086-06-16T05:30:00Z', '{active,buffer}', '{45,15}',"
                            + " '{[2086-06-16T04:30:00Z,2086-06-16T05:30:00Z)}', 350000, 'LKR', 'Dilani Fernando',"
                            + " '+94771234567', 'phone', '2086-06-15T02:40:00Z', ?, 'fingerprint', ?, 1,"
                            + " '2086-06-15T02:30:00Z', '2086-06-15T02:30:00Z')",
                    salon.id(),
                    booking,
                    branch,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID());
            owner.update(
                    "INSERT INTO booking_state_change (tenant_id, id, branch_id, booking_id, position, from_state,"
                            + " to_state, changed_at, actor_kind, actor_id, created_at, updated_at) VALUES (?, ?, ?, ?,"
                            + " 1, NULL, 'held', '2086-06-15T02:30:00Z', 'staff', ?, '2086-06-15T02:30:00Z',"
                            + " '2086-06-15T02:30:00Z')",
                    salon.id(),
                    UUID.randomUUID(),
                    branch,
                    booking,
                    UUID.randomUUID());
        }
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("TABLES")
    void withNoSalonSetNoRowIsSeenAndNoneIsWritten(String table) throws SQLException {
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);

            assertThat(tenantsVisible(connection, "SELECT tenant_id FROM " + table))
                    .isEmpty();
            assertThat(update(connection, "UPDATE " + table + " SET updated_at = now()"))
                    .isZero();
            assertThat(update(connection, "DELETE FROM " + table)).isZero();
            connection.rollback();
        }
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("TABLES")
    void withAlphaSetOnlyAlphasRowsAreSeenEvenByAQueryForBetas(String table) throws SQLException {
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            setTenant(connection, alpha);

            assertThat(tenantsVisible(connection, "SELECT tenant_id FROM " + table))
                    .isNotEmpty()
                    .containsOnly(alpha.id());
            assertThat(tenantsVisible(
                            connection,
                            "SELECT tenant_id FROM " + table + " WHERE " + KEYS.get(table) + " = ?",
                            keyOf(table, beta)))
                    .as("beta's row, by its key")
                    .isEmpty();
            connection.rollback();
        }
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("TABLES")
    void alphaCannotUpdateOrDeleteBetasRows(String table) throws SQLException {
        List<Map<String, Object>> before = rowsOf(table, beta);
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            setTenant(connection, alpha);

            assertThat(update(
                            connection,
                            "UPDATE " + table + " SET updated_at = now() WHERE " + KEYS.get(table) + " = ?",
                            keyOf(table, beta)))
                    .isZero();
            assertThat(update(connection, "DELETE FROM " + table + " WHERE tenant_id = ?", beta.id()))
                    .isZero();
            connection.commit();
        }
        assertThat(rowsOf(table, beta)).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("TABLES")
    void alphaCannotCreateARowForBetaNorMoveItsOwnRowToBeta(String table) throws SQLException {
        List<Map<String, Object>> before = rowsOf(table, beta);
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            setTenant(connection, alpha);
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> insertCopyFor(connection, table, beta.id()))
                    .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
            connection.rollback();

            setTenant(connection, alpha);
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> update(
                            connection,
                            "UPDATE " + table + " SET tenant_id = ? WHERE " + KEYS.get(table) + " = ?",
                            beta.id(),
                            keyOf(table, alpha)))
                    .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
            connection.rollback();
        }
        assertThat(rowsOf(table, beta)).isEqualTo(before);
    }

    private static UUID keyOf(String table, TestSalon salon) {
        return database.asOwner()
                .queryForObject(
                        "SELECT " + KEYS.get(table) + " FROM " + table + " WHERE tenant_id = ? LIMIT 1",
                        UUID.class,
                        salon.id());
    }

    /** A salon's rows of a table, with arrays written as text so that two reads compare by value. */
    private static List<Map<String, Object>> rowsOf(String table, TestSalon salon) {
        return database
                .asOwner()
                .queryForList("SELECT * FROM " + table + " WHERE tenant_id = ? ORDER BY 1, 2", salon.id())
                .stream()
                .map(row -> {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    copy.replaceAll(
                            (column, value) -> value instanceof java.sql.Array array ? array.toString() : value);
                    return copy;
                })
                .toList();
    }

    /** A copy of one of alpha's rows with the given salon and a new key: only the policy can refuse it. */
    private static void insertCopyFor(Connection connection, String table, UUID salon) throws SQLException {
        Map<String, Object> copy = new LinkedHashMap<>(
                database.asOwner().queryForMap("SELECT * FROM " + table + " WHERE tenant_id = ? LIMIT 1", alpha.id()));
        copy.put(KEYS.get(table), UUID.randomUUID());
        copy.put("tenant_id", salon);
        String columns = String.join(", ", copy.keySet());
        String values = copy.keySet().stream().map(column -> "?").collect(Collectors.joining(", "));
        update(
                connection,
                "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")",
                copy.values().toArray());
    }

    private static void setTenant(Connection connection, TestSalon salon) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, salon.id().toString());
            statement.execute();
        }
    }

    private static List<UUID> tenantsVisible(Connection connection, String query, Object... parameters)
            throws SQLException {
        List<UUID> tenants = new ArrayList<>();
        try (PreparedStatement statement = prepared(connection, query, parameters);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                tenants.add(rows.getObject(1, UUID.class));
            }
        }
        return tenants;
    }

    private static int update(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = prepared(connection, sql, parameters)) {
            return statement.executeUpdate();
        }
    }

    private static PreparedStatement prepared(Connection connection, String sql, Object... parameters)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
        return statement;
    }
}
