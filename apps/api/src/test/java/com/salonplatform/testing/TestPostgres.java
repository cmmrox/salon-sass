package com.salonplatform.testing;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** The PostgreSQL the developer tests run against: the same major version the product runs on. */
public final class TestPostgres {

    public static final DockerImageName IMAGE = DockerImageName.parse("postgres:18.6");

    private static final PostgreSQLContainer SHARED = newContainer();

    static {
        SHARED.start();
    }

    private TestPostgres() {
    }

    /** One container for the whole test run. It is removed when the JVM exits. */
    public static PostgreSQLContainer shared() {
        return SHARED;
    }

    /** A container of its own, for a test that stops the database or needs an empty one. */
    public static PostgreSQLContainer newContainer() {
        return new PostgreSQLContainer(IMAGE);
    }
}
