package com.salonplatform.shared.local;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.jayway.jsonpath.DocumentContext;
import com.salonplatform.testing.Http.Exchange;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestDatabase;
import com.salonplatform.testing.TestPostgres;
import org.junit.jupiter.api.Test;

import static com.salonplatform.shared.local.ProbeClient.ALPHA;
import static com.salonplatform.shared.local.ProbeClient.BETA;
import static com.salonplatform.shared.local.ProbeClient.GAMMA;
import static com.salonplatform.shared.local.ProbeClient.attempts;
import static com.salonplatform.shared.local.ProbeClient.status;
import static org.assertj.core.api.Assertions.assertThat;

/** AC-9 and AC-10: a slow or failing provider degrades its channel, not the API. */
class ProviderLimitsIntegrationTest {

    @Test
    void aCallLongerThanTheTimeLimitFailsWhenTheLimitRunsOutAndTheApiKeepsAnswering() {
        try (StartedApplication application = TestDatabase.createEmpty(TestPostgres.shared()).start(
                ProbeClient.localWithDispatcher("--salon.outbox.provider.time-limit=1s", "--salon.outbox.lease=5s",
                        "--salon.outbox.breaker.failure-threshold=1000"))) {
            ProbeClient probes = new ProbeClient(application);
            List<String> slow = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                slow.add(probes.probe(ALPHA, "{\"delayMs\":5000}"));
            }

            // The first calls started at once; each fails when its second runs out, well before five.
            DocumentContext first = probes.awaitProbe(ALPHA, slow.getFirst(), Duration.ofMillis(3_500), attempts(1));
            assertThat(first.<String>read("$.status")).isEqualTo("pending");
            assertThat(first.<String>read("$.lastError")).isEqualTo("The provider did not answer within 1000 ms");

            for (String salon : new String[] { ALPHA, BETA, GAMMA }) {
                Instant asked = Instant.now();
                Exchange response = application.http().getOnHost(probes.host(salon), "/api/v1/salon");
                assertThat(response.status()).isEqualTo(200);
                assertThat(Duration.between(asked, Instant.now())).as(salon).isLessThan(Duration.ofSeconds(1));
            }
            assertThat(application.http().get("/actuator/health").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void anOpenBreakerHoldsTheChannelsMessagesWithoutAttemptsUntilItsWaitHasPassedOnTheClock() {
        try (StartedApplication application = TestDatabase.createEmpty(TestPostgres.shared()).start(
                ProbeClient.localWithDispatcher("--salon.outbox.breaker.failure-threshold=3",
                        "--salon.outbox.breaker.wait-duration=60s", "--salon.outbox.provider.concurrency=1"))) {
            ProbeClient probes = new ProbeClient(application);
            Instant start = Instant.now().plus(Duration.ofDays(1));
            probes.setClock(start);
            for (int i = 0; i < 3; i++) {
                String failing = probes.probe(BETA, "{\"failures\":50}");
                probes.awaitProbe(BETA, failing, Duration.ofSeconds(5), attempts(1));
            }

            String waiting = probes.probe(BETA, "{\"failures\":0}");
            ProbeClient.sleep(1_500);
            DocumentContext held = probes.view(BETA, waiting);
            assertThat(held.<String>read("$.status")).isEqualTo("pending");
            assertThat(held.<Integer>read("$.attempts")).isZero();
            assertThat(held.<Integer>read("$.providerCalls")).isZero();
            assertThat(application.http().getOnHost(probes.host(BETA), "/api/v1/salon").status()).isEqualTo(200);
            assertThat(application.http().get("/actuator/health").statusCode()).isEqualTo(200);

            // The breaker opened a moment after `start` on the test clock and waits 60 s from then.
            probes.setClock(start.plusSeconds(45));
            ProbeClient.sleep(1_500);
            DocumentContext stillHeld = probes.view(BETA, waiting);
            assertThat(stillHeld.<String>read("$.status")).as("before the breaker's wait has passed").isEqualTo("pending");
            assertThat(stillHeld.<Integer>read("$.attempts")).isZero();
            assertThat(stillHeld.<Integer>read("$.providerCalls")).isZero();

            probes.setClock(start.plusSeconds(120));
            DocumentContext sent = probes.awaitProbe(BETA, waiting, Duration.ofSeconds(5), status("sent"));
            assertThat(sent.<Integer>read("$.attempts")).isEqualTo(1);
            assertThat(sent.<Integer>read("$.providerCalls")).isEqualTo(1);
        }
    }
}
