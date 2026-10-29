package com.salonplatform.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.salonplatform.testing.Http;
import com.salonplatform.testing.StartedApplication;
import com.salonplatform.testing.TestPostgres;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * A stopping instance leaves the rotation before it stops answering (F01-S05): its readiness turns
 * OUT_OF_SERVICE at once, it keeps answering requests for the drain delay, and only then does the
 * server shut down.
 */
class ReadinessDrainIntegrationTest {

    private static final Duration DRAIN = Duration.ofSeconds(3);

    @Test
    void readinessFailsFirstAndRequestsAreAnsweredThroughoutTheDrain() throws Exception {
        StartedApplication application = StartedApplication.against(
                TestPostgres.shared(), "--salon.server.drain-delay=" + DRAIN.toMillis() + "ms");
        Http http = application.http();
        assertThat(http.get("/actuator/health/readiness").statusCode()).isEqualTo(200);

        Instant stopAsked = Instant.now();
        CompletableFuture<Void> stopping = CompletableFuture.runAsync(application::close);

        // Readiness reports the stop at once.
        Instant outOfService = null;
        while (outOfService == null
                && Duration.between(stopAsked, Instant.now()).compareTo(Duration.ofSeconds(1)) < 0) {
            if (http.get("/actuator/health/readiness").statusCode() == 503) {
                outOfService = Instant.now();
            }
        }
        assertThat(outOfService)
                .as("readiness went OUT_OF_SERVICE within a second of the stop")
                .isNotNull();

        // Every request sent during the drain is still answered.
        int answered = 0;
        while (Duration.between(stopAsked, Instant.now()).compareTo(DRAIN.minusMillis(500)) < 0) {
            assertThat(http.get("/api/v1/meta").statusCode()).isEqualTo(200);
            answered++;
        }
        assertThat(answered).isPositive();
        assertThat(stopping).as("the stop waits for the drain").isNotDone();

        stopping.get(30, TimeUnit.SECONDS);
        assertThat(Duration.between(stopAsked, Instant.now())).isGreaterThanOrEqualTo(DRAIN);
        assertThatThrownBy(() -> http.get("/api/v1/meta")).as("stopped").isInstanceOf(IllegalStateException.class);
    }

    /** The test framework pauses a context it is not using (a stop without a close) and resumes it. */
    @Test
    void anInstanceStoppedAndStartedAgainWithoutClosingIsReadyAgain() {
        try (StartedApplication application = StartedApplication.against(TestPostgres.shared())) {
            ReadinessDrain drain = application.bean(ReadinessDrain.class);

            drain.stop();
            assertThat(application.http().get("/actuator/health/readiness").statusCode())
                    .isEqualTo(503);
            drain.start();

            assertThat(application.http().get("/actuator/health/readiness").statusCode())
                    .isEqualTo(200);
        }
    }
}
