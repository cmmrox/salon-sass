package com.salonplatform.branding;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.jayway.jsonpath.JsonPath;
import com.salonplatform.testing.Http;
import com.salonplatform.testing.Http.Exchange;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import com.salonplatform.testing.TestSalons;
import com.salonplatform.testing.TestSalons.TestSalon;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * CR-003: a database that is stopped, or that accepts connections and answers nothing, costs a
 * salon's request seconds and an honest 503, never a hang; and the endpoint recovers by itself.
 */
class SalonEndpointDatabaseSilentIntegrationTest {

    private static final String SALON = "/api/v1/salon";

    private static final Duration LIMIT = Duration.ofSeconds(5);

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
                        for (String request : new String[] { "the first request", "the second request" }) {
                            Exchange response = within(LIMIT, () -> http.getOnHost(host, SALON), request);

                            assertThat(response.status()).as(request).isEqualTo(503);
                            assertThat(response.contentType()).startsWith("application/problem+json");
                            assertThat(JsonPath.parse(response.body()).<String>read("$.code")).isEqualTo("service_unavailable");
                            assertThat(response.body()).doesNotContainIgnoringCase("exception").doesNotContainIgnoringCase("sql")
                                    .doesNotContainIgnoringCase("hikari").doesNotContainIgnoringCase("postgres");
                        }
                        // A salon's hostname does not make the platform's own endpoints wait on the database.
                        assertThat(within(LIMIT, () -> http.getOnHost(host, "/api/v1/meta"), "meta").status()).isEqualTo(200);
                    }
                    finally {
                        thaw(postgres);
                    }
                    long thawed = System.nanoTime();
                    await().atMost(LIMIT).pollInterval(Duration.ofMillis(100))
                            .untilAsserted(() -> assertThat(http.getOnHost(host, SALON).status()).isEqualTo(200));
                    assertThat(Duration.ofNanos(System.nanoTime() - thawed)).as("time to recover").isLessThan(LIMIT);

                    keepGoing.set(false);
                    clients.shutdown();
                    assertThat(clients.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
                }

                assertThat(timed).as("every request, in flight at the freeze or made during it")
                        .allSatisfy(request -> {
                            assertThat(request.status()).isIn(200, 503);
                            assertThat(request.took()).isLessThan(LIMIT);
                        });
                assertThat(timed).extracting(Timed::status).as("some requests met the silent database").contains(503);
                assertThat(timed).extracting(Timed::status).as("and some were served before or after it").contains(200);
                for (int request = 0; request < 10; request++) {
                    assertThat(http.getOnHost(host, SALON).status()).as("after the database answered again").isEqualTo(200);
                }
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

                for (String request : new String[] { "the first request", "the second request" }) {
                    Exchange response = within(LIMIT, () -> http.getOnHost(host, SALON), request);

                    assertThat(response.status()).as(request).isEqualTo(503);
                    assertThat(JsonPath.parse(response.body()).<String>read("$.code")).isEqualTo("service_unavailable");
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
        Boolean paused = postgres.getDockerClient().inspectContainerCmd(postgres.getContainerId()).exec()
                .getState().getPaused();
        if (Boolean.TRUE.equals(paused)) {
            postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
        }
    }

    private record Timed(int status, Duration took) {
    }
}
