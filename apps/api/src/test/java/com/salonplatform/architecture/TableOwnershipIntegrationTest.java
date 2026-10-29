package com.salonplatform.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.salonplatform.shared.infrastructure.ModuleOwnership;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every table has one owning module, and the database keeps the ownership rules (CR-021 decision 4): read
 * from a database the application's migrations built. Each rule is also shown a deliberate fault, made in a
 * transaction that is rolled back (PostgreSQL's DDL is transactional), and must name it.
 */
class TableOwnershipIntegrationTest {

    /** The last migration before CR-021: the tables that exist after it keep their names. */
    private static final MigrationVersion LAST_MIGRATION_BEFORE_PREFIXES = MigrationVersion.fromVersion("16");

    private static final ModuleOwnership OWNERSHIP = ModuleOwnership.load();

    private static TestDatabase database;

    private static Set<String> namedBeforePrefixes;

    @BeforeAll
    static void migratedOnceToTheLastUnprefixedVersionAndThenToTheLatest() throws SQLException {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        migrate(LAST_MIGRATION_BEFORE_PREFIXES);
        try (Connection connection = database.connectAsOwner()) {
            namedBeforePrefixes = DatabaseOwnership.tables(connection);
        }
        migrate(MigrationVersion.LATEST);
    }

    @Test
    void everyTableHasExactlyOneOwningModule() throws SQLException {
        try (Connection connection = database.connectAsOwner()) {
            Set<String> tables = DatabaseOwnership.tables(connection);

            assertThat(tables).as("the migrations built the tables").contains("tenant", "identity_staff");
            assertThat(DatabaseOwnership.tablesWithoutExactlyOneOwner(OWNERSHIP, tables))
                    .isEmpty();
        }
    }

    @Test
    void aTableInNoModulesEntryIsFound() throws SQLException {
        List<String> breaches = withFault(
                "CREATE TABLE stray_table (id uuid PRIMARY KEY)",
                connection -> DatabaseOwnership.tablesWithoutExactlyOneOwner(
                        OWNERSHIP, DatabaseOwnership.tables(connection)));

        assertThat(breaches).containsExactly("table stray_table is in the database but in no module's map entry");
    }

    @Test
    void aTableInTheMapButNotInTheDatabaseIsFound() throws SQLException {
        List<String> breaches = withFault(
                "DROP TABLE billing_reminder",
                connection -> DatabaseOwnership.tablesWithoutExactlyOneOwner(
                        OWNERSHIP, DatabaseOwnership.tables(connection)));

        assertThat(breaches)
                .containsExactly("table billing_reminder is in platform's map entry but not in the database");
    }

    @Test
    void aTableListedTwiceIsRefused() {
        String twice = """
                modules:
                  booking:
                    tables: [booking_hold, booking_slot]
                  payment:
                    tables: [payment_refund, booking_hold]
                """;

        assertThatIllegalStateException()
                .isThrownBy(
                        () -> ModuleOwnership.read(new ByteArrayInputStream(twice.getBytes(StandardCharsets.UTF_8))))
                .withMessageContaining("booking_hold (booking, payment)");
    }

    @Test
    void noForeignKeyJoinsTwoModulesExceptToTheSalon() throws SQLException {
        try (Connection connection = database.connectAsOwner()) {
            List<DatabaseOwnership.ForeignKey> keys = DatabaseOwnership.foreignKeys(connection);

            assertThat(keys)
                    .as("the salon's own rows refer to it")
                    .contains(new DatabaseOwnership.ForeignKey("salon_brand", List.of("tenant_id"), "tenant"));
            assertThat(DatabaseOwnership.foreignKeysBetweenModules(OWNERSHIP, keys))
                    .isEmpty();
        }
    }

    @Test
    void aForeignKeyToAnotherModulesTableIsFound() throws SQLException {
        List<String> breaches = withFault(
                "ALTER TABLE identity_guard ADD CONSTRAINT fault FOREIGN KEY (tenant_id) REFERENCES salon_brand (tenant_id)",
                connection -> DatabaseOwnership.foreignKeysBetweenModules(
                        OWNERSHIP, DatabaseOwnership.foreignKeys(connection)));

        assertThat(breaches)
                .containsExactly("foreign key identity_guard[tenant_id] (identity) refers to salon_brand (branding)");
    }

    @Test
    void aForeignKeyToTheSalonOnAnotherColumnIsFound() throws SQLException {
        List<String> breaches = withFault(
                "ALTER TABLE identity_guard ADD COLUMN closed_salon uuid REFERENCES tenant (id)",
                connection -> DatabaseOwnership.foreignKeysBetweenModules(
                        OWNERSHIP, DatabaseOwnership.foreignKeys(connection)));

        assertThat(breaches)
                .containsExactly("foreign key identity_guard[closed_salon] (identity) refers to tenant (platform)");
    }

    @Test
    void everyTableCreatedAfterCr021CarriesItsModulesPrefix() throws SQLException {
        try (Connection connection = database.connectAsOwner()) {
            assertThat(namedBeforePrefixes).as("the tables named before CR-021").contains("tenant", "salon_brand");
            assertThat(DatabaseOwnership.newTablesWithoutTheirModulesPrefix(
                            OWNERSHIP, DatabaseOwnership.tables(connection), namedBeforePrefixes))
                    .isEmpty();
        }
    }

    @Test
    void aNewTableWithoutItsModulesPrefixIsFound() throws SQLException {
        ModuleOwnership withCatalogue =
                ModuleOwnership.read(new ByteArrayInputStream("""
                modules:
                  catalogue:
                    tables: [catalogue_service, service_variant]
                """.getBytes(StandardCharsets.UTF_8)));

        List<String> breaches = withFault(
                "CREATE TABLE catalogue_service (id uuid PRIMARY KEY); CREATE TABLE service_variant (id uuid PRIMARY KEY)",
                connection -> DatabaseOwnership.newTablesWithoutTheirModulesPrefix(
                        withCatalogue, DatabaseOwnership.tables(connection), namedBeforePrefixes));

        assertThat(breaches).containsExactly("table service_variant is new but does not start with catalogue_");
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

    /** Applies a fault as the owner, reads what a rule says about it, and rolls the fault back. */
    private static List<String> withFault(String fault, Rule rule) throws SQLException {
        try (Connection connection = database.connectAsOwner()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(fault);
                return rule.breaches(connection);
            } finally {
                connection.rollback();
            }
        }
    }

    @FunctionalInterface
    private interface Rule {
        List<String> breaches(Connection connection) throws SQLException;
    }
}
