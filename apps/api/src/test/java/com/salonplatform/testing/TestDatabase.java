package com.salonplatform.testing;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * One database in a test container, reached directly as either role: as the owner, the way
 * migrations and an administrator reach it, or as the application's role, the way the API does.
 */
public final class TestDatabase {

    /** An owning role that is not a superuser, as the owning role is everywhere but on a developer's machine. */
    public static final String ORDINARY_OWNER = "salon_owner_test";

    private static final String ORDINARY_OWNER_PASSWORD = "salon-owner-test";

    private final PostgreSQLContainer postgres;

    private final String url;

    /** The role that runs the migrations and owns the tables. */
    private final String migrationUser;

    private final String migrationPassword;

    private TestDatabase(PostgreSQLContainer postgres, String url) {
        this(postgres, url, postgres.getUsername(), postgres.getPassword());
    }

    private TestDatabase(PostgreSQLContainer postgres, String url, String migrationUser, String migrationPassword) {
        this.postgres = postgres;
        this.url = url;
        this.migrationUser = migrationUser;
        this.migrationPassword = migrationPassword;
    }

    /** The container's own database. */
    public static TestDatabase of(PostgreSQLContainer postgres) {
        return new TestDatabase(postgres, postgres.getJdbcUrl());
    }

    /** A new, empty database in the container, for a test that must start from nothing. */
    public static TestDatabase createEmpty(PostgreSQLContainer postgres) {
        String name = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        catch (SQLException ex) {
            throw new IllegalStateException("could not create database " + name, ex);
        }
        return new TestDatabase(postgres, postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + name));
    }

    /**
     * A new, empty database owned by {@link #ORDINARY_OWNER}, a role that is not a superuser and
     * cannot bypass row-level security, which runs the migrations and so owns every table. Forced
     * row-level security then holds for the owner too, as it does in every environment but a
     * developer's machine, where the owning role is the container's superuser. {@link #asOwner()}
     * stays the container's superuser, so that a test can still arrange any salon's rows.
     */
    public static TestDatabase createEmptyOwnedByAnOrdinaryRole(PostgreSQLContainer postgres) {
        String name = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement statement = connection.createStatement()) {
            // Two tests may arrive here at once in one container; the lock makes "create if absent" safe.
            statement.execute("SELECT pg_advisory_lock(7401)");
            statement.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + ORDINARY_OWNER
                    + "') THEN CREATE ROLE " + ORDINARY_OWNER + " LOGIN PASSWORD '" + ORDINARY_OWNER_PASSWORD
                    + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; END IF; END $$");
            statement.execute("SELECT pg_advisory_unlock(7401)");
            statement.execute("CREATE DATABASE " + name + " OWNER " + ORDINARY_OWNER);
        }
        catch (SQLException ex) {
            throw new IllegalStateException("could not create database " + name, ex);
        }
        return new TestDatabase(postgres, postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + name),
                ORDINARY_OWNER, ORDINARY_OWNER_PASSWORD);
    }

    public String url() {
        return url;
    }

    public PostgreSQLContainer container() {
        return postgres;
    }

    /** The container's own user: a superuser, so row-level security does not apply to it. */
    public JdbcTemplate asOwner() {
        return new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
    }

    /** A connection of the application's role, for a test that controls its transactions itself. */
    public Connection connectAsApplication() throws SQLException {
        return DriverManager.getConnection(url, TestPostgres.APP_USER, TestPostgres.APP_PASSWORD);
    }

    /** A connection of the platform runtime role, for a test that controls its transactions itself. */
    public Connection connectAsPlatform() throws SQLException {
        return DriverManager.getConnection(url, TestPostgres.PLATFORM_USER, TestPostgres.PLATFORM_PASSWORD);
    }

    public Connection connectAsOwner() throws SQLException {
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    /** The application started against this database, with a test's configuration class added to it. */
    public StartedApplication startWith(Class<?> testConfiguration, String... settings) {
        return StartedApplication.against(url, migrationUser, migrationPassword, testConfiguration, settings);
    }

    /** The application started against this database. */
    public StartedApplication start(String... settings) {
        return StartedApplication.against(url, migrationUser, migrationPassword, settings);
    }
}
