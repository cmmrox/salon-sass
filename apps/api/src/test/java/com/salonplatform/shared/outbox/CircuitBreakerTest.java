package com.salonplatform.shared.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    private final MovableClock clock = new MovableClock(Instant.parse("2026-10-12T08:00:00Z"));

    private final CircuitBreaker breaker = new CircuitBreaker(5, Duration.ofSeconds(60), clock);

    @Test
    void staysClosedUntilTheThresholdOfFailuresInARow() {
        for (int i = 0; i < 4; i++) {
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.onFailure();
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.tryAcquire()).isFalse();
    }

    @Test
    void aSuccessStartsTheCountAgain() {
        for (int i = 0; i < 4; i++) {
            breaker.onFailure();
        }
        breaker.onSuccess();
        for (int i = 0; i < 4; i++) {
            breaker.onFailure();
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void anOpenBreakerLetsOneTrialThroughOnceItsWaitHasPassedOnTheClock() {
        open();

        clock.advance(Duration.ofSeconds(59));
        assertThat(breaker.tryAcquire()).as("before the wait").isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.tryAcquire()).as("the trial").isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(breaker.tryAcquire()).as("while the trial is in flight").isFalse();
    }

    @Test
    void aTrialThatSucceedsClosesTheBreaker() {
        open();
        clock.advance(Duration.ofSeconds(60));
        assertThat(breaker.tryAcquire()).isTrue();

        breaker.onSuccess();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.tryAcquire()).isTrue();
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void aTrialThatFailsOpensTheBreakerForAnotherWait() {
        open();
        clock.advance(Duration.ofSeconds(60));
        assertThat(breaker.tryAcquire()).isTrue();

        breaker.onFailure();

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        clock.advance(Duration.ofSeconds(59));
        assertThat(breaker.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void aTrialThatWasNotMadeLetsTheNextOneThrough() {
        open();
        clock.advance(Duration.ofSeconds(60));
        assertThat(breaker.tryAcquire()).isTrue();

        breaker.onAbandoned();

        assertThat(breaker.tryAcquire()).isTrue();
    }

    /** The local test clock can be returned to real time: the channel is not held until that time comes round again. */
    @Test
    void aClockSetBackToBeforeTheBreakerOpenedEndsTheWait() {
        clock.advance(Duration.ofDays(30));
        open();
        assertThat(breaker.tryAcquire()).isFalse();

        clock.advance(Duration.ofDays(-30));

        assertThat(breaker.tryAcquire()).as("the trial").isTrue();
        breaker.onFailure();
        assertThat(breaker.tryAcquire()).as("open again, for a wait from now").isFalse();
        clock.advance(Duration.ofSeconds(60));
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    void theGuardLimitsCallsAtOnceAndGivesThePlaceBackOnce() {
        ChannelGuard guard = new ChannelGuard(2, 5, Duration.ofSeconds(60), clock);
        ChannelGuard.Permit first = guard.tryAcquire().orElseThrow();
        ChannelGuard.Permit second = guard.tryAcquire().orElseThrow();

        assertThat(guard.tryAcquire()).as("a third at once").isEmpty();
        first.succeeded();
        first.failed();
        assertThat(guard.tryAcquire()).as("one place back, not two").isPresent();
        assertThat(guard.tryAcquire()).isEmpty();
        second.abandoned();
    }

    @Test
    void theGuardAsksTheBreakerAndKeepsNoPlaceWhenItSaysNo() {
        ChannelGuard guard = new ChannelGuard(1, 1, Duration.ofSeconds(60), clock);
        guard.tryAcquire().orElseThrow().failed();

        assertThat(guard.breakerState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(guard.tryAcquire()).isEmpty();
        clock.advance(Duration.ofSeconds(60));
        assertThat(guard.tryAcquire()).as("the place was not kept by the refusal").isPresent();
    }

    private void open() {
        for (int i = 0; i < 5; i++) {
            breaker.onFailure();
        }
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /** A clock a test moves by hand. */
    static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
