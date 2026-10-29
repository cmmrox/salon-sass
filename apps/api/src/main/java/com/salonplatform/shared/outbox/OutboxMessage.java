package com.salonplatform.shared.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * A message on its way out of the system, written in the transaction of the change that caused it: a
 * delivery, which a provider sends, or an event between modules, which the subscribers of its type handle.
 * Written and read through {@link OutboxMessageRepository} (its salon stamped and filtered by
 * {@code @TenantId}, checked again by row-level security); its delivery state is changed by
 * {@link OutboxStore}, one salon at a time.
 */
@Entity
@Table(name = "outbox_message")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class OutboxMessage {

    private static final String DELIVERY = "delivery";

    private static final String EVENT = "event";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "channel", nullable = false, updatable = false)
    private String channel;

    @Column(name = "event_type", updatable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "lease_token")
    private UUID leaseToken;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "trace_parent", updatable = false)
    private String traceParent;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    /** A new delivery for the channel's provider, due at once. */
    static OutboxMessage delivery(UUID id, String channel, String payload, String traceParent, Instant at) {
        return new OutboxMessage(id, DELIVERY, channel, null, payload, traceParent, at);
    }

    /** A new event of that type for its subscribers, due at once. */
    static OutboxMessage event(UUID id, String eventType, String payload, String traceParent, Instant at) {
        return new OutboxMessage(id, EVENT, EventTypes.CHANNEL, eventType, payload, traceParent, at);
    }

    private OutboxMessage(
            UUID id, String kind, String channel, String eventType, String payload, String traceParent, Instant at) {
        this.id = id;
        this.kind = kind;
        this.channel = channel;
        this.eventType = eventType;
        this.payload = payload;
        this.status = OutboxStatus.PENDING.value();
        this.attempts = 0;
        this.nextAttemptAt = at;
        this.traceParent = traceParent;
        this.createdAt = at;
        this.updatedAt = at;
    }

    OutboxMessageView view() {
        return new OutboxMessageView(
                id,
                tenantId,
                channel,
                OutboxStatus.of(status),
                attempts,
                nextAttemptAt,
                leaseUntil,
                lastError,
                createdAt,
                sentAt);
    }
}
