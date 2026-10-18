package com.salonplatform.platform;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F03-S01 AC-15 and the bootstrap seam on real PostgreSQL: the platform runtime role cannot touch
 * salon tables, the tenant role cannot touch platform tables or run the bootstrap functions, the
 * functions bootstrap only a salon created in the calling transaction, once, keep row-level security
 * forced and restore the caller's setting; and the API refuses to start on a misconfigured platform
 * role instead of falling back to another.
 */
class PlatformDatabaseIntegrationTest {

    private static TestDatabase database;

    private static JdbcTemplate owner;

    private static final UUID LEGACY = UUID.fromString("d4d4d4d4-0000-4000-8000-000000000004");

    @BeforeAll
    static void migrated() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication migrated = database.start()) {
            // Started once to apply the migrations.
        }
        owner = database.asOwner();
        owner.update("INSERT INTO tenant (id, slug, status) VALUES (?, 'legacy-salon', 'active')", LEGACY);
        owner.update("INSERT INTO salon_brand (tenant_id, display_name, brand_colour) VALUES (?, 'Legacy', '#D6086A')",
                LEGACY);
    }

    /** AC-20: an ordinary (non-local) start creates no operator account and no plan. */
    @Test
    void startingTheApplicationCreatesNoDefaultOperator() {
        assertThat(owner.queryForObject("SELECT count(*) FROM operator_user", Integer.class)).isZero();
        assertThat(owner.queryForObject("SELECT count(*) FROM plan", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT * FROM salon_brand",
        "INSERT INTO salon_brand (tenant_id, display_name, brand_colour) VALUES ('d4d4d4d4-0000-4000-8000-000000000004', 'x', '#000000')",
        "SELECT * FROM identity_staff",
        "INSERT INTO identity_staff (id, tenant_id, created_at, updated_at, email, password_hash, role, active, professional_grant, version) VALUES (gen_random_uuid(), 'd4d4d4d4-0000-4000-8000-000000000004', now(), now(), 'x@example.test', 'x', 'OWNER', true, false, 0)",
        "SELECT * FROM identity_session",
        "SELECT * FROM identity_customer",
        "SELECT * FROM audit_entry",
        "SELECT * FROM outbox_message",
        "UPDATE tenant SET status = 'suspended'",
        "DELETE FROM tenant",
        "UPDATE plan SET name = 'x'",
        "DELETE FROM provisioning_receipt",
        "UPDATE platform_audit_entry SET action = 'x'",
        "DELETE FROM operator_user",
        "SELECT tenant_by_slug('alpha')",
        "SELECT * FROM scheduled_job",
        "CREATE TABLE intruder (id int)",
    })
    void thePlatformRoleIsRefusedTenantTablesAndEverythingElseItDoesNotNeed(String sql) throws SQLException {
        try (Connection connection = database.connectAsPlatform(); Statement statement = connection.createStatement()) {
            assertThatExceptionOfType(SQLException.class).isThrownBy(() -> statement.execute(sql))
                    .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
        }
    }

    @Test
    void theBootstrapFunctionsRefuseASalonNotCreatedInTheCallingTransaction() throws SQLException {
        try (Connection connection = database.connectAsPlatform()) {
            connection.setAutoCommit(false);
            assertThatThrownBy(() -> bootstrapOwner(connection, LEGACY))
                    .isInstanceOfSatisfying(SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("42501"));
            connection.rollback();
            assertThatThrownBy(() -> bootstrapBrand(connection, LEGACY))
                    .isInstanceOfSatisfying(SQLException.class, ex -> assertThat(ex.getSQLState()).isEqualTo("42501"));
            connection.rollback();
        }
        assertThat(owner.queryForObject("SELECT count(*) FROM identity_staff WHERE tenant_id = ?", Integer.class, LEGACY))
                .isZero();
    }

    @Test
    void theBootstrapFunctionsRunOnceForANewSalonAndRestoreTheCallersTenantSetting() throws SQLException {
        UUID salon = UUID.randomUUID();
        try (Connection connection = database.connectAsPlatform()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO tenant (id, slug, status, created_at, updated_at) VALUES (?, ?, 'active', now(), now())")) {
                insert.setObject(1, salon);
                insert.setString(2, "boot-" + salon.toString().substring(0, 8));
                insert.executeUpdate();
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.tenant_id', 'caller-value', true)");
            }
            bootstrapOwner(connection, salon);
            bootstrapBrand(connection, salon);
            assertThat(setting(connection)).isEqualTo("caller-value");
            assertThatThrownBy(() -> bootstrapOwner(connection, salon)).isInstanceOf(SQLException.class);
            connection.rollback();
        }
        // Rolled back: nothing of it survives.
        assertThat(owner.queryForObject("SELECT count(*) FROM tenant WHERE id = ?", Integer.class, salon)).isZero();

        try (Connection connection = database.connectAsPlatform()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO tenant (id, slug, status, created_at, updated_at) VALUES (?, ?, 'active', now(), now())")) {
                insert.setObject(1, salon);
                insert.setString(2, "boot-" + salon.toString().substring(0, 8));
                insert.executeUpdate();
            }
            bootstrapOwner(connection, salon);
            bootstrapBrand(connection, salon);
            assertThat(setting(connection)).isEmpty();
            connection.commit();
        }
        assertThat(owner.queryForObject("SELECT role FROM identity_staff WHERE tenant_id = ?", String.class, salon))
                .isEqualTo("OWNER");
        assertThat(owner.queryForObject("SELECT mfa_secret FROM identity_staff WHERE tenant_id = ?", String.class, salon))
                .isNull();
        assertThat(owner.queryForObject("SELECT brand_colour FROM salon_brand WHERE tenant_id = ?", String.class, salon))
                .isEqualTo("#111827");
        // Row-level security stays forced on the tables the functions wrote.
        assertThat(owner.queryForObject("SELECT relforcerowsecurity FROM pg_class WHERE relname = 'identity_staff'",
                Boolean.class)).isTrue();
        // A second bootstrap of a committed salon is refused, for both modules.
        try (Connection connection = database.connectAsPlatform()) {
            connection.setAutoCommit(false);
            assertThatThrownBy(() -> bootstrapOwner(connection, salon)).isInstanceOf(SQLException.class);
            connection.rollback();
        }
    }

    @Test
    void theApplicationRefusesToStartWithoutAnExplicitSeparatePlatformRole() {
        assertThatThrownBy(() -> database.start("--salon.platform.datasource.username=")).hasStackTraceContaining(
                "salon.platform.datasource.username");
        assertThatThrownBy(() -> database.start("--salon.platform.datasource.username=" + TestPostgres.APP_USER,
                "--salon.platform.datasource.password=" + TestPostgres.APP_PASSWORD))
                .hasStackTraceContaining("must be a role of its own");
    }

    @Test
    void theApplicationRefusesAPlatformRoleThatOwnsATableOrBypassesRowLevelSecurity() throws SQLException {
        String role = "salon_platform_owner_" + UUID.randomUUID().toString().substring(0, 8);
        TestDatabase empty = database;
        try (Connection admin = empty.connectAsOwner(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE ROLE " + role + " LOGIN PASSWORD 'owner-test' NOSUPERUSER");
            statement.execute("GRANT CREATE ON SCHEMA public TO " + role);
            statement.execute("SET ROLE " + role);
            statement.execute("CREATE TABLE owned_by_platform (id int)");
            statement.execute("RESET ROLE");
        }
        assertThatThrownBy(() -> empty.start("--salon.platform.datasource.username=" + role,
                "--salon.platform.datasource.password=owner-test")).hasStackTraceContaining("owns tables");

        String bypass = "salon_platform_bypass_" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection admin = empty.connectAsOwner(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE ROLE " + bypass + " LOGIN PASSWORD 'bypass-test' NOSUPERUSER BYPASSRLS");
        }
        assertThatThrownBy(() -> empty.start("--salon.platform.datasource.username=" + bypass,
                "--salon.platform.datasource.password=bypass-test")).hasStackTraceContaining("BYPASSRLS");
    }

    private static void bootstrapOwner(Connection connection, UUID salon) throws SQLException {
        try (PreparedStatement call = connection.prepareStatement(
                "SELECT identity_bootstrap_owner(?, ?, 'Owner', ?, 'hash', now())")) {
            call.setObject(1, salon);
            call.setObject(2, UUID.randomUUID());
            call.setString(3, "owner-" + UUID.randomUUID() + "@example.test");
            call.execute();
        }
    }

    private static void bootstrapBrand(Connection connection, UUID salon) throws SQLException {
        try (PreparedStatement call = connection.prepareStatement(
                "SELECT branding_bootstrap_brand(?, 'New Salon', '#111827', now())")) {
            call.setObject(1, salon);
            call.execute();
        }
    }

    private static String setting(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT coalesce(current_setting('app.tenant_id', true), '')")) {
            row.next();
            return row.getString(1);
        }
    }
}
