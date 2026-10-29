package com.salonplatform.shared.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * What stands between the dispatcher and one channel: a limit on how many calls run at once, and, for a
 * provider's channel, its circuit breaker. A message is claimed only once a permit is held, so a message
 * that cannot be attempted yet is left as it is, attempts untouched.
 */
final class ChannelGuard {

    private final Semaphore calls;

    private final Optional<CircuitBreaker> breaker;

    /** A provider's channel: its limit on calls at once and its circuit breaker. */
    ChannelGuard(int concurrency, int failureThreshold, Duration breakerWait, Clock clock) {
        this(concurrency, Optional.of(new CircuitBreaker(failureThreshold, breakerWait, clock)));
    }

    private ChannelGuard(int concurrency, Optional<CircuitBreaker> breaker) {
        this.calls = new Semaphore(concurrency);
        this.breaker = breaker;
    }

    /**
     * The events' channel: a limit on subscriber deliveries at once, which keeps them from taking every
     * database connection, and no breaker, because subscribers run in-process with no provider to protect.
     */
    static ChannelGuard withoutBreaker(int concurrency) {
        return new ChannelGuard(concurrency, Optional.empty());
    }

    /** A permit for one call, when one is free and the breaker, if any, lets it through. */
    Optional<Permit> tryAcquire() {
        if (!calls.tryAcquire()) {
            return Optional.empty();
        }
        if (!breaker.map(CircuitBreaker::tryAcquire).orElse(true)) {
            calls.release();
            return Optional.empty();
        }
        return Optional.of(new Permit());
    }

    CircuitBreaker.State breakerState() {
        return breaker.orElseThrow().state();
    }

    /** One call's place: released exactly once, with what happened. */
    final class Permit {

        private boolean released;

        void succeeded() {
            release(CircuitBreaker::onSuccess);
        }

        void failed() {
            release(CircuitBreaker::onFailure);
        }

        void abandoned() {
            release(CircuitBreaker::onAbandoned);
        }

        private synchronized void release(Consumer<CircuitBreaker> outcome) {
            if (released) {
                return;
            }
            released = true;
            breaker.ifPresent(outcome);
            calls.release();
        }
    }
}
