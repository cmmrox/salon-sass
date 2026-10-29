package com.salonplatform.shared.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * What a message's state is: for whoever wrote it, and for the local probe.
 *
 * @param id the message's id, which is also the idempotency key its provider is given
 * @param tenantId its salon
 * @param channel the provider's channel
 * @param status where it is on its way out
 * @param attempts attempts begun so far
 * @param nextAttemptAt when it may next be attempted
 * @param leaseUntil while it is being delivered, until when its claim holds; otherwise null
 * @param lastError why the last attempt failed, or null
 * @param createdAt when it was written
 * @param sentAt when it was delivered, or null
 */
public record OutboxMessageView(
        UUID id,
        UUID tenantId,
        String channel,
        OutboxStatus status,
        int attempts,
        Instant nextAttemptAt,
        Instant leaseUntil,
        String lastError,
        Instant createdAt,
        Instant sentAt) {}
