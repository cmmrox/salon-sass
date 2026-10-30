package com.salonplatform.identity.dto;

import com.salonplatform.shared.outbox.OutboxMessageView;
import com.salonplatform.shared.outbox.OutboxStatus;
import java.time.Instant;
import java.util.UUID;

/** A customer code message's delivery state, as the local hook shows it: the outbox's view, field for field. */
public record DeliveryView(
        UUID id,
        UUID tenantId,
        String channel,
        OutboxStatus status,
        int attempts,
        Instant nextAttemptAt,
        Instant leaseUntil,
        String lastError,
        Instant createdAt,
        Instant sentAt) {

    public static DeliveryView from(OutboxMessageView message) {
        return new DeliveryView(
                message.id(),
                message.tenantId(),
                message.channel(),
                message.status(),
                message.attempts(),
                message.nextAttemptAt(),
                message.leaseUntil(),
                message.lastError(),
                message.createdAt(),
                message.sentAt());
    }
}
