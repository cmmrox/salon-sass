package com.salonplatform.testing;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the integration tests: the whole application on a real port, against real PostgreSQL in
 * a container.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestPostgres.shared()::getJdbcUrl);
        registry.add("spring.datasource.username", TestPostgres.shared()::getUsername);
        registry.add("spring.datasource.password", TestPostgres.shared()::getPassword);
    }

    protected Http http() {
        return new Http(port);
    }
}
