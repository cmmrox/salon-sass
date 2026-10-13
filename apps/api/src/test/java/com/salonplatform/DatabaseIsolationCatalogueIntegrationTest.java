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

        assertThat(tables).extracting(table -> table.get("name")).as("tenant tables")
                .containsExactlyInAnyOrder("salon_brand", "audit_entry", "outbox_message",
                        "identity_staff", "identity_session", "identity_refresh_history", "identity_mfa_challenge",
                        "identity_mfa_step", "identity_rate_bucket", "identity_guard",
                        "identity_customer", "identity_customer_challenge");
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

    /**
     * Every index of a tenant table starts with {@code tenant_id}, unique or not, so that no index
     * can answer a question across salons.
     */
    @Test
    void everyIndexOfATenantTableStartsWithTenantId() {
        List<Map<String, Object>> indexes = owner.queryForList("""
                SELECT c.relname AS name, i.relname AS index,
                       x.indkey[0] = (SELECT a.attnum FROM pg_attribute a
                                       WHERE a.attrelid = c.oid AND a.attname = 'tenant_id') AS starts_with_tenant
                  FROM pg_index x
                  JOIN pg_class c ON c.oid = x.indrelid
                  JOIN pg_class i ON i.oid = x.indexrelid
                 WHERE c.relnamespace = 'public'::regnamespace
                   AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'tenant_id')
                """);

        assertThat(indexes).extracting(index -> index.get("index")).contains("audit_entry_pkey", "audit_entry_subject_idx",
                "outbox_message_pkey", "outbox_message_due_idx", "outbox_message_lease_idx");
        assertThat(indexes).allSatisfy(index -> assertThat(index)
                .as("%s on %s", index.get("index"), index.get("name")).containsEntry("starts_with_tenant", true));
    }

    /**
     * What the application's role may do to each table, and nothing more: it reads salon brands,
     * adds to and reads the audit trail, and works the outbox. It has nothing on a platform table.
     */
    @Test
    void theApplicationRoleHoldsExactlyTheTablePrivilegesItNeeds() {
        List<Map<String, Object>> grants = owner.queryForList("""
                SELECT table_name AS name, string_agg(privilege_type, ',' ORDER BY privilege_type) AS privileges
                  FROM information_schema.role_table_grants
                 WHERE grantee = ? AND table_schema = 'public'
                 GROUP BY table_name
                """, TestPostgres.APP_USER);

        assertThat(grants).extracting(grant -> grant.get("name"), grant -> grant.get("privileges"))
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple("salon_brand", "SELECT"),
                        org.assertj.core.api.Assertions.tuple("audit_entry", "INSERT,SELECT"),
                        org.assertj.core.api.Assertions.tuple("outbox_message", "DELETE,INSERT,SELECT,UPDATE"),
                        org.assertj.core.api.Assertions.tuple("identity_staff", "DELETE,INSERT,SELECT,UPDATE"),
                        org.assertj.core.api.Assertions.tuple("identity_session", "DELETE,INSERT,SELECT,UPDATE"),
                        org.assertj.core.api.Assertions.tuple("identity_refresh_history", "DELETE,INSERT,SELECT"),
                        org.assertj.core.api.Assertions.tuple("identity_mfa_challenge", "DELETE,INSERT,SELECT,UPDATE"),
                        org.assertj.core.api.Assertions.tuple("identity_mfa_step", "DELETE,INSERT,SELECT"),
                        org.assertj.core.api.Assertions.tuple("identity_rate_bucket", "DELETE,INSERT,SELECT,UPDATE"),
                        org.assertj.core.api.Assertions.tuple("identity_guard", "INSERT,SELECT,UPDATE"),
                        org.assertj.core.api.Assertions.tuple("identity_customer", "DELETE,INSERT,SELECT,UPDATE"),
                        org.assertj.core.api.Assertions.tuple("identity_customer_challenge", "DELETE,INSERT,SELECT,UPDATE"));
    }

    /**
     * The functions that run with their owner's rights are the application's only way to a platform
     * table or across salons. Each is listed here, so that a new one is looked at; each pins its
     * search path, cannot be run by everyone, and can be run by the application's role.
     */
    @Test
    void everyFunctionThatRunsWithItsOwnersRightsIsKnownPinsItsSearchPathAndIsNotPublic() {
        List<Map<String, Object>> functions = owner.queryForList("""
                SELECT p.proname AS name,
                       coalesce(array_to_string(p.proconfig, ';'), '') AS settings,
                       has_function_privilege('public', p.oid, 'EXECUTE') AS public_may_run,
                       has_function_privilege(?, p.oid, 'EXECUTE') AS application_may_run
                  FROM pg_proc p
                 WHERE p.pronamespace = 'public'::regnamespace AND p.prosecdef
                """, TestPostgres.APP_USER);

        assertThat(functions).extracting(function -> function.get("name")).containsExactlyInAnyOrder("tenant_by_slug",
                "tenant_by_id", "active_tenant_ids", "outbox_due", "outbox_backlog", "job_claim", "job_finish");
        assertThat(functions).allSatisfy(function -> {
            assertThat((String) function.get("settings")).as("%s", function.get("name"))
                    .isEqualTo("search_path=pg_catalog, pg_temp");
            assertThat(function).as("%s", function.get("name")).containsEntry("public_may_run", false)
                    .containsEntry("application_may_run", true);
        });
    }

    /** The product's grants: what the application's role is refused, table by table. */
    @ParameterizedTest
    @ValueSource(strings = {
        "INSERT INTO salon_brand (tenant_id, display_name, brand_colour) VALUES (gen_random_uuid(), 'x', '#000000')",
        "UPDATE salon_brand SET display_name = 'x'",
        "DELETE FROM salon_brand",
        "INSERT INTO tenant (id, slug) VALUES (gen_random_uuid(), 'sneaky')",
        "UPDATE tenant SET status = 'suspended'",
        "CREATE TABLE intruder (id int)",
        "UPDATE audit_entry SET reason = 'changed'",
        "DELETE FROM audit_entry",
        "TRUNCATE audit_entry",
        "TRUNCATE outbox_message",
        "UPDATE identity_mfa_step SET step=0",
        "UPDATE identity_refresh_history SET credential_hash='x'",
        "DELETE FROM identity_guard",
        "SELECT * FROM scheduled_job",
        "INSERT INTO scheduled_job (name, next_run_at, created_at, updated_at) VALUES ('sneaky', now(), now(), now())",
        "UPDATE scheduled_job SET locked_by = NULL, locked_until = NULL",
        "DELETE FROM scheduled_job",
        "ALTER TABLE outbox_message DISABLE ROW LEVEL SECURITY",
        "DROP FUNCTION outbox_due(timestamptz, text[], integer, integer)",
    })
    void theApplicationRoleIsRefusedEverythingItDoesNotNeed(String sql) throws SQLException {
        try (Connection connection = database.connectAsApplication(); Statement statement = connection.createStatement()) {
            assertThatExceptionOfType(SQLException.class).isThrownBy(() -> statement.execute(sql))
                    .satisfies(refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
        }
    }
}
