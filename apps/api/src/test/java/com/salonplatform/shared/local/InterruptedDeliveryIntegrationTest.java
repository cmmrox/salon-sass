package com.salonplatform.shared.local;

import static com.salonplatform.shared.local.ProbeClient.ALPHA;
import static com.salonplatform.shared.local.ProbeClient.status;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * AC-11: a delivery in progress when the API stops is not lost. The next API attempts it again once
 * its lease has run out on the test clock, with the same idempotency key, and it is sent.
 */
class InterruptedDeliveryIntegrationTest {

    @Test
    void aDeliveryInterruptedByAStopIsAttemptedAgainAfterItsLeaseWithTheSameKey() {
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());
        String[] settings = ProbeClient.localWithDispatcher(
                "--salon.outbox.provider.time-limit=10s",
                "--salon.outbox.lease=30s",
                "--salon.outbox.shutdown-grace=0s");
        String id;
        try (StartedApplication first = database.start(settings)) {
            ProbeClient probes = new ProbeClient(first);
            id = probes.probe(ALPHA, "{\"delayMs\":3000}");
            probes.awaitProbe(ALPHA, id, Duration.ofSeconds(5), probe -> "sending".equals(probe.read("$.status")));
        }

        Map<String, Object> left = database.asOwner()
                .queryForMap("SELECT status, attempts, lease_until FROM outbox_message WHERE id = ?::uuid", id);
        assertThat(left).containsEntry("status", "sending").containsEntry("attempts", 1);

        try (StartedApplication second = database.start(settings)) {
            ProbeClient probes = new ProbeClient(second);
            ProbeClient.sleep(1_000);
            assertThat(probes.view(ALPHA, id).<String>read("$.status"))
                    .as("held until its lease runs out")
                    .isEqualTo("sending");

            probes.setClock(Instant.now().plus(Duration.ofSeconds(31)));
            DocumentContext sent = probes.awaitProbe(ALPHA, id, Duration.ofSeconds(10), status("sent"));

            assertThat(sent.<Integer>read("$.attempts")).isEqualTo(2);
            assertThat(second.bean(ProbeProvider.class).callsFor(UUID.fromString(id)))
                    .as("called again with the message id as its key")
                    .isEqualTo(1);
        }
    }
}
