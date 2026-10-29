package com.salonplatform.shared.outbox;

import com.salonplatform.shared.tenancy.TenantContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every module's {@link EventSubscriber}s by event type, and the delivery of one event to them. The
 * dispatcher calls {@link #deliver} for a claimed event, with the event's salon set, as it calls a
 * provider for a claimed message, so an event has the outbox's lease, backoff and dead state.
 *
 * <p>Each subscriber runs in a transaction of its own, which first records it as having processed the
 * event: the record and the subscriber's change commit together, so a repeated delivery, after a lease ran
 * out or by a second dispatcher, applies nothing twice, and a subscriber that already succeeded is passed
 * over when another one failed. An event type with no subscriber is delivered at once.
 */
@Component
class EventSubscribers {

    private static final Logger log = LoggerFactory.getLogger(EventSubscribers.class);

    private static final Pattern SUBSCRIBER_NAME = Pattern.compile("[a-z]+\\.[a-z][a-z0-9-]*");

    private final Map<String, List<EventSubscriber<?>>> byType;

    private final ProcessedEventStore processed;

    private final TransactionTemplate transactions;

    private final JsonMapper json;

    private final Clock clock;

    EventSubscribers(
            List<EventSubscriber<?>> subscribers,
            ProcessedEventStore processed,
            PlatformTransactionManager transactionManager,
            JsonMapper json,
            Clock clock) {
        this.byType = byType(subscribers);
        this.processed = processed;
        this.transactions = new TransactionTemplate(transactionManager);
        this.json = json;
        this.clock = clock;
    }

    /**
     * Delivers one event of the current salon to every subscriber of its type that has not processed it
     * yet. Throws, naming each subscriber that failed, when any did; the others' changes stay committed.
     */
    void deliver(UUID eventId, String eventType, String payload) {
        List<String> failures = new ArrayList<>();
        for (EventSubscriber<?> subscriber : byType.getOrDefault(eventType, List.of())) {
            try {
                deliverOnce(subscriber, eventId, payload);
            } catch (RuntimeException ex) {
                log.warn("Subscriber {} failed on event {} ({})", subscriber.name(), eventId, eventType, ex);
                failures.add(subscriber.name() + ": "
                        + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(String.join("; ", failures));
        }
    }

    private <E extends Record> void deliverOnce(EventSubscriber<E> subscriber, UUID eventId, String payload) {
        transactions.executeWithoutResult(status -> {
            if (processed.record(TenantContext.require().id(), eventId, subscriber.name(), clock.instant())) {
                subscriber.handle(read(payload, subscriber.event()));
            }
        });
    }

    /**
     * Reads the payload back into the record the subscriber names, refusing an unknown or a missing field
     * rather than guessing: a payload that does not fit is a failed delivery, which the outbox keeps.
     */
    private <E extends Record> E read(String payload, Class<E> event) {
        return json.readerFor(event)
                .with(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                        DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .readValue(payload);
    }

    private static Map<String, List<EventSubscriber<?>>> byType(List<EventSubscriber<?>> subscribers) {
        Set<String> names = new HashSet<>();
        Map<String, List<EventSubscriber<?>>> byType = new HashMap<>();
        for (EventSubscriber<?> subscriber : subscribers) {
            if (!SUBSCRIBER_NAME.matcher(subscriber.name()).matches()) {
                throw new IllegalStateException("The event subscriber name '" + subscriber.name()
                        + "' must be <module>.<what it does>, in lowercase");
            }
            if (!names.add(subscriber.name())) {
                throw new IllegalStateException("Two event subscribers are named '" + subscriber.name() + "'");
            }
            byType.computeIfAbsent(EventTypes.nameOf(subscriber.event()), type -> new ArrayList<>())
                    .add(subscriber);
        }
        byType.replaceAll((type, each) -> List.copyOf(each));
        return Map.copyOf(byType);
    }
}
