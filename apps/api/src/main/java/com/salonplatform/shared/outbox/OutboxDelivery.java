package com.salonplatform.shared.outbox;

import java.util.UUID;

/**
 * One attempt to deliver a message, as its provider is given it.
 *
 * @param messageId the message's id
 * @param tenantId its salon, which is also the salon set while the provider is called
 * @param channel its channel
 * @param payload its payload, as JSON
 * @param attempt which attempt this is, from 1
 */
public record OutboxDelivery(UUID messageId, UUID tenantId, String channel, String payload, int attempt) {

    /** The key the provider passes on, so that the same message is never delivered twice: its id. */
    public String idempotencyKey() {
        return messageId.toString();
    }
}
