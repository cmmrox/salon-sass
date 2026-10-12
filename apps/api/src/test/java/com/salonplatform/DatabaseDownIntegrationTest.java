package com.salonplatform;

import java.net.http.HttpResponse;
import java.time.Duration;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestPostgres;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseDownIntegrationTest {

    @Test
    void healthReportsDownQuicklyWhenTheDatabaseStops() {
        try (PostgreSQLContainer postgres = TestPostgres.newContainer()) {
            postgres.start();
            try (StartedApplication application = StartedApplication.against(postgres.getJdbcUrl(),
                    postgres.getUsername(), postgres.getPassword())) {
                assertThat(application.http().get("/actuator/health").statusCode()).isEqualTo(200);

                postgres.stop();

                long started = System.nanoTime();
                HttpResponse<String> response = application.http().get("/actuator/health");
                Duration took = Duration.ofNanos(System.nanoTime() - started);

                assertThat(response.statusCode()).isEqualTo(503);
                DocumentContext body = JsonPath.parse(response.body());
                assertThat(body.<String>read("$.status")).isEqualTo("DOWN");
                assertThat(body.<String>read("$.components.db.status")).isEqualTo("DOWN");
                assertThat(took).isLessThan(Duration.ofSeconds(5));

                // The service itself is still answering: only what needs the database is down.
                assertThat(application.http().get("/api/v1/meta").statusCode()).isEqualTo(200);
            }
        }
    }
}
