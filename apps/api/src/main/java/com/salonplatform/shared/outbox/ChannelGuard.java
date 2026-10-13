package com.salonplatform.shared.outbox;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * What stands between the dispatcher and one channel's provider: a limit on how many calls run at
 * once, and the channel's circuit breaker. A message is claimed only once a permit is held, so a
 * message that cannot be attempted yet is left as it is, attempts untouched.
 */
final class ChannelGuard {

    private final Semaphore calls;

    private final CircuitBreaker breaker;

    ChannelGuard(int concurrency, int failureThreshold, Duration breakerWait, Clock clock) {
        this.calls = new Semaphore(concurrency);
        this.breaker = new CircuitBreaker(failureThreshold, breakerWait, clock);
    }

    /** A permit for one call, when one is free and the breaker lets it through. */
    Optional<Permit> tryAcquire() {
        if (!calls.tryAcquire()) {
            return Optional.empty();
        }
        if (!breaker.tryAcquire()) {
            calls.release();
            return Optional.empty();
        }
        return Optional.of(new Permit());
    }

    CircuitBreaker.State breakerState() {
        return breaker.state();
    }

    /** One call's place: released exactly once, with what happened. */
    final class Permit {

        private boolean released;

        void succeeded() {
            release(breaker::onSuccess);
        }

        void failed() {
            release(breaker::onFailure);
        }

        void abandoned() {
            release(breaker::onAbandoned);
        }

        private synchronized void release(Runnable outcome) {
            if (released) {
                return;
            }
            released = true;
            outcome.run();
            calls.release();
        }
    }
}
