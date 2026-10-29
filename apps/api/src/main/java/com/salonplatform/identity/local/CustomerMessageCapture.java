package com.salonplatform.identity.local;

import com.salonplatform.identity.service.CustomerAccess;
import com.salonplatform.shared.outbox.*;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * A local synthetic provider capture, never a real sender. Delay/failure are outside database
 * locks.
 */
@Service
@Profile("local")
class CustomerMessageCapture {
    record Key(UUID tenant, String channel) {}

    record ChallengeKey(UUID tenant, UUID challenge) {}

    static final class Control {
        final AtomicInteger failures;
        final long delayMs;

        Control(int failures, long delayMs) {
            this.failures = new AtomicInteger(failures);
            this.delayMs = delayMs;
        }
    }

    record Captured(UUID challengeId, String channel, String code, int attempt, Instant deliveredAt) {}

    private final ConcurrentMap<Key, Control> controls = new ConcurrentHashMap<>();
    private final ConcurrentMap<ChallengeKey, Captured> captures = new ConcurrentHashMap<>();
    private final CustomerAccess customers;
    private final Clock clock;

    CustomerMessageCapture(CustomerAccess customers, Clock clock) {
        this.customers = customers;
        this.clock = clock;
    }

    void deliver(OutboxDelivery message) throws Exception {
        captures.entrySet()
                .removeIf(e ->
                        !clock.instant().isBefore(e.getValue().deliveredAt().plusSeconds(300)));
        Control control = controls.getOrDefault(new Key(message.tenantId(), message.channel()), new Control(0, 0));
        if (control.delayMs > 0) Thread.sleep(control.delayMs);
        if (control.failures.getAndUpdate(n -> Math.max(0, n - 1)) > 0)
            throw new IllegalStateException("The local message provider refused this attempt");
        CustomerAccess.Envelope envelope = tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .readValue(message.payload(), CustomerAccess.Envelope.class);
        boolean captured = customers.captureIfLive(
                message,
                clear -> captures.putIfAbsent(
                        new ChallengeKey(message.tenantId(), envelope.challengeId()),
                        new Captured(
                                envelope.challengeId(),
                                message.channel(),
                                clear.code(),
                                message.attempt(),
                                clock.instant())));
        if (!captured) throw new OutboxCancelled();
    }

    void control(String channel, int failures, long delayMs) {
        if (!Set.of("sms", "whatsapp").contains(channel)) throw new NotFoundException();
        controls.put(new Key(TenantContext.require().id(), channel), new Control(failures, delayMs));
    }

    Captured capture(UUID id) {
        if (customers.messageFor(id).isEmpty()) throw new NotFoundException();
        Captured capture = captures.get(new ChallengeKey(TenantContext.require().id(), id));
        if (capture == null) throw new NotFoundException();
        return capture;
    }

    @org.springframework.transaction.event.TransactionalEventListener
    void erased(com.salonplatform.identity.CustomerDataErased event) {
        captures.keySet()
                .removeIf(k -> k.tenant().equals(event.tenantId())
                        && event.challengeIds().contains(k.challenge()));
    }

    void clear() {
        UUID tenant = TenantContext.require().id();
        captures.keySet().removeIf(k -> k.tenant().equals(tenant));
        controls.keySet().removeIf(k -> k.tenant().equals(tenant));
    }
}
