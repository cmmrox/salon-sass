package com.salonplatform.shared.outbox;

import java.io.Serial;

/** Declared cancellation, rather than a successful delivery or a retryable provider failure. */
public final class OutboxCancelled extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public OutboxCancelled() {
        super("The delivery is no longer applicable");
    }
}
