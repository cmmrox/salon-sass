package com.salonplatform.branding;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The second layer on its own: row-level security, with Hibernate nowhere in the path. Plain SQL as
 * the application's role, which in this database is also granted INSERT, UPDATE and DELETE on
 * {@code salon_brand}, so that the policy and nothing else stands between one salon and another.
 * The four cases of docs/architecture/multi-tenancy.md, "Tests you must write", hold.
 */
class SalonBrandRowLevelSecurityAloneIntegrationTest {

    private static final String SET_TENANT = "SELECT set_config('app.tenant_id', ?, true)";

    private static TestDatabase database;

    private static TestSalon alpha;

    private static TestSalon beta;

    @BeforeAll
    static void migratedDatabaseWithTwoSalonsAndEveryWriteGranted() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication migrated = database.start()) {
            // Started once to apply the migrations as the product applies them.
        }
        JdbcTemplate owner = database.asOwner();
        owner.execute("GRANT INSERT, UPDATE, DELETE ON salon_brand TO " + TestPostgres.APP_USER);
        TestSalons salons = new TestSalons(owner);
        alpha = salons.create("Alpha Studio", "#D6086A");
        beta = salons.create("Beta Lounge", "#1F2937");
        assertThat(owner.queryForObject("SELECT count(*) FROM salon_brand", Integer.class))
                .as("both salons have a brand row to find")
                .isEqualTo(2);
    }

    @Test
    void aQueryWithNoSalonSetSeesNoRow() throws SQLException {
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            assertThat(tenantsVisible(connection, "SELECT tenant_id FROM salon_brand"))
                    .isEmpty();
            connection.rollback();
        }
    }

    @Test
    void withAlphaSetOnlyAlphasRowIsVisibleEvenToAQueryThatAsksForBetas() throws SQLException {
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            setTenant(connection, alpha);

            assertThat(tenantsVisible(connection, "SELECT tenant_id FROM salon_brand"))
                    .containsExactly(alpha.id());
            assertThat(tenantsVisible(
                            connection, "SELECT tenant_id FROM salon_brand WHERE tenant_id = '" + beta.id() + "'"))
                    .isEmpty();
            connection.rollback();
        }
    }

    @Test
    void alphaCannotUpdateOrDeleteBetasRow() throws SQLException {
        Map<String, Object> betaBefore = brandOf(beta);
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            setTenant(connection, alpha);

            assertThat(update(
                            connection,
                            "UPDATE salon_brand SET display_name = 'Taken' WHERE tenant_id = '" + beta.id() + "'"))
                    .as("rows updated")
                    .isZero();
            assertThat(update(connection, "DELETE FROM salon_brand WHERE tenant_id = '" + beta.id() + "'"))
                    .as("rows deleted")
                    .isZero();
            assertThat(update(connection, "UPDATE salon_brand SET brand_colour = '#000000'"))
                    .as("an update of every row reaches alpha's only")
                    .isEqualTo(1);
            connection.commit();
        }
        assertThat(brandOf(beta)).isEqualTo(betaBefore);
        database.asOwner()
                .update("UPDATE salon_brand SET brand_colour = ? WHERE tenant_id = ?", alpha.brandColour(), alpha.id());
    }

    @Test
    void alphaCannotCreateARowForBetaNorMoveItsOwnRowToBeta() throws SQLException {
        TestSalon delta = new TestSalons(database.asOwner()).createWithoutBrand("active");
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            setTenant(connection, alpha);

            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> update(
                            connection,
                            "INSERT INTO salon_brand (tenant_id, display_name, brand_colour) " + "VALUES ('"
                                    + delta.id() + "', 'Not mine', '#123456')"))
                    .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
            connection.rollback();

            setTenant(connection, alpha);
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> update(connection, "UPDATE salon_brand SET tenant_id = '" + delta.id() + "'"))
                    .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
            connection.rollback();
        }
        assertThat(database.asOwner()
                        .queryForObject(
                                "SELECT count(*) FROM salon_brand WHERE tenant_id = ?", Integer.class, delta.id()))
                .isZero();
    }

    @Test
    void withNoSalonSetNothingCanBeWritten() throws SQLException {
        TestSalon delta = new TestSalons(database.asOwner()).createWithoutBrand("active");
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);
            assertThat(update(connection, "UPDATE salon_brand SET display_name = 'Taken'"))
                    .isZero();
            assertThat(update(connection, "DELETE FROM salon_brand")).isZero();
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> update(
                            connection,
                            "INSERT INTO salon_brand (tenant_id, display_name, brand_colour) " + "VALUES ('"
                                    + delta.id() + "', 'Nobody', '#123456')"))
                    .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
            connection.rollback();
        }
    }

    /**
     * T-4: the salon is set for one transaction. The next transaction on the same connection, as a
     * pooled connection would be reused, has none: it sees no row, and does not fail either.
     */
    @Test
    void theSalonEndsWithItsTransactionOnTheSameConnection() throws SQLException {
        try (Connection connection = database.connectAsApplication()) {
            connection.setAutoCommit(false);

            setTenant(connection, alpha);
            assertThat(tenantsVisible(connection, "SELECT tenant_id FROM salon_brand"))
                    .containsExactly(alpha.id());
            connection.commit();
            assertThat(tenantsVisible(connection, "SELECT tenant_id FROM salon_brand"))
                    .isEmpty();
            connection.commit();

            setTenant(connection, beta);
            assertThat(tenantsVisible(connection, "SELECT tenant_id FROM salon_brand"))
                    .containsExactly(beta.id());
            connection.rollback();
            assertThat(tenantsVisible(connection, "SELECT tenant_id FROM salon_brand"))
                    .isEmpty();
            connection.commit();

            connection.setAutoCommit(true);
            assertThat(tenantsVisible(connection, "SELECT tenant_id FROM salon_brand"))
                    .isEmpty();
        }
    }

    private static void setTenant(Connection connection, TestSalon salon) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SET_TENANT)) {
            statement.setString(1, salon.id().toString());
            statement.execute();
        }
    }

    private static List<UUID> tenantsVisible(Connection connection, String query) throws SQLException {
        List<UUID> tenants = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(query)) {
            while (rows.next()) {
                tenants.add(rows.getObject(1, UUID.class));
            }
        }
        return tenants;
    }

    private static int update(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static Map<String, Object> brandOf(TestSalon salon) {
        return database.asOwner().queryForMap("SELECT * FROM salon_brand WHERE tenant_id = ?", salon.id());
    }
}
