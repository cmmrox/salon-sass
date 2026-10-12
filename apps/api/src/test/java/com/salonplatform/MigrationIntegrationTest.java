package com.salonplatform;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestPostgres;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationIntegrationTest {

    private static final String HISTORY =
            "SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank";

    @Test
    void theFirstStartAppliesTheBaselineAndTheSecondAppliesNothing() throws SQLException {
        PostgreSQLContainer postgres = TestPostgres.shared();
        String database = "migration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        String emptyDatabaseUrl = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);

        List<Map<String, Object>> afterFirstStart;
        try (StartedApplication application = StartedApplication.against(emptyDatabaseUrl, postgres.getUsername(),
                postgres.getPassword())) {
            afterFirstStart = application.jdbc().queryForList(HISTORY);
        }

        assertThat(afterFirstStart).hasSize(1);
        assertThat(afterFirstStart.getFirst())
                .containsEntry("version", "1")
                .containsEntry("description", "baseline")
                .containsEntry("success", true);

        try (StartedApplication application = StartedApplication.against(emptyDatabaseUrl, postgres.getUsername(),
                postgres.getPassword())) {
            assertThat(application.jdbc().queryForList(HISTORY)).isEqualTo(afterFirstStart);
            assertThat(application.http().get("/actuator/health").statusCode()).isEqualTo(200);
        }
    }
}
