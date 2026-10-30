package com.salonplatform.identity.local;

import com.salonplatform.identity.CustomerDataErased;
import com.salonplatform.identity.dto.CapturedMessage;
import com.salonplatform.identity.service.CustomerAccessService;
import com.salonplatform.shared.outbox.EventSubscriber;
import com.salonplatform.shared.outbox.OutboxCancelled;
import com.salonplatform.shared.outbox.OutboxDelivery;
import com.salonplatform.shared.tenancy.TenantContext;
import com.salonplatform.shared.web.NotFoundException;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * A local synthetic provider capture, never a real sender. Delay/failure are outside database
 * locks. It subscribes to {@link CustomerDataErased} so that an erased customer's captured codes go
 * once the erasure has committed.
 */
@Service
@Profile("local")
class CustomerMessageCapture implements EventSubscriber<CustomerDataErased> {
    private record Key(UUID tenant, String channel) {}

    private record ChallengeKey(UUID tenant, UUID challenge) {}

    static final class Control {
        final AtomicInteger failures;
        final long delayMs;

        Control(int failures, long delayMs) {
            this.failures = new AtomicInteger(failures);
            this.delayMs = delayMs;
        }
    }

    private final ConcurrentMap<Key, Control> controls = new ConcurrentHashMap<>();
    private final ConcurrentMap<ChallengeKey, CapturedMessage> captures = new ConcurrentHashMap<>();
    private final CustomerAccessService customers;
    private final Clock clock;

    CustomerMessageCapture(CustomerAccessService customers, Clock clock) {
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
        CustomerAccessService.Envelope envelope =
                JsonMapper.builder().build().readValue(message.payload(), CustomerAccessService.Envelope.class);
        boolean captured = customers.captureIfLive(
                message,
                clear -> captures.putIfAbsent(
                        new ChallengeKey(message.tenantId(), envelope.challengeId()),
                        new CapturedMessage(
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

    CapturedMessage capture(UUID id) {
        if (customers.messageFor(id).isEmpty()) throw new NotFoundException();
        CapturedMessage capture =
                captures.get(new ChallengeKey(TenantContext.require().id(), id));
        if (capture == null) throw new NotFoundException();
        return capture;
    }

    @Override
    public String name() {
        return "identity.local-message-capture";
    }

    @Override
    public Class<CustomerDataErased> event() {
        return CustomerDataErased.class;
    }

    @Override
    public void handle(CustomerDataErased event) {
        UUID tenant = TenantContext.require().id();
        captures.keySet()
                .removeIf(k -> k.tenant().equals(tenant) && event.challengeIds().contains(k.challenge()));
    }

    void clear() {
        UUID tenant = TenantContext.require().id();
        captures.keySet().removeIf(k -> k.tenant().equals(tenant));
        controls.keySet().removeIf(k -> k.tenant().equals(tenant));
    }
}
