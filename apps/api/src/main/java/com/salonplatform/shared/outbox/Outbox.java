package com.salonplatform.shared.outbox;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.time.SalonTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The way anything leaves the system (docs/engineering/backend.md, "Transactions, outbox and
 * external calls"). A message is written in the caller's transaction, so it exists exactly when the
 * change that caused it was committed; it is delivered afterwards by the dispatcher, with retry,
 * time limits and a circuit breaker. Called outside a writable transaction, it refuses.
 */
@Service
public class Outbox {

    @PersistenceContext
    private EntityManager entityManager;

    private final SalonTime time;

    private final Traces traces;

    private final JsonMapper json;

    Outbox(SalonTime time, Traces traces, JsonMapper json) {
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

    /** As {@link #enqueue(String, Object)}, at a time the caller already took for the same change. */
    public UUID enqueue(String channel, Object payload, Instant at) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    "An outbox message is written in the transaction of the change that causes it; there is none here");
        }
        TenantContext.resolved().orElseThrow(() -> new IllegalStateException(
                "An outbox message belongs to a salon: write it inside work for one (TenantContext)"));
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("a message has a channel");
        }
        UUID id = UUID.randomUUID();
        entityManager.persist(new OutboxMessage(id, channel, json.writeValueAsString(payload),
                traces.currentTraceParent().orElse(null), at));
        return id;
    }

    /** One of the current salon's messages. Another salon's is not found, as one that does not exist. */
    public Optional<OutboxMessageView> find(UUID id) {
        return entityManager.createQuery("select m from OutboxMessage m where m.id = :id", OutboxMessage.class)
                .setParameter("id", id).getResultStream().findFirst().map(OutboxMessage::view);
    }
}
