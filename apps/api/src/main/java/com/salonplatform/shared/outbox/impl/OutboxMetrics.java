package com.salonplatform.shared.outbox.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * The outbox's meters (NFR-16):
 * <ul>
 * <li>{@value #WAITING}: messages waiting, pending or being delivered, across every salon, apart from the
 * pending events of a type this instance has no subscriber for;</li>
 * <li>{@value #UNSUBSCRIBED}: those events, which wait for a build that subscribes to their type and are
 * closed as {@code unrouted} after {@code salon.outbox.unrouted-after} (decision 119), so that they never make
 * the first two look like a stuck dispatcher;</li>
 * <li>{@value #OLDEST_DUE_AGE}: how long ago, in seconds, the oldest message that is due became due
 * (0 when none is);</li>
 * <li>{@value #DEAD}: messages that went {@code dead};</li>
 * <li>{@value #DELIVERIES}: delivery attempts, by {@code kind} ({@code delivery} to a provider, or
 * {@code event} to the subscribers of an event between modules), {@code channel} and {@code outcome}
 * ({@code sent}, {@code failed}, {@code timed_out}, {@code cancelled}).</li>
 * </ul>
 * The first three are refreshed by the dispatcher on each poll. {@value #WAITING}, {@value #OLDEST_DUE_AGE}
 * and {@value #DEAD} count events and provider messages together.
 */
@Component
class OutboxMetrics {

    static final String WAITING = "salon.outbox.waiting";

    static final String OLDEST_DUE_AGE = "salon.outbox.oldest.due.age";

    static final String UNSUBSCRIBED = "salon.outbox.unsubscribed";

    static final String DEAD = "salon.outbox.dead";

    static final String DELIVERIES = "salon.outbox.deliveries";

    private final MeterRegistry registry;

    private final AtomicLong waiting = new AtomicLong();

    private final AtomicLong oldestDueAgeSeconds = new AtomicLong();

    private final AtomicLong unsubscribed = new AtomicLong();

    private final Counter dead;

    OutboxMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(WAITING, waiting, AtomicLong::get)
                .description("Messages waiting to be delivered")
                .baseUnit("messages")
                .register(registry);
        Gauge.builder(OLDEST_DUE_AGE, oldestDueAgeSeconds, AtomicLong::get)
                .description("How long the oldest due message has been due")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder(UNSUBSCRIBED, unsubscribed, AtomicLong::get)
                .description("Events waiting for a build that subscribes to their type")
                .baseUnit("messages")
                .register(registry);
        this.dead = Counter.builder(DEAD)
                .description("Messages that ran out of attempts")
                .baseUnit("messages")
                .register(registry);
    }

    void backlog(OutboxStore.Backlog backlog, Instant now) {
        waiting.set(backlog.waiting());
        unsubscribed.set(backlog.unsubscribed());
        oldestDueAgeSeconds.set(
                backlog.oldestDueAt() == null
                        ? 0
                        : Math.max(
                                0, Duration.between(backlog.oldestDueAt(), now).toSeconds()));
    }

    void delivery(OutboxStore.Claim claim, String outcome) {
        Counter.builder(DELIVERIES)
                .description("Delivery attempts by kind, channel and outcome")
                .tag("kind", claim.isEvent() ? "event" : "delivery")
                .tag("channel", claim.channel())
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }

    void dead() {
        dead.increment();
    }
}
