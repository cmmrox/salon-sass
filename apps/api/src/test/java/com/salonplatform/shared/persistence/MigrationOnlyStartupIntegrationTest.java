package com.salonplatform.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class MigrationOnlyStartupIntegrationTest {
    @Test
    void nonWebMigrationProfileAppliesTheRealSchemaWithoutServletInfrastructure() {
        try (var database = TestPostgres.newContainer()) {
            database.start();
            try (var application = StartedApplication.against(
                    database, "--spring.profiles.active=test,migrate", "--spring.main.web-application-type=none")) {
                assertThat(application.bean(Environment.class).getProperty("local.server.port"))
                        .isNull();
                assertThat(application.bean(Flyway.class).info().pending()).isEmpty();
                assertThat(application.bean(Flyway.class).info().applied()).hasSizeGreaterThanOrEqualTo(10);
            }
        }
    }
}
