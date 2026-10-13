package com.salonplatform.shared.local;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.DocumentContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static com.salonplatform.shared.local.ProbeClient.ALPHA;
import static com.salonplatform.shared.local.ProbeClient.status;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-11, the database's half: the database stops answering while a provider call is in progress.
 * The call's outcome cannot be recorded, so the message stays claimed; once the database answers
 * again and the lease has run out on the test clock, the message is attempted again with the same
 * idempotency key and ends sent, with no repair by hand. The dispatcher goes on by itself.
 */
class DeliveryDuringDatabaseOutageIntegrationTest {

    @Test
    void aDeliveryWhoseOutcomeCouldNotBeRecordedIsAttemptedAgainAfterItsLease() {
        try (PostgreSQLContainer postgres = TestPostgres.newContainer()) {
            postgres.start();
            try (StartedApplication application = StartedApplication.against(postgres, ProbeClient.localWithDispatcher(
                    "--salon.outbox.lease=20s", "--salon.outbox.provider.time-limit=10s"))) {
                ProbeClient probes = new ProbeClient(application);
                String id = probes.probe(ALPHA, "{\"delayMs\":2500}");
                probes.awaitProbe(ALPHA, id, Duration.ofSeconds(5), status("sending"));

                pause(postgres);
                try {
                    // The provider answers while the database is silent; the outcome cannot be recorded.
                    ProbeClient.sleep(8_000);
                    assertThat(application.bean(ProbeProvider.class).callsFor(UUID.fromString(id))).isEqualTo(1);
                }
                finally {
                    unpause(postgres);
                }

                Map<String, Object> left = TestDatabase.of(postgres).asOwner().queryForMap(
                        "SELECT status, attempts FROM outbox_message WHERE id = ?::uuid", id);
                assertThat(left).as("still claimed: its outcome was never recorded").containsEntry("status", "sending")
                        .containsEntry("attempts", 1);

                probes.setClock(Instant.now().plusSeconds(21));
                DocumentContext sent = probes.awaitProbe(ALPHA, id, Duration.ofSeconds(15), status("sent"));

                assertThat(sent.<Integer>read("$.attempts")).isEqualTo(2);
                assertThat(sent.<Integer>read("$.providerCalls")).as("called twice, under the one key: its message id")
                        .isEqualTo(2);
                assertThat(application.http().get("/actuator/health").statusCode()).isEqualTo(200);
            }
        }
    }

    private static void pause(PostgreSQLContainer postgres) {
        postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
    }

    private static void unpause(PostgreSQLContainer postgres) {
        Boolean paused = postgres.getDockerClient().inspectContainerCmd(postgres.getContainerId()).exec()
                .getState().getPaused();
        if (Boolean.TRUE.equals(paused)) {
            postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
        }
    }
}
