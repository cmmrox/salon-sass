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

    private final PostgreSQLContainer postgres;

    private final String url;

    private TestDatabase(PostgreSQLContainer postgres, String url) {
        this.postgres = postgres;
        this.url = url;
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

    public String url() {
        return url;
    }

    public PostgreSQLContainer container() {
        return postgres;
    }

    /** The owner: a superuser here, so row-level security does not apply to it. */
    public JdbcTemplate asOwner() {
        return new JdbcTemplate(new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword()));
    }

    /** A connection of the application's role, for a test that controls its transactions itself. */
    public Connection connectAsApplication() throws SQLException {
        return DriverManager.getConnection(url, TestPostgres.APP_USER, TestPostgres.APP_PASSWORD);
    }

    public Connection connectAsOwner() throws SQLException {
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    /** The application started against this database. */
    public StartedApplication start(String... settings) {
        return StartedApplication.against(url, postgres, settings);
    }
}
