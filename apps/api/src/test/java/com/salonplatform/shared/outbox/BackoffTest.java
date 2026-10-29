package com.salonplatform.shared.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BackoffTest {

    private final Backoff backoff =
            new Backoff(new OutboxSettings.Backoff(Duration.ofSeconds(30), 2, Duration.ofHours(1)));

    /** The defaults: from 30 seconds, doubling, up to an hour. */
    @ParameterizedTest
    @CsvSource({
        "1, PT30S",
        "2, PT1M",
        "3, PT2M",
        "4, PT4M",
        "5, PT8M",
        "6, PT16M",
        "7, PT32M",
        "8, PT1H",
        "9, PT1H",
        "1000, PT1H"
    })
    void startsAtTheInitialWaitDoublesAndStopsAtTheMaximum(int failures, String wait) {
        assertThat(backoff.after(failures)).isEqualTo(Duration.parse(wait));
    }

    @Test
    void aWaitOnlyFollowsAFailure() {
        assertThatIllegalArgumentException().isThrownBy(() -> backoff.after(0));
    }

    @Test
    void aMultiplierOfOneWaitsTheSameEveryTime() {
        Backoff flat = new Backoff(new OutboxSettings.Backoff(Duration.ofSeconds(5), 1, Duration.ofMinutes(1)));
        assertThat(flat.after(1)).isEqualTo(flat.after(7)).isEqualTo(Duration.ofSeconds(5));
    }
}
