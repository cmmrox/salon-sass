package com.salonplatform.shared.outbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The circuit breaker of one channel, on the application's clock.
 *
 * <ul>
 * <li><b>Closed:</b> every call may go ahead. After {@code failureThreshold} failures in a row it
 * opens.</li>
 * <li><b>Open:</b> no call goes ahead, so the channel's messages wait without being attempted and
 * without using up their attempts. Once {@code wait} has passed it lets one trial call through.</li>
 * <li><b>Half open:</b> the trial call is in flight and nothing else goes ahead. Its success closes
 * the breaker; its failure opens it again for another {@code wait}.</li>
 * </ul>
 *
 * <p>Whoever is let through reports the outcome with {@link #onSuccess()} or {@link #onFailure()},
 * or {@link #onAbandoned()} when no call was made after all.
 */
final class CircuitBreaker {

    enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final int failureThreshold;

    private final Duration wait;

    private final Clock clock;

    private final ReentrantLock lock = new ReentrantLock();

    private State state = State.CLOSED;

    private int consecutiveFailures;

    private Instant openedAt;

    private boolean trialInFlight;

    CircuitBreaker(int failureThreshold, Duration wait, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.wait = wait;
        this.clock = clock;
    }

    /** Whether a call may go ahead now. */
    boolean tryAcquire() {
        lock.lock();
        try {
            return switch (state) {
                case CLOSED -> true;
                case OPEN -> {
                    if (clock.instant().isBefore(openedAt.plus(wait))) {
                        yield false;
                    }
                    state = State.HALF_OPEN;
                    trialInFlight = true;
                    yield true;
                }
                case HALF_OPEN -> {
                    if (trialInFlight) {
                        yield false;
                    }
                    trialInFlight = true;
                    yield true;
                }
            };
        }
        finally {
            lock.unlock();
        }
    }

    void onSuccess() {
        lock.lock();
        try {
            state = State.CLOSED;
            consecutiveFailures = 0;
            trialInFlight = false;
        }
        finally {
            lock.unlock();
        }
    }

    void onFailure() {
        lock.lock();
        try {
            if (state == State.HALF_OPEN) {
                open();
                return;
            }
            consecutiveFailures++;
            if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
                open();
            }
        }
        finally {
            lock.unlock();
        }
    }

    /** A call that was let through was not made after all: as if it had not been asked for. */
    void onAbandoned() {
        lock.lock();
        try {
            if (state == State.HALF_OPEN) {
                trialInFlight = false;
            }
        }
        finally {
            lock.unlock();
        }
    }

    State state() {
        lock.lock();
        try {
            return state;
        }
        finally {
            lock.unlock();
        }
    }

    private void open() {
        state = State.OPEN;
        openedAt = clock.instant();
        trialInFlight = false;
    }
}
