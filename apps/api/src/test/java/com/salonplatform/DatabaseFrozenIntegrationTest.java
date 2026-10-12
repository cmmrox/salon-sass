package com.salonplatform;

import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestPostgres;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.jdbc.core.ConnectionCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A database that still accepts connections and no longer answers: overloaded, or behind a network
 * that drops packets without a word. Nothing is refused, so nothing fails on its own; every wait
 * has to end because the application set a limit on it.
 */
class DatabaseFrozenIntegrationTest {

    /** AC-2 gives a stopped database five seconds. A silent one is given the same. */
    private static final Duration LIMIT = Duration.ofSeconds(5);

    @Test
    void healthReportsDownQuicklyWhileTheDatabaseDoesNotAnswerAndRecoversWhenItDoes() {
        try (PostgreSQLContainer postgres = TestPostgres.newContainer()) {
            postgres.start();
            try (StartedApplication application = StartedApplication.against(postgres)) {
                Http http = application.http();
                // The pool now holds a connection it used a moment ago, which it hands out again
                // without testing it first.
                for (int warmUp = 0; warmUp < 3; warmUp++) {
                    assertThat(http.get("/actuator/health").statusCode()).isEqualTo(200);
                }

                freeze(postgres);
                try {
                    for (String request : new String[] { "the first request", "the second request" }) {
                        HttpResponse<String> response = answerWithinTheLimit(http, "/actuator/health", request);

                        assertThat(response.statusCode()).as(request).isEqualTo(503);
                        DocumentContext body = JsonPath.parse(response.body());
                        assertThat(body.<String>read("$.status")).as(request).isEqualTo("DOWN");
                        assertThat(body.<String>read("$.components.db.status")).as(request).isEqualTo("DOWN");
                    }

                    // The service itself is still answering: only what needs the database is down.
                    assertThat(answerWithinTheLimit(http, "/api/v1/meta", "meta").statusCode()).isEqualTo(200);
                }
                finally {
                    thaw(postgres);
                }

                // Nobody restarts anything: the pool replaces the connections it gave up on.
                await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted(
                        () -> assertThat(http.get("/actuator/health").statusCode()).isEqualTo(200));
                DocumentContext body = JsonPath.parse(http.get("/actuator/health").body());
                assertThat(body.<String>read("$.status")).isEqualTo("UP");
                assertThat(body.<String>read("$.components.db.status")).isEqualTo("UP");
            }
        }
    }

    /**
     * What every later endpoint that reads the database depends on: a request that already holds a
     * connection when the database goes silent gets an error, and its connection back, within the
     * driver's socket timeout. Started with a short one so that the test does not take as long as
     * the product's own, which {@link DatabaseTimeoutsIntegrationTest} checks is set.
     */
    @Test
    void aQueryOnAConnectionAlreadyInUseFailsWithinTheSocketTimeoutInsteadOfWaitingForEver() {
        try (PostgreSQLContainer postgres = TestPostgres.newContainer()) {
            postgres.start();
            try (StartedApplication application = StartedApplication.against(postgres,
                    "--spring.datasource.hikari.data-source-properties.socketTimeout=2")) {
                try {
                    CompletableFuture<Throwable> outcome = CompletableFuture.supplyAsync(
                            () -> application.jdbc().execute((ConnectionCallback<Throwable>) connection -> {
                                try (Statement statement = connection.createStatement()) {
                                    statement.execute("SELECT 1");
                                    freeze(postgres);
                                    statement.execute("SELECT 1");
                                    return null;
                                }
                                catch (SQLException ex) {
                                    return ex;
                                }
                            }));

                    assertThat(outcome).as("the query made while the database was silent")
                            .succeedsWithin(LIMIT)
                            .isInstanceOf(SQLException.class);
                }
                finally {
                    thaw(postgres);
                }
            }
        }
    }

    private static HttpResponse<String> answerWithinTheLimit(Http http, String path, String request) {
        CompletableFuture<HttpResponse<String>> response = CompletableFuture.supplyAsync(() -> http.get(path));
        assertThat(response).as("%s to %s while the database does not answer", request, path).succeedsWithin(LIMIT);
        return response.join();
    }

    /** Stops every process of the database where it stands. Its connections stay open and silent. */
    private static void freeze(PostgreSQLContainer postgres) {
        postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
    }

    private static void thaw(PostgreSQLContainer postgres) {
        Boolean paused = postgres.getDockerClient().inspectContainerCmd(postgres.getContainerId()).exec()
                .getState().getPaused();
        if (Boolean.TRUE.equals(paused)) {
            postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
        }
    }
}
