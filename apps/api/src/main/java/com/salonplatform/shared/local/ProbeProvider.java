package com.salonplatform.shared.local;

import com.salonplatform.shared.outbox.OutboxDelivery;
import com.salonplatform.shared.outbox.OutboxProvider;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The fake provider of the channel {@value #CHANNEL}: does what its probe asked. It fails the first
 * {@code failures} attempts of a message, takes {@code delayMs} to answer every call, and counts the
 * calls it receives for each message id (the idempotency key).
 */
@Component
@Profile("local")
public class ProbeProvider implements OutboxProvider {

    public static final String CHANNEL = "probe";

    private final Map<UUID, AtomicInteger> calls = new ConcurrentHashMap<>();

    private final JsonMapper json;

    ProbeProvider(JsonMapper json) {
        this.json = json;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public void deliver(OutboxDelivery delivery) throws Exception {
        int call = calls.computeIfAbsent(UUID.fromString(delivery.idempotencyKey()), id -> new AtomicInteger())
                .incrementAndGet();
        JsonNode payload = json.readTree(delivery.payload());
        int failures = payload.path("failures").asInt(0);
        long delayMs = payload.path("delayMs").asLong(0);
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
        if (call <= failures) {
            throw new IllegalStateException(
                    "The probe's provider failed call " + call + " of the " + failures + " it was told to fail");
        }
    }

    /** How many calls the provider has received for this message since the API started. */
    public int callsFor(UUID messageId) {
        AtomicInteger count = calls.get(messageId);
        return count == null ? 0 : count.get();
    }
}
