package com.salonplatform.shared.local;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.jayway.jsonpath.DocumentContext;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.salonplatform.shared.local.ProbeClient.ALPHA;
import static com.salonplatform.shared.local.ProbeClient.BETA;
import static com.salonplatform.shared.local.ProbeClient.GAMMA;
import static com.salonplatform.shared.local.ProbeClient.attempts;
import static com.salonplatform.shared.local.ProbeClient.status;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-6 and AC-7: a probe is delivered, retried with the backoff measured on the test clock, and
 * ends dead when it runs out of attempts. The default backoff (30 s, doubling), three attempts.
 */
class ProbeDeliveryIntegrationTest {

    private static StartedApplication application;

    private static ProbeClient probes;

    @BeforeAll
    static void start() {
        application = TestDatabase.createEmpty(TestPostgres.shared()).start(ProbeClient.localWithDispatcher(
                "--salon.outbox.max-attempts=3", "--salon.outbox.provider.time-limit=2s", "--salon.outbox.lease=5s",
                "--salon.outbox.breaker.failure-threshold=1000"));
        probes = new ProbeClient(application);
    }

    @AfterAll
    static void stop() {
        application.close();
    }

    @Test
    void aProbeThatDoesNotFailIsSentWithinSecondsWithOneAttemptAndOneCall() {
        String id = probes.probe(ALPHA, "{\"failures\":0}");

        DocumentContext sent = probes.awaitProbe(ALPHA, id, Duration.ofSeconds(5), status("sent"));

        assertThat(sent.<Integer>read("$.attempts")).isEqualTo(1);
        assertThat(sent.<Integer>read("$.providerCalls")).isEqualTo(1);
        assertThat(sent.<String>read("$.sentAt")).isNotNull();
        assertThat(sent.<String>read("$.lastError")).isNull();
    }

    @Test
    void aProbeThatFailsWaitsTheBackoffOnTheTestClockAndIsSentAfterItsThirdAttempt() {
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        probes.setClock(start);
        String id = probes.probe(BETA, "{\"failures\":2}");

        DocumentContext first = probes.awaitProbe(BETA, id, Duration.ofSeconds(5), attempts(1));
        assertThat(first.<String>read("$.status")).isEqualTo("pending");
        assertThat(first.<String>read("$.lastError")).contains("failed call 1");
        Instant firstNext = Instant.parse(first.read("$.nextAttemptAt"));
        assertThat(Duration.between(start, firstNext)).as("30 s after the first failure")
                .isBetween(Duration.ofSeconds(30), Duration.ofSeconds(32));

        // Not attempted again while the clock has not reached it.
        ProbeClient.sleep(1_000);
        assertThat(probes.view(BETA, id).<Integer>read("$.attempts")).isEqualTo(1);

        Instant secondAt = firstNext.plusSeconds(1);
        probes.setClock(secondAt);
        DocumentContext second = probes.awaitProbe(BETA, id, Duration.ofSeconds(5), attempts(2));
        Instant secondNext = Instant.parse(second.read("$.nextAttemptAt"));
        assertThat(Duration.between(secondAt, secondNext)).as("60 s after the second failure")
                .isBetween(Duration.ofSeconds(60), Duration.ofSeconds(62));

        probes.setClock(secondNext.plusSeconds(1));
        DocumentContext sent = probes.awaitProbe(BETA, id, Duration.ofSeconds(5), status("sent"));
        assertThat(sent.<Integer>read("$.attempts")).isEqualTo(3);
        assertThat(sent.<Integer>read("$.providerCalls")).isEqualTo(3);
    }

    @Test
    void aProbeThatFailsMoreThanItsAttemptsEndsDeadWithItsLastErrorAndCountsOnce() {
        MeterRegistry meters = application.bean(MeterRegistry.class);
        double deadBefore = meters.counter("salon.outbox.dead").count();
        Instant clock = Instant.now().plus(10, ChronoUnit.DAYS);
        probes.setClock(clock);
        String id = probes.probe(GAMMA, "{\"failures\":5}");

        DocumentContext probe = probes.awaitProbe(GAMMA, id, Duration.ofSeconds(5), attempts(1));
        for (int attempt = 2; attempt <= 3; attempt++) {
            clock = Instant.parse(probe.<String>read("$.nextAttemptAt")).plusSeconds(1);
            probes.setClock(clock);
            probe = probes.awaitProbe(GAMMA, id, Duration.ofSeconds(5), attempts(attempt));
        }

        assertThat(probe.<String>read("$.status")).isEqualTo("dead");
        assertThat(probe.<String>read("$.lastError")).contains("failed call 3");
        assertThat(meters.counter("salon.outbox.dead").count()).isEqualTo(deadBefore + 1);
        probes.setClock(clock.plus(7, ChronoUnit.DAYS));
        ProbeClient.sleep(1_000);
        assertThat(probes.view(GAMMA, id).<Integer>read("$.providerCalls")).as("no longer attempted").isEqualTo(3);
    }
}
