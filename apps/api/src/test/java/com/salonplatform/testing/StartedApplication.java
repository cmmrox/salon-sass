package com.salonplatform.testing;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import com.salonplatform.SalonApiApplication;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The application started the way {@code main} starts it, on a free port, against a database the
 * test names. For the tests that must start it more than once, or take its database away.
 *
 * <p>It connects as {@link TestPostgres#APP_USER} and migrates as the container's own user, as the
 * product does everywhere.
 */
public final class StartedApplication implements AutoCloseable {

    private final ConfigurableApplicationContext context;

    private StartedApplication(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /** Against the container's own database. */
    public static StartedApplication against(PostgreSQLContainer postgres, String... settings) {
        return against(postgres.getJdbcUrl(), postgres, settings);
    }

    /**
     * Against another database in the same container, with further settings given the way a
     * command line gives them.
     */
    public static StartedApplication against(String jdbcUrl, PostgreSQLContainer postgres, String... settings) {
        List<String> arguments = new ArrayList<>();
        if (List.of(settings).stream().noneMatch(setting -> setting.startsWith("--server.port="))) {
            arguments.add("--server.port=0");
        }
        arguments.addAll(List.of(
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + TestPostgres.APP_USER,
                "--spring.datasource.password=" + TestPostgres.APP_PASSWORD,
                "--spring.flyway.user=" + postgres.getUsername(),
                "--spring.flyway.password=" + postgres.getPassword()));
        arguments.addAll(List.of(settings));
        return new StartedApplication(new SpringApplicationBuilder(SalonApiApplication.class)
                .profiles("test")
                .run(arguments.toArray(String[]::new)));
    }

    public Http http() {
        return new Http(port());
    }

    public int port() {
        return context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
    }

    /**
     * The address each of the server's connectors is bound to; "*" for every interface. What the
     * server listens on, read from the server itself.
     */
    public List<String> listenAddresses() {
        TomcatWebServer server = (TomcatWebServer) ((WebServerApplicationContext) context).getWebServer();
        List<String> addresses = new ArrayList<>();
        for (Connector connector : server.getTomcat().getService().findConnectors()) {
            InetAddress address = ((AbstractProtocol<?>) connector.getProtocolHandler()).getAddress();
            addresses.add(address == null ? "*" : address.getHostAddress());
        }
        return addresses;
    }

    /** The application's own connections: its role, under row-level security. */
    public JdbcTemplate jdbc() {
        return context.getBean(JdbcTemplate.class);
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    @Override
    public void close() {
        context.close();
    }
}
