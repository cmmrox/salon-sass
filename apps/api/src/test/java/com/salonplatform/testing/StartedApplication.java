package com.salonplatform.testing;

import com.salonplatform.SalonApiApplication;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The application started the way {@code main} starts it, on a free port, against a database the
 * test names. For the tests that must start it more than once, or take its database away.
 */
public final class StartedApplication implements AutoCloseable {

    private final ConfigurableApplicationContext context;

    private StartedApplication(ConfigurableApplicationContext context) {
        this.context = context;
    }

    public static StartedApplication against(String jdbcUrl, String username, String password) {
        return new StartedApplication(new SpringApplicationBuilder(SalonApiApplication.class)
                .profiles("test")
                .run("--server.port=0",
                        "--spring.datasource.url=" + jdbcUrl,
                        "--spring.datasource.username=" + username,
                        "--spring.datasource.password=" + password));
    }

    public Http http() {
        return new Http(context.getEnvironment().getRequiredProperty("local.server.port", Integer.class));
    }

    public JdbcTemplate jdbc() {
        return context.getBean(JdbcTemplate.class);
    }

    @Override
    public void close() {
        context.close();
    }
}
