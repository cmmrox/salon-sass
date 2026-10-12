package com.salonplatform;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

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

/**
 * What the database itself promises, read from its catalogue after every migration: every tenant
 * table carries the checklist of docs/architecture/multi-tenancy.md, and the application's role is
 * one that cannot get round it. Written over every table with a {@code tenant_id}, so that a table
 * added later without the checklist fails here.
 */
class DatabaseIsolationCatalogueIntegrationTest {

    private static TestDatabase database;

    private static JdbcTemplate owner;

    @BeforeAll
    static void migrated() {
        database = TestDatabase.createEmpty(TestPostgres.shared());
        try (StartedApplication migrated = database.start()) {
            // Started once to apply the migrations.
        }
        owner = database.asOwner();
    }

    @Test
    void everyTenantTableHasRowLevelSecurityEnabledAndForcedWithTheTenantIsolationPolicy() {
        List<Map<String, Object>> tables = owner.queryForList("""
                SELECT c.relname AS name, c.relrowsecurity AS enabled, c.relforcerowsecurity AS forced,
                       EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid AND p.polname = 'tenant_isolation')
                           AS policy
                  FROM pg_class c
                  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
                 WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p')
                """);

        assertThat(tables).extracting(table -> table.get("name")).as("tenant tables").contains("salon_brand");
        assertThat(tables).allSatisfy(table -> assertThat(table)
                .as("%s", table.get("name"))
                .containsEntry("enabled", true).containsEntry("forced", true).containsEntry("policy", true));
    }

    @Test
    void everyTenantIdIsRequiredAndRefersToATenant() {
        List<Map<String, Object>> columns = owner.queryForList("""
                SELECT c.relname AS name, a.attnotnull AS required,
                       EXISTS (SELECT 1 FROM pg_constraint k
                                WHERE k.conrelid = c.oid AND k.contype = 'f'
                                  AND k.confrelid = 'public.tenant'::regclass
                                  AND k.conkey = ARRAY[a.attnum]) AS refers_to_tenant
                  FROM pg_class c
                  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'tenant_id' AND NOT a.attisdropped
                 WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p')
                """);

        assertThat(columns).isNotEmpty().allSatisfy(column -> assertThat(column).as("%s", column.get("name"))
                .containsEntry("required", true).containsEntry("refers_to_tenant", true));
    }

    @Test
    void everyUniqueConstraintAndUniqueIndexOnATenantTableIncludesTenantId() {
        List<Map<String, Object>> uniques = owner.queryForList("""
                SELECT c.relname AS name, i.relname AS index,
                       (SELECT a.attnum FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'tenant_id')
                           = ANY (x.indkey::smallint[]) AS includes_tenant
                  FROM pg_index x
                  JOIN pg_class c ON c.oid = x.indrelid
                  JOIN pg_class i ON i.oid = x.indexrelid
                 WHERE x.indisunique AND c.relnamespace = 'public'::regnamespace
                   AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'tenant_id')
                """);

        assertThat(uniques).isNotEmpty().allSatisfy(unique -> assertThat(unique)
                .as("%s on %s", unique.get("index"), unique.get("name")).containsEntry("includes_tenant", true));
    }

    @Test
    void theApplicationRoleIsNotASuperuserCannotBypassRowLevelSecurityAndOwnsNothing() {
        Map<String, Object> role = owner.queryForMap("""
                SELECT r.rolsuper AS superuser, r.rolbypassrls AS bypass, r.rolcreaterole AS createrole,
                       r.rolcreatedb AS createdb,
                       (SELECT count(*) FROM pg_class c WHERE c.relowner = r.oid) AS owned
                  FROM pg_roles r WHERE r.rolname = ?
                """, TestPostgres.APP_USER);

        assertThat(role).containsEntry("superuser", false).containsEntry("bypass", false)
                .containsEntry("createrole", false).containsEntry("createdb", false).containsEntry("owned", 0L);
    }

    @Test
    void theApplicationRoleCannotReadTheRegistryButCanAskItAboutOneSlug() throws SQLException {
        owner.update("INSERT INTO tenant (id, slug) VALUES ('d4d4d4d4-0000-4000-8000-000000000004', 'catalogue-check')");
        try (Connection connection = database.connectAsApplication(); Statement statement = connection.createStatement()) {
            assertThatExceptionOfType(SQLException.class).isThrownBy(() -> statement.executeQuery("SELECT * FROM tenant"))
                    .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
            try (var row = statement.executeQuery("SELECT id, status FROM tenant_by_slug('catalogue-check')")) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("id")).isEqualTo("d4d4d4d4-0000-4000-8000-000000000004");
                assertThat(row.getString("status")).isEqualTo("active");
            }
            try (var row = statement.executeQuery("SELECT id FROM tenant_by_slug('no-such-salon')")) {
                assertThat(row.next()).isFalse();
            }
        }
    }

    /** The product's grants: the application only reads salon brands in this release. */
    @ParameterizedTest
    @ValueSource(strings = {
        "INSERT INTO salon_brand (tenant_id, display_name, brand_colour) VALUES (gen_random_uuid(), 'x', '#000000')",
        "UPDATE salon_brand SET display_name = 'x'",
        "DELETE FROM salon_brand",
        "INSERT INTO tenant (id, slug) VALUES (gen_random_uuid(), 'sneaky')",
        "UPDATE tenant SET status = 'suspended'",
        "CREATE TABLE intruder (id int)",
    })
    void theApplicationRoleIsRefusedEverythingItDoesNotNeed(String sql) throws SQLException {
        try (Connection connection = database.connectAsApplication(); Statement statement = connection.createStatement()) {
            assertThatExceptionOfType(SQLException.class).isThrownBy(() -> statement.execute(sql))
                    .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
        }
    }
}
