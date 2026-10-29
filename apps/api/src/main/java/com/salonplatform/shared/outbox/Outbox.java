package com.salonplatform.shared.outbox;

import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.time.SalonTime;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
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
        entityManager.persist(new OutboxMessage(
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
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Cancellation requires a writable tenant transaction");
        TenantContext.resolved().orElseThrow(() -> new IllegalStateException("Cancellation requires a salon"));
        return entityManager
                        .createNativeQuery("UPDATE outbox_message SET status='cancelled', lease_until=NULL,"
                                + " lease_token=NULL, updated_at=:at WHERE tenant_id=:tenant"
                                + " AND id=:id AND status IN ('pending','sending','dead')")
                        .setParameter("tenant", TenantContext.require().id())
                        .setParameter("id", id)
                        .setParameter("at", at)
                        .executeUpdate()
                == 1;
    }

    /**
     * Erases identifying delivery envelopes for one owner inside its privacy transaction. The owner
     * recognizes its own payloads and returns only an opaque correlation id. Keyset pages remain
     * bounded even when matched rows are removed. All states, including sent, are erased; absent
     * rows cannot be recreated by conditional dispatcher finalization.
     */
    public java.util.Set<UUID> eraseMatching(
            java.util.Set<String> channels,
            java.util.function.Function<String, Optional<UUID>> matchingOwner,
            Instant at) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Erasure requires a writable tenant transaction");
        UUID tenant = TenantContext.require().id();
        java.util.Set<UUID> correlations = new java.util.HashSet<>();
        UUID after = null;
        while (true) {
            String condition = after == null ? "" : " and m.id>:after";
            var query = entityManager
                    .createQuery(
                            "select m.id,m.payload from OutboxMessage m where m.channel in"
                                    + " :channels"
                                    + condition
                                    + " order by m.id",
                            Object[].class)
                    .setParameter("channels", channels)
                    .setMaxResults(100);
            if (after != null) query.setParameter("after", after);
            var rows = query.getResultList();
            if (rows.isEmpty()) break;
            for (Object[] row : rows) {
                UUID id = (UUID) row[0];
                after = id;
                Optional<UUID> correlation = matchingOwner.apply((String) row[1]);
                if (correlation.isPresent()) {
                    cancel(id, at);
                    entityManager
                            .createNativeQuery("DELETE FROM outbox_message WHERE tenant_id=:tenant AND id=:id")
                            .setParameter("tenant", tenant)
                            .setParameter("id", id)
                            .executeUpdate();
                    correlations.add(correlation.get());
                }
            }
        }
        return java.util.Set.copyOf(correlations);
    }

    /**
     * One of the current salon's messages. Another salon's is not found, as one that does not
     * exist.
     */
    public Optional<OutboxMessageView> find(UUID id) {
        return entityManager
                .createQuery("select m from OutboxMessage m where m.id = :id", OutboxMessage.class)
                .setParameter("id", id)
                .getResultStream()
                .findFirst()
                .map(OutboxMessage::view);
    }
}
