package com.salonplatform.shared.local;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.salonplatform.testing.CapturedLogs;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-12: two dispatchers in two applications against one database, 100 probes over three salons
 * sent together: each probe is sent, and its provider is called exactly once across both. Each
 * delivery's log lines carry its salon's id.
 */
class TwoDispatchersIntegrationTest {

    private static final Map<String, String> SALONS = Map.of(
            "alpha", "a1a1a1a1-0000-4000-8000-000000000001",
            "beta", "b2b2b2b2-0000-4000-8000-000000000002",
            "gamma", "c3c3c3c3-0000-4000-8000-000000000003");

    @Test
    void everyMessageIsDeliveredOnceWhenTwoDispatchersRaceForIt() throws Exception {
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());
        try (CapturedLogs logs = CapturedLogs.of("com.salonplatform.shared.outbox");
                StartedApplication one = database.start(ProbeClient.localWithDispatcher("--salon.outbox.poll-interval=20ms"));
                StartedApplication two = database.start(ProbeClient.localWithDispatcher("--salon.outbox.poll-interval=20ms"))) {
            ProbeClient probes = new ProbeClient(one);
            List<String> order = List.of("alpha", "beta", "gamma");
            Map<String, String> salonOf = new HashMap<>();
            try (ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<String[]>> created = new ArrayList<>();
                for (int i = 0; i < 100; i++) {
                    String salon = order.get(i % 3);
                    created.add(senders.submit(() -> new String[] { probes.probe(salon, "{}"), salon }));
                }
                for (Future<String[]> probe : created) {
                    salonOf.put(probe.get()[0], probe.get()[1]);
                }
            }

            Instant until = Instant.now().plusSeconds(30);
            JdbcCounts counts = new JdbcCounts(database);
            while (counts.sent() < 100 && Instant.now().isBefore(until)) {
                ProbeClient.sleep(100);
            }
            assertThat(counts.sent()).isEqualTo(100);

            ProbeProvider first = one.bean(ProbeProvider.class);
            ProbeProvider second = two.bean(ProbeProvider.class);
            int fromFirst = 0;
            for (String id : salonOf.keySet()) {
                UUID messageId = UUID.fromString(id);
                assertThat(first.callsFor(messageId) + second.callsFor(messageId)).as(id).isEqualTo(1);
                fromFirst += first.callsFor(messageId);
            }
            assertThat(fromFirst).as("both dispatchers delivered").isBetween(1, 99);

            List<ILoggingEvent> delivered = logs.events(line -> line.getFormattedMessage().startsWith("Delivered outbox message"));
            assertThat(delivered).hasSize(100);
            for (ILoggingEvent line : delivered) {
                String id = line.getFormattedMessage().split(" ")[3];
                assertThat(CapturedLogs.context(line)).as(id).containsEntry("tenant.id", SALONS.get(salonOf.get(id)))
                        .containsKey("trace.id");
            }
        }
    }

    private record JdbcCounts(TestDatabase database) {

        int sent() {
            return database.asOwner().queryForObject("SELECT count(*) FROM outbox_message WHERE status = 'sent'",
                    Integer.class);
        }
    }
}
