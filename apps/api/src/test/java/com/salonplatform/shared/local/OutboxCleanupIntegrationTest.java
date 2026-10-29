package com.salonplatform.shared.local;

import static com.salonplatform.shared.local.ProbeClient.ALPHA;
import static com.salonplatform.shared.local.ProbeClient.BETA;
import static com.salonplatform.shared.local.ProbeClient.status;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.jayway.jsonpath.DocumentContext;
import com.salonplatform.testing.CapturedLogs;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * AC-15: the first recurring job, through the real scheduler and the test clock. Once a delivered
 * message's age passes the retention (30 days), the cleanup job's next run removes it, salon by
 * salon: newer messages, and messages that were never delivered, stay; each salon's run is logged
 * with that salon's id.
 */
class OutboxCleanupIntegrationTest {

    private static final Map<String, String> SALON_IDS = Map.of(
            "alpha", "a1a1a1a1-0000-4000-8000-000000000001",
            "beta", "b2b2b2b2-0000-4000-8000-000000000002",
            "gamma", "c3c3c3c3-0000-4000-8000-000000000003");

    @Test
    void deliveredMessagesOlderThanTheRetentionAreRemovedSalonBySalonAndNothingElseIs() {
        TestDatabase database = TestDatabase.createEmpty(TestPostgres.shared());
        // The capture comes last: starting an application sets logging up afresh and would drop it.
        try (StartedApplication application = database.start(ProbeClient.localWithDispatcher(
                        "--salon.jobs.enabled=true",
                        "--salon.jobs.poll-interval=100ms",
                        "--salon.outbox.max-attempts=1"));
                CapturedLogs logs = CapturedLogs.of("com.salonplatform.shared")) {
            ProbeClient probes = new ProbeClient(application);
            Instant start = Instant.now();
            String alphaOld = probes.probe(ALPHA, "{}");
            String betaOld = probes.probe(BETA, "{}");
            String alphaDead = probes.probe(ALPHA, "{\"failures\":1}");
            probes.awaitProbe(ALPHA, alphaOld, Duration.ofSeconds(5), status("sent"));
            probes.awaitProbe(BETA, betaOld, Duration.ofSeconds(5), status("sent"));
            probes.awaitProbe(ALPHA, alphaDead, Duration.ofSeconds(5), status("dead"));

            // A day short of the retention: the job runs (hourly, on the test clock) and removes nothing.
            probes.setClock(start.plus(Duration.ofDays(29)));
            String betaNewer = probes.probe(BETA, "{}");
            probes.awaitProbe(BETA, betaNewer, Duration.ofSeconds(5), status("sent"));
            ProbeClient.sleep(1_000);
            assertThat(probes.view(ALPHA, alphaOld).<String>read("$.status")).isEqualTo("sent");
            assertThat(probes.view(BETA, betaOld).<String>read("$.status")).isEqualTo("sent");

            // Past the retention for the first messages, not for beta's newer one.
            probes.setClock(start.plus(Duration.ofDays(30)).plus(Duration.ofHours(2)));
            awaitGone(application, probes, ALPHA, alphaOld);
            awaitGone(application, probes, BETA, betaOld);

            DocumentContext kept = probes.view(BETA, betaNewer);
            assertThat(kept.<String>read("$.status"))
                    .as("beta's newer message stays")
                    .isEqualTo("sent");
            assertThat(probes.view(ALPHA, alphaDead).<String>read("$.status"))
                    .as("a message that was never delivered stays")
                    .isEqualTo("dead");
            assertThat(database.asOwner()
                            .queryForList("SELECT id::text FROM outbox_message ORDER BY created_at", String.class))
                    .containsExactlyInAnyOrder(alphaDead, betaNewer);
            assertThat(database.asOwner().queryForObject("SELECT count(*) FROM audit_entry", Integer.class))
                    .as("the audit trail is not touched")
                    .isEqualTo(4);

            List<ILoggingEvent> removals =
                    logs.events(line -> line.getFormattedMessage().startsWith("Removed 1 delivered"));
            assertThat(removals)
                    .extracting(line -> CapturedLogs.context(line).get("tenant.id"))
                    .containsExactlyInAnyOrder(SALON_IDS.get("alpha"), SALON_IDS.get("beta"));
            assertThat(removals)
                    .allSatisfy(line -> assertThat(CapturedLogs.context(line)).containsKeys("trace.id", "span.id"));
            assertThat(logs.events(line -> line.getFormattedMessage().startsWith("Removed 0 delivered")))
                    .as("every active salon is visited on every run, each under its own id")
                    .extracting(line -> CapturedLogs.context(line).get("tenant.id"))
                    .contains(SALON_IDS.get("alpha"), SALON_IDS.get("beta"), SALON_IDS.get("gamma"));
            assertThat(logs.events(line -> line.getFormattedMessage().contains("ran for the salon")))
                    .as("the scheduler's own lines name the salon too")
                    .allSatisfy(line -> assertThat(CapturedLogs.context(line).get("tenant.id"))
                            .isIn(SALON_IDS.values()));
        }
    }

    private static void awaitGone(StartedApplication application, ProbeClient probes, String salon, String id) {
        Instant until = Instant.now().plusSeconds(5);
        while (application
                        .http()
                        .getOnHost(probes.host(salon), "/local/probes/" + id)
                        .status()
                != 404) {
            if (Instant.now().isAfter(until)) {
                throw new AssertionError("the " + salon + " message " + id + " was not removed within 5 seconds");
            }
            ProbeClient.sleep(50);
        }
    }
}
