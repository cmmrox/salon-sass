package com.salonplatform.shared.outbox;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.type.SqlTypes;

/**
 * A message on its way out of the system, written in the transaction of the change that caused it.
 * Written and read here through Hibernate (its salon stamped and filtered by {@code @TenantId},
 * checked again by row-level security); its delivery state is changed by {@link OutboxStore}, one
 * salon at a time.
 */
@Entity
@Table(name = "outbox_message")
class OutboxMessage {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "channel", nullable = false, updatable = false)
    private String channel;

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

    protected OutboxMessage() {
    }

    /** A new message, due at once. */
    OutboxMessage(UUID id, String channel, String payload, String traceParent, Instant at) {
        this.id = id;
        this.channel = channel;
        this.payload = payload;
        this.status = OutboxStatus.PENDING.value();
        this.attempts = 0;
        this.nextAttemptAt = at;
        this.traceParent = traceParent;
        this.createdAt = at;
        this.updatedAt = at;
    }

    OutboxMessageView view() {
        return new OutboxMessageView(id, tenantId, channel, OutboxStatus.of(status), attempts, nextAttemptAt, leaseUntil,
                lastError, createdAt, sentAt);
    }
}
