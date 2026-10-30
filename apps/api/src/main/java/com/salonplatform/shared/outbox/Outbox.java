package com.salonplatform.shared.outbox;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The way anything leaves the system (docs/engineering/backend.md, "Transactions, outbox and
 * external calls"), and the way a module tells other modules that something happened ("Events between
 * modules"). A message or an event is written in the caller's transaction, so it exists exactly when the
 * change that caused it was committed; it is delivered afterwards by the dispatcher, with retry,
 * time limits and, for a provider, a circuit breaker. Called outside a writable transaction, it refuses.
 */
public interface Outbox {

    /**
     * Writes a message for the current salon, due at once. Its delivery belongs to the current
     * trace.
     *
     * @param channel the channel whose provider delivers it
     * @param payload what the provider is given, written as JSON
     * @return the message's id, which is also its idempotency key
     */
    UUID enqueue(String channel, Object payload);

    /**
     * As {@link #enqueue(String, Object)}, at a time the caller already took for the same change.
     */
    UUID enqueue(String channel, Object payload, Instant at);

    /**
     * Publishes an event between modules for the current salon, in the caller's transaction: it exists
     * exactly when the change that caused it was committed, and is then delivered to every subscriber of its
     * type (docs/engineering/backend.md, "Events between modules"). Its delivery belongs to the current trace.
     *
     * @param event a record annotated with {@link EventType}, holding ids and values only
     * @return the event's id
     */
    UUID publish(Record event);

    /**
     * Cancels an undelivered message in the caller's tenant transaction. An in-flight claim cannot
     * resurrect it.
     */
    boolean cancel(UUID id, Instant at);

    /**
     * Erases identifying delivery envelopes for one owner inside its privacy transaction. The owner
     * recognizes its own payloads and returns only an opaque correlation id. Keyset pages remain
     * bounded even when matched rows are removed. All states, including sent, are erased; absent
     * rows cannot be recreated by conditional dispatcher finalization.
     */
    Set<UUID> eraseMatching(Set<String> channels, Function<String, Optional<UUID>> matchingOwner, Instant at);

    /**
     * One of the current salon's messages. Another salon's is not found, as one that does not
     * exist.
     */
    Optional<OutboxMessageView> find(UUID id);
}
