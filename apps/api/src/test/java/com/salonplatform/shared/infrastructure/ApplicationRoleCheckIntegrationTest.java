package com.salonplatform.shared.infrastructure;

import com.salonplatform.SalonApiApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-3 does not depend on every environment being configured right: given a database role that could
 * get round row-level security, the application does not start.
 */
class ApplicationRoleCheckIntegrationTest {

    @Test
    void theApplicationDoesNotStartAsASuperuser() {
        PostgreSQLContainer postgres = TestPostgres.shared();
        TestDatabase database = TestDatabase.createEmpty(postgres);

        assertThatThrownBy(() -> start(database, postgres.getUsername(), postgres.getPassword()))
                .hasStackTraceContaining("is a superuser, so row-level security would not hold for it");
    }

    @Test
    void theApplicationDoesNotStartAsARoleThatBypassesRowLevelSecurity() {
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());
        database.asOwner().execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'salon_bypass') THEN
                        CREATE ROLE salon_bypass LOGIN PASSWORD 'bypass-test' BYPASSRLS;
                    END IF;
                END $$""");

        assertThatThrownBy(() -> start(database, "salon_bypass", "bypass-test"))
                .hasStackTraceContaining("has BYPASSRLS, so row-level security would not hold for it");
    }

    @Test
    void theApplicationDoesNotStartAsARoleThatOwnsATable() {
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());
        database.start().close();
        database.asOwner().execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'salon_owning') THEN
                        CREATE ROLE salon_owning LOGIN PASSWORD 'owning-test';
                    END IF;
                END $$""");
        database.asOwner().execute("CREATE TABLE owned_by_the_application (id int)");
        database.asOwner().execute("ALTER TABLE owned_by_the_application OWNER TO salon_owning");

        assertThatThrownBy(() -> start(database, "salon_owning", "owning-test"))
                .hasStackTraceContaining("owns tables, so row-level security would not hold for it");
    }

    private static void start(TestDatabase database, String username, String password) {
        PostgreSQLContainer postgres = database.container();
        new SpringApplicationBuilder(SalonApiApplication.class).profiles("test").run(
                "--server.port=0",
                "--spring.datasource.url=" + database.url(),
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password,
                "--spring.flyway.user=" + postgres.getUsername(),
                "--spring.flyway.password=" + postgres.getPassword()).close();
    }
}
