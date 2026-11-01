package com.salonplatform.catalogue;

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
 * The second layer on its own for F05-S01's and F05-S02's tables: row-level security, with Hibernate nowhere in the path. Plain
 * SQL as the application's role, which in this database may also insert, update and delete every table, and with
 * the triggers that refuse every update of a terms version and of a variant dropped, so that the policy and nothing else stands
 * between one salon and another. With both salons' rows present: with no salon set nothing is seen or written;
 * alpha sees only its own, even asking for beta's; alpha cannot change or remove beta's; and a row for beta, or one
 * of alpha's moved to beta, is refused. {@link CatalogueHibernateFilterAloneIntegrationTest} is the other layer
 * alone.
 */
class CatalogueRowLevelSecurityAloneIntegrationTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    /** Each table and the column that tells one of a salon's rows from another. */
    static final Map<String, String> KEYS = new LinkedHashMap<>();

    static {
        KEYS.put("catalogue_category_list", "id");
        KEYS.put("catalogue_category", "id");
        KEYS.put("catalogue_photo", "id");
        KEYS.put("catalogue_service", "id");
        KEYS.put("catalogue_terms_version", "id");
        KEYS.put("catalogue_terms_variant", "id");
        KEYS.put("catalogue_popular_setting", "id");
    }

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
        owner.execute("DROP TRIGGER catalogue_terms_version_immutable ON catalogue_terms_version");
        owner.execute("DROP TRIGGER catalogue_terms_variant_immutable ON catalogue_terms_variant");
        TestSalons salons = new TestSalons(owner);
        alpha = salons.create("Catalogue Alpha", "#111111");
        beta = salons.create("Catalogue Beta", "#222222");
        for (TestSalon salon : List.of(alpha, beta)) {
            CatalogueRows.serviceWithPhoto(owner, salon, salons.defaultBranch(salon), "Haircut");
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

    /** A salon's rows of a table, with arrays and bytes written as text so that two reads compare by value. */
    private static List<Map<String, Object>> rowsOf(String table, TestSalon salon) {
        return database
                .asOwner()
                .queryForList("SELECT * FROM " + table + " WHERE tenant_id = ? ORDER BY 1, 2", salon.id())
                .stream()
                .map(row -> {
                    Map<String, Object> copy = new LinkedHashMap<>(row);
                    // Arrays and bytes are written as text, so that two reads compare by value.
                    copy.replaceAll((column, value) -> value instanceof java.sql.Array array
                            ? array.toString()
                            : value instanceof byte[] bytes
                                    ? java.util.HexFormat.of().formatHex(bytes)
                                    : value);
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
