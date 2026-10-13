package com.salonplatform.shared.outbox;

import java.util.Locale;

/** Where a message is on its way out. */
public enum OutboxStatus {
    /** Waiting for its next attempt. */
    PENDING,
    /** Claimed by a dispatcher, which holds a lease on it while the provider is called. */
    SENDING,
    /** Delivered. */
    SENT,
    /** Intentionally not delivered because its purpose expired or was superseded. */
    CANCELLED,
    /** Out of attempts; it stays with its last error and is not attempted again. */
    DEAD;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static OutboxStatus of(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
