package com.salonplatform.shared.outbox;

/** Declared cancellation, rather than a successful delivery or a retryable provider failure. */
public final class OutboxCancelled extends RuntimeException {
    public OutboxCancelled() {
        super("The delivery is no longer applicable");
    }
}
