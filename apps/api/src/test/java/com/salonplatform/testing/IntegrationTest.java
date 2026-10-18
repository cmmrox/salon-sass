package com.salonplatform.testing;

import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the integration tests: the whole application on a real port, against real PostgreSQL in
 * a container, connected as the application's role with migrations run as the owner.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// Spring Boot's test support turns tracing export off, and W3C propagation with it; this keeps
// tracing as the product runs it. Nothing is exported: no OTLP endpoint is configured.
@AutoConfigureTracing
public abstract class IntegrationTest {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestPostgres.shared()::getJdbcUrl);
        registry.add("spring.datasource.username", () -> TestPostgres.APP_USER);
        registry.add("spring.datasource.password", () -> TestPostgres.APP_PASSWORD);
        registry.add("spring.flyway.user", TestPostgres.shared()::getUsername);
        registry.add("spring.flyway.password", TestPostgres.shared()::getPassword);
        registry.add("salon.platform.datasource.username", () -> TestPostgres.PLATFORM_USER);
        registry.add("salon.platform.datasource.password", () -> TestPostgres.PLATFORM_PASSWORD);
    }

    protected Http http() {
        return new Http(port);
    }

    protected int port() {
        return port;
    }
}
