package com.salonplatform.shared.outbox;

import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.time.SalonTime;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * The way anything leaves the system (docs/engineering/backend.md, "Transactions, outbox and
 * external calls"). A message is written in the caller's transaction, so it exists exactly when the
 * change that caused it was committed; it is delivered afterwards by the dispatcher, with retry,
 * time limits and a circuit breaker. Called outside a writable transaction, it refuses.
 */
@Service
public class Outbox {

    private final OutboxMessageRepository messages;

    private final OutboxStore store;

    private final SalonTime time;

    private final Traces traces;

    private final JsonMapper json;

    Outbox(OutboxMessageRepository messages, OutboxStore store, SalonTime time, Traces traces, JsonMapper json) {
        this.messages = messages;
        this.store = store;
        this.time = time;
        this.traces = traces;
        this.json = json;
    }

    /**
     * Writes a message for the current salon, due at once. Its delivery belongs to the current
     * trace.
     *
     * @param channel the channel whose provider delivers it
     * @param payload what the provider is given, written as JSON
     * @return the message's id, which is also its idempotency key
     */
    public UUID enqueue(String channel, Object payload) {
        return enqueue(channel, payload, time.now());
    }

    /**
     * As {@link #enqueue(String, Object)}, at a time the caller already took for the same change.
     */
    public UUID enqueue(String channel, Object payload, Instant at) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    "An outbox message is written in the transaction of the change that causes it;"
                            + " there is none here");
        }
        TenantContext.resolved()
                .orElseThrow(() -> new IllegalStateException(
                        "An outbox message belongs to a salon: write it inside work" + " for one (TenantContext)"));
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("a message has a channel");
        }
        UUID id = UUID.randomUUID();
        messages.persist(new OutboxMessage(
                id,
                channel,
                json.writeValueAsString(payload),
                traces.currentTraceParent().orElse(null),
                at));
        return id;
    }

    /**
     * Cancels an undelivered message in the caller's tenant transaction. An in-flight claim cannot
     * resurrect it.
     */
    public boolean cancel(UUID id, Instant at) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Cancellation requires a writable tenant transaction");
        }
        TenantContext.resolved().orElseThrow(() -> new IllegalStateException("Cancellation requires a salon"));
        return store.cancelUndelivered(TenantContext.require().id(), id, at);
    }

    /**
     * Erases identifying delivery envelopes for one owner inside its privacy transaction. The owner
     * recognizes its own payloads and returns only an opaque correlation id. Keyset pages remain
     * bounded even when matched rows are removed. All states, including sent, are erased; absent
     * rows cannot be recreated by conditional dispatcher finalization.
     */
    public Set<UUID> eraseMatching(Set<String> channels, Function<String, Optional<UUID>> matchingOwner, Instant at) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Erasure requires a writable tenant transaction");
        }
        UUID tenant = TenantContext.require().id();
        Set<UUID> correlations = new HashSet<>();
        List<OutboxMessageRepository.Payload> page = messages.findFirst100ByChannelInOrderById(channels);
        while (!page.isEmpty()) {
            for (OutboxMessageRepository.Payload message : page) {
                Optional<UUID> correlation = matchingOwner.apply(message.payload());
                if (correlation.isPresent()) {
                    cancel(message.id(), at);
                    store.remove(tenant, message.id());
                    correlations.add(correlation.get());
                }
            }
            page = messages.findFirst100ByChannelInAndIdGreaterThanOrderById(
                    channels, page.getLast().id());
        }
        return Set.copyOf(correlations);
    }

    /**
     * One of the current salon's messages. Another salon's is not found, as one that does not
     * exist.
     */
    public Optional<OutboxMessageView> find(UUID id) {
        return messages.findById(id).map(OutboxMessage::view);
    }
}
