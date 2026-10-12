package com.salonplatform.shared.outbox;

import java.time.Duration;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the outbox delivers ({@code salon.outbox.*}). The defaults are the design's; tests may
 * shorten them.
 *
 * @param enabled whether this instance runs the dispatcher
 * @param pollInterval how often the dispatcher asks what is due
 * @param batchSize the most messages one poll takes, across every salon
 * @param perSalon the most messages one poll takes for one salon, so that one salon's backlog
 *        cannot hold back the others
 * @param lease how long a claim holds a message; a claim whose lease runs out (the API stopped
 *        mid-call) is attempted again. Longer than the provider's time limit
 * @param maxAttempts attempts in all, after which a message is {@code dead}
 * @param backoff the wait before the next attempt after a failure
 * @param provider the limits on every provider call
 * @param breaker the circuit breaker of each channel
 * @param retention how long a delivered message is kept
 * @param shutdownGrace on stop, how long calls in flight are given to finish before they are left
 *        to their lease
 */
@ConfigurationProperties("salon.outbox")
public record OutboxSettings(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("20") int perSalon,
        @DefaultValue("60s") Duration lease,
        @DefaultValue("10") int maxAttempts,
        @DefaultValue Backoff backoff,
        @DefaultValue Provider provider,
        @DefaultValue Breaker breaker,
        @DefaultValue("30d") Duration retention,
        @DefaultValue("5s") Duration shutdownGrace) {

    public OutboxSettings {
        Objects.requireNonNull(backoff, "backoff");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(breaker, "breaker");
        positive("salon.outbox.poll-interval", pollInterval);
        positive("salon.outbox.lease", lease);
        positive("salon.outbox.retention", retention);
        if (shutdownGrace.isNegative()) {
            throw new IllegalArgumentException("salon.outbox.shutdown-grace must not be negative");
        }
        if (batchSize < 1 || perSalon < 1) {
            throw new IllegalArgumentException("salon.outbox.batch-size and per-salon must be at least 1");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("salon.outbox.max-attempts must be at least 1");
        }
        if (lease.compareTo(provider.timeLimit()) <= 0) {
            throw new IllegalArgumentException("salon.outbox.lease (" + lease + ") must be longer than "
                    + "salon.outbox.provider.time-limit (" + provider.timeLimit() + "), or a call still in "
                    + "progress could be claimed again");
        }
    }

    /**
     * @param initial the wait after the first failure
     * @param multiplier how much longer each next wait is
     * @param max the longest wait
     */
    public record Backoff(@DefaultValue("30s") Duration initial, @DefaultValue("2") double multiplier,
            @DefaultValue("1h") Duration max) {

        public Backoff {
            positive("salon.outbox.backoff.initial", initial);
            positive("salon.outbox.backoff.max", max);
            if (multiplier < 1) {
                throw new IllegalArgumentException("salon.outbox.backoff.multiplier must be at least 1");
            }
        }
    }

    /**
     * @param timeLimit how long a provider call may take before it counts as failed
     * @param concurrency how many calls run at once per channel
     */
    public record Provider(@DefaultValue("10s") Duration timeLimit, @DefaultValue("4") int concurrency) {

        public Provider {
            positive("salon.outbox.provider.time-limit", timeLimit);
            if (concurrency < 1) {
                throw new IllegalArgumentException("salon.outbox.provider.concurrency must be at least 1");
            }
        }
    }

    /**
     * @param failureThreshold consecutive failures on a channel after which its breaker opens
     * @param waitDuration how long an open breaker waits before it lets one trial call through
     */
    public record Breaker(@DefaultValue("5") int failureThreshold, @DefaultValue("60s") Duration waitDuration) {

        public Breaker {
            positive("salon.outbox.breaker.wait-duration", waitDuration);
            if (failureThreshold < 1) {
                throw new IllegalArgumentException("salon.outbox.breaker.failure-threshold must be at least 1");
            }
        }
    }

    static void positive(String name, Duration value) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
