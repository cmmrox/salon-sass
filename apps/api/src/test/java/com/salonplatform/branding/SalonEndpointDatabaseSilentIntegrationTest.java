package com.salonplatform.branding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.Http.Exchange;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * CR-003: a database that is stopped, or that accepts connections and answers nothing, costs a
 * salon's request seconds and an honest 503, never a hang; and the endpoint recovers by itself.
 */
class SalonEndpointDatabaseSilentIntegrationTest {

    private static final String SALON = "/api/v1/salon";

    private static final Duration LIMIT = Duration.ofSeconds(5);

    /** A silence that ends while the pool, left to itself, is in its longest wait between attempts. */
    private static final Duration LONG_SILENCE = Duration.ofSeconds(30);

    /**
     * How soon after a long silence the endpoint must answer 200 again: well inside the five seconds
     * AC-10 allows, which are counted from the request to thaw the database and so include the time
     * the thaw itself takes.
     */
    private static final Duration RECOVERY = Duration.ofSeconds(2);

    @Test
    void requestsInFlightAndRequestsMadeWhileTheDatabaseIsSilentGet503QuicklyAndTheEndpointRecovers() throws Exception {
        try (PostgreSQLContainer postgres = TestPostgres.newContainer()) {
            postgres.start();
            try (StartedApplication application = StartedApplication.against(postgres)) {
                TestSalon alpha = new TestSalons(TestDatabase.of(postgres).asOwner()).create("Alpha Studio", "#D6086A");
                Http http = application.http();
                String host = alpha.host(application.port());
                assertThat(http.getOnHost(host, SALON).status()).isEqualTo(200);

                // Requests keep arriving from several clients, so that some are part-way through
                // their database work at the moment it goes silent.
                List<Timed> timed = new CopyOnWriteArrayList<>();
                AtomicBoolean keepGoing = new AtomicBoolean(true);
                try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int client = 0; client < 12; client++) {
                        clients.submit(() -> {
                            while (keepGoing.get()) {
                                long started = System.nanoTime();
                                int status = http.getOnHost(host, SALON).status();
                                timed.add(new Timed(status, Duration.ofNanos(System.nanoTime() - started)));
                            }
                        });
                    }
                    Thread.sleep(500);
                    freeze(postgres);
                    try {
                        Thread.sleep(1_000);
                        for (String request : new String[] {"the first request", "the second request"}) {
                            Exchange response = within(LIMIT, () -> http.getOnHost(host, SALON), request);

                            assertThat(response.status()).as(request).isEqualTo(503);
                            assertThat(response.contentType()).startsWith("application/problem+json");
                            assertThat(JsonPath.parse(response.body()).<String>read("$.code"))
                                    .isEqualTo("service_unavailable");
                            assertThat(response.body())
                                    .doesNotContainIgnoringCase("exception")
                                    .doesNotContainIgnoringCase("sql")
                                    .doesNotContainIgnoringCase("hikari")
                                    .doesNotContainIgnoringCase("postgres");
                        }
                        // A salon's hostname does not make the platform's own endpoints wait on the database.
                        assertThat(within(LIMIT, () -> http.getOnHost(host, "/api/v1/meta"), "meta")
                                        .status())
                                .isEqualTo(200);
                    } finally {
                        thaw(postgres);
                    }
                    long thawed = System.nanoTime();
                    await().atMost(LIMIT)
                            .pollInterval(Duration.ofMillis(100))
                            .untilAsserted(
                                    () -> assertThat(http.getOnHost(host, SALON).status())
                                            .isEqualTo(200));
                    assertThat(Duration.ofNanos(System.nanoTime() - thawed))
                            .as("time to recover")
                            .isLessThan(LIMIT);

                    keepGoing.set(false);
                    clients.shutdown();
                    assertThat(clients.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
                }

                assertThat(timed)
                        .as("every request, in flight at the freeze or made during it")
                        .allSatisfy(request -> {
                            assertThat(request.status()).isIn(200, 503);
                            assertThat(request.took()).isLessThan(LIMIT);
                        });
                assertThat(timed)
                        .extracting(Timed::status)
                        .as("some requests met the silent database")
                        .contains(503);
                assertThat(timed)
                        .extracting(Timed::status)
                        .as("and some were served before or after it")
                        .contains(200);
                for (int request = 0; request < 10; request++) {
                    assertThat(http.getOnHost(host, SALON).status())
                            .as("after the database answered again")
                            .isEqualTo(200);
                }
            }
        }
    }

    /**
     * How long the endpoint goes on answering 503 once the database answers again must not depend on
     * how long the database was silent. Left to itself the pool waits longer and longer between its
     * attempts to open a connection, up to five seconds, and notices that the database is back only
     * when the wait it is in ends.
     *
     * <p>The silence here is timed to end inside such a wait. Requests keep every pooled connection
     * busy, so all of them are given up together when the request's limit passes, three seconds into
     * the silence; each attempt to replace them then takes the two seconds allowed for signing in.
     * The pool's tenth attempt fails about 28 seconds in, and it would then sleep for five.
     */
    @Test
    void afterALongSilenceTheEndpointAnswersAgainAsSoonAsTheDatabaseDoes() throws Exception {
        try (PostgreSQLContainer postgres = TestPostgres.newContainer()) {
            postgres.start();
            try (StartedApplication application = StartedApplication.against(postgres)) {
                TestSalon alpha = new TestSalons(TestDatabase.of(postgres).asOwner()).create("Alpha Studio", "#D6086A");
                Http http = application.http();
                String host = alpha.host(application.port());
                assertThat(http.getOnHost(host, SALON).status()).isEqualTo(200);

                List<Answered> answered = new CopyOnWriteArrayList<>();
                AtomicBoolean keepGoing = new AtomicBoolean(true);
                long thawAsked;
                try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int client = 0; client < 12; client++) {
                        clients.submit(() -> {
                            while (keepGoing.get()) {
                                int status = http.getOnHost(host, SALON).status();
                                answered.add(new Answered(status, System.nanoTime()));
                            }
                        });
                    }
                    Thread.sleep(500);
                    freeze(postgres);
                    try {
                        Thread.sleep(LONG_SILENCE.toMillis());
                    } finally {
                        thawAsked = System.nanoTime();
                        thaw(postgres);
                    }
                    // Longer than the pool's longest wait, so that the answer is found either way.
                    Thread.sleep(LIMIT.plusSeconds(2).toMillis());
                    keepGoing.set(false);
                    clients.shutdown();
                    assertThat(clients.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
                }

                long asked = thawAsked;
                assertThat(answered).as("while the database was silent").anySatisfy(answer -> {
                    assertThat(answer.status()).isEqualTo(503);
                    assertThat(answer.at()).isLessThan(asked);
                });
                Duration recovery = answered.stream()
                        .filter(answer -> answer.status() == 200 && answer.at() > asked)
                        .map(answer -> Duration.ofNanos(answer.at() - asked))
                        .min(Duration::compareTo)
                        .orElseThrow(
                                () -> new AssertionError("no request was answered 200 after the database was thawed"));
                assertThat(recovery)
                        .as(
                                "from asking for the database to be thawed, after %s of silence, to the first 200",
                                LONG_SILENCE)
                        .isLessThan(RECOVERY);
            }
        }
    }

    @Test
    void withTheDatabaseStoppedTheEndpointAnswers503Quickly() {
        try (PostgreSQLContainer postgres = TestPostgres.newContainer()) {
            postgres.start();
            try (StartedApplication application = StartedApplication.against(postgres)) {
                TestSalon alpha = new TestSalons(TestDatabase.of(postgres).asOwner()).create("Alpha Studio", "#D6086A");
                Http http = application.http();
                String host = alpha.host(application.port());
                assertThat(http.getOnHost(host, SALON).status()).isEqualTo(200);

                postgres.stop();

                for (String request : new String[] {"the first request", "the second request"}) {
                    Exchange response = within(LIMIT, () -> http.getOnHost(host, SALON), request);

                    assertThat(response.status()).as(request).isEqualTo(503);
                    assertThat(JsonPath.parse(response.body()).<String>read("$.code"))
                            .isEqualTo("service_unavailable");
                    assertThat(JsonPath.parse(response.body()).<String>read("$.traceId"))
                            .as("the trace id of a 503")
                            .matches("^[0-9a-f]{32}$");
                }
                assertThat(http.getOnHost(host, "/api/v1/meta").status()).isEqualTo(200);
            }
        }
    }

    private static Exchange within(Duration limit, java.util.function.Supplier<Exchange> request, String name) {
        CompletableFuture<Exchange> response = CompletableFuture.supplyAsync(request);
        assertThat(response).as("%s while the database does not answer", name).succeedsWithin(limit);
        return response.join();
    }

    private static void freeze(PostgreSQLContainer postgres) {
        postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
    }

    private static void thaw(PostgreSQLContainer postgres) {
        Boolean paused = postgres.getDockerClient()
                .inspectContainerCmd(postgres.getContainerId())
                .exec()
                .getState()
                .getPaused();
        if (Boolean.TRUE.equals(paused)) {
            postgres.getDockerClient()
                    .unpauseContainerCmd(postgres.getContainerId())
                    .exec();
        }
    }

    private record Timed(int status, Duration took) {}

    /** A request's status and when its answer arrived, on the clock of {@link System#nanoTime()}. */
    private record Answered(int status, long at) {}
}
