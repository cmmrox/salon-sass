package com.salonplatform.testing;

import java.util.ArrayList;
import java.util.List;

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
        return against(jdbcUrl, username, password, new String[0]);
    }

    /** As above, with further settings given the way a command line gives them. */
    public static StartedApplication against(String jdbcUrl, String username, String password, String... settings) {
        List<String> arguments = new ArrayList<>(List.of("--server.port=0",
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password));
        arguments.addAll(List.of(settings));
        return new StartedApplication(new SpringApplicationBuilder(SalonApiApplication.class)
                .profiles("test")
                .run(arguments.toArray(String[]::new)));
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
