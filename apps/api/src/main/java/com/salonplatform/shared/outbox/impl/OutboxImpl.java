package com.salonplatform.shared.outbox.impl;

import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.outbox.Outbox;
import com.salonplatform.shared.outbox.OutboxMessageView;
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

@Service
class OutboxImpl implements Outbox {

    private final OutboxMessageRepository messages;

    private final OutboxStore store;

    private final SalonTime time;

    private final Traces traces;

    private final JsonMapper json;

    OutboxImpl(OutboxMessageRepository messages, OutboxStore store, SalonTime time, Traces traces, JsonMapper json) {
        this.messages = messages;
        this.store = store;
        this.time = time;
        this.traces = traces;
        this.json = json;
    }

    @Override
    public UUID enqueue(String channel, Object payload) {
        return enqueue(channel, payload, time.now());
    }

    @Override
    public UUID enqueue(String channel, Object payload, Instant at) {
        requireWritableSalonTransaction();
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("a message has a channel");
        }
        if (EventTypes.CHANNEL.equals(channel)) {
            throw new IllegalArgumentException("the channel '" + channel + "' carries events: publish them");
        }
        UUID id = UUID.randomUUID();
        messages.persist(OutboxMessage.delivery(
                id,
                channel,
                json.writeValueAsString(payload),
                traces.currentTraceParent().orElse(null),
                at));
        return id;
    }

    @Override
    public UUID publish(Record event) {
        requireWritableSalonTransaction();
        String type = EventTypes.nameOf(event.getClass());
        UUID id = UUID.randomUUID();
        messages.persist(OutboxMessage.event(
                id,
                type,
                json.writeValueAsString(event),
                traces.currentTraceParent().orElse(null),
                time.now()));
        return id;
    }

    private static void requireWritableSalonTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException(
                    "An outbox message is written in the transaction of the change that causes it;"
                            + " there is none here");
        }
        TenantContext.resolved()
                .orElseThrow(() -> new IllegalStateException(
                        "An outbox message belongs to a salon: write it inside work for one (TenantContext)"));
    }

    @Override
    public boolean cancel(UUID id, Instant at) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("Cancellation requires a writable tenant transaction");
        }
        TenantContext.resolved().orElseThrow(() -> new IllegalStateException("Cancellation requires a salon"));
        return store.cancelUndelivered(TenantContext.require().id(), id, at);
    }

    @Override
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

    @Override
    public Optional<OutboxMessageView> find(UUID id) {
        return messages.findById(id).map(OutboxMessage::view);
    }
}
