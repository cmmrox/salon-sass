package com.salonplatform.shared.outbox;

import java.time.Duration;

/**
 * The wait before a message is attempted again: the initial wait after the first failure, each
 * next wait longer by the multiplier, never longer than the maximum. With the defaults: 30 s, 1 min,
 * 2 min, 4 min, ... up to 1 h.
 */
final class Backoff {

    private final Duration initial;

    private final double multiplier;

    private final Duration max;

    Backoff(OutboxSettings.Backoff settings) {
        this.initial = settings.initial();
        this.multiplier = settings.multiplier();
        this.max = settings.max();
    }

    /** The wait after this many failed attempts, from 1. */
    Duration after(int failedAttempts) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("a wait follows a failed attempt");
        }
        double millis = initial.toMillis() * Math.pow(multiplier, failedAttempts - 1);
        if (Double.isInfinite(millis) || millis >= max.toMillis()) {
            return max;
        }
        return Duration.ofMillis(Math.round(millis));
    }
}
