package com.salonplatform.testing;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The PostgreSQL the developer tests run against: the same major version the product runs on, with
 * the same two roles. The container's own user owns the tables and runs the migrations;
 * {@link #APP_USER} is what the application connects as, created the way an environment creates it:
 * able to log in, and nothing more.
 */
public final class TestPostgres {

    public static final DockerImageName IMAGE = DockerImageName.parse("postgres:18.6");

    /** The application's role, as in every environment: owns nothing, cannot bypass row-level security. */
    public static final String APP_USER = "salon_app";

    public static final String APP_PASSWORD = "salon-app-test";

    /** The platform runtime role (F03-S01): owns nothing, cannot bypass row-level security. */
    public static final String PLATFORM_USER = "salon_platform";

    public static final String PLATFORM_PASSWORD = "salon-platform-test";

    private static final PostgreSQLContainer SHARED = newContainer();

    static {
        SHARED.start();
    }

    private TestPostgres() {}

    /** One container for the whole test run. It is removed when the JVM exits. */
    public static PostgreSQLContainer shared() {
        return SHARED;
    }

    /**
     * A container of its own, for a test that stops the database or needs an empty one. Its command is
     * Testcontainers' default with room for more connections: every cached test context keeps its pools
     * open (14 connections), and some tests start applications of their own beside them, which overran
     * PostgreSQL's default of 100 and failed whichever test came next.
     */
    public static PostgreSQLContainer newContainer() {
        return new PostgreSQLContainer(IMAGE)
                .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=200")
                .withInitScript("db/test-application-role.sql");
    }
}
