package com.salonplatform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.salonplatform.identity.IdentityRows.SalonRows;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The second layer on its own for every identity table: row-level security, with Hibernate nowhere in the path.
 * Plain SQL as the application's role, which in this database may also select, insert, update and delete every
 * identity table, so that the policy and nothing else stands between one salon and another. With two salons'
 * rows in every table, alpha sees only its own, even when it asks for beta's by id; updates and deletes aimed at
 * beta's rows reach nothing; a row for beta, or one of alpha's moved to beta, is refused; and with no salon set
 * nothing is seen or written. {@link IdentityRepositoriesHibernateFilterAloneIntegrationTest} is the other
 * layer on its own.
 */
class IdentityTablesRowLevelSecurityAloneIntegrationTest {

    private static final String SET_TENANT = "SELECT set_config('app.tenant_id', ?, true)";

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    static final List<String> TABLES = IdentityRows.TABLES;

    private static TestDatabase database;

    private static IdentityRows identityRows;

    private static TestSalon alpha;

    private static TestSalon beta;

    private static Map<String, UUID> alphasRows;

    private static Map<String, UUID> betasRows;

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
        TestSalons salons = new TestSalons(owner);
        identityRows = new IdentityRows(owner);
        alpha = salons.create("Policy Alpha", "#111111");
        beta = salons.create("Policy Beta", "#222222");
        alphasRows = rowPerTable(identityRows.writeFor(alpha));
        betasRows = rowPerTable(identityRows.writeFor(beta));
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
                            connection, "SELECT tenant_id FROM " + table + " WHERE id = ?", betasRows.get(table)))
                    .as("beta's row, by its id")
                    .isEmpty();
            assertThat(tenantsVisible(connection, "SELECT tenant_id FROM " + table + " WHERE tenant_id = ?", beta.id()))
                    .as("beta's rows, by its salon")
                    .isEmpty();
            connection.rollback();
        }
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("TABLES")
    void alphaCannotUpdateOrDeleteBetasRows(String table) throws SQLException {
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            setTenant(connection, alpha);

            assertThat(update(
                            connection,
                            "UPDATE " + table + " SET updated_at = now() WHERE id = ?",
                            betasRows.get(table)))
                    .as("rows updated")
                    .isZero();
            assertThat(update(connection, "DELETE FROM " + table + " WHERE tenant_id = ?", beta.id()))
                    .as("rows deleted")
                    .isZero();
            assertThat(update(connection, "UPDATE " + table + " SET updated_at = now()"))
                    .as("an update of every row reaches alpha's only")
                    .isEqualTo(count(connection, table));
            connection.commit();
        }
        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("TABLES")
    void alphaCannotCreateARowForBetaNorMoveItsOwnRowToBeta(String table) throws SQLException {
        Map<String, List<Map<String, Object>>> before = identityRows.snapshotOf(beta.id());
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            setTenant(connection, alpha);

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> insertCopyFor(connection, table, alphasRows.get(table), beta.id()))
                    .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
            connection.rollback();

            setTenant(connection, alpha);
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> update(
                            connection,
                            "UPDATE " + table + " SET tenant_id = ? WHERE id = ?",
                            beta.id(),
                            alphasRows.get(table)))
                    .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
            connection.rollback();
        }
        assertThat(identityRows.snapshotOf(beta.id())).isEqualTo(before);
    }

    /** One row of each table: the id the isolation cases aim at. */
    private static Map<String, UUID> rowPerTable(SalonRows rows) {
        return Map.of(
                "identity_refresh_history", rows.refresh(),
                "identity_mfa_step", rows.mfaStep(),
                "identity_mfa_challenge", rows.mfaChallenge(),
                "identity_customer_challenge", rows.customerChallenge(),
                "identity_rate_bucket", rows.rateBucket(),
                "identity_session", rows.staffSession(),
                "identity_guard", rows.salon(),
                "identity_customer", rows.customer(),
                "identity_staff", rows.staff());
    }

    /**
     * Inserts a copy of one of alpha's rows, as the application's role, with a new id and the given salon: every
     * other value is one the table accepts, so only the policy can refuse it.
     */
    private static void insertCopyFor(Connection connection, String table, UUID alphasRow, UUID salon)
            throws SQLException {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(
                database.asOwner().queryForMap("SELECT * FROM " + table + " WHERE id = ?", alphasRow));
        copy.put("id", UUID.randomUUID());
        copy.put("tenant_id", salon);
        String columns = String.join(", ", copy.keySet());
        String values = copy.keySet().stream().map(column -> "?").collect(Collectors.joining(", "));
        update(
                connection,
                "INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")",
                copy.values().toArray());
    }

    private static int count(Connection connection, String table) throws SQLException {
        return tenantsVisible(connection, "SELECT tenant_id FROM " + table).size();
    }

    private static void setTenant(Connection connection, TestSalon salon) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
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
