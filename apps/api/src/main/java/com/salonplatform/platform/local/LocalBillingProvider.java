package com.salonplatform.platform.local;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.salonplatform.platform.application.BillingProvider;
import com.salonplatform.platform.application.ProviderEvents;
import com.salonplatform.platform.infrastructure.BillingChargeChannel;
import com.salonplatform.platform.infrastructure.BillingProviderKey;
import com.salonplatform.shared.outbox.OutboxProvider;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code local} only: the fake billing provider. Nothing is charged. Each charge answers with the
 * outcome set for its salon by the hook (success unless set), delivered as a signed provider event
 * through the same intake real events use, with a fresh event id and the attempt's key.
 */
@Component
@Profile("local")
class LocalBillingProvider implements BillingProvider {

    private final Map<UUID, String> outcomes = new ConcurrentHashMap<>();
    private final ProviderEvents events;
    private final BillingProviderKey key;
    private final JsonMapper json;
    private final Clock clock;

    LocalBillingProvider(ProviderEvents events, BillingProviderKey key, JsonMapper json, Clock clock) {
        this.events = events;
        this.key = key;
        this.json = json;
        this.clock = clock;
    }

    void outcome(UUID salon, String outcome) {
        outcomes.put(salon, outcome);
    }

    String outcome(UUID salon) {
        return outcomes.getOrDefault(salon, "success");
    }

    @Override
    public void charge(Charge charge) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", "evt_" + UUID.randomUUID().toString().replace("-", ""));
        event.put("invoiceId", charge.invoiceId().toString());
        event.put("attemptKey", charge.attemptKey().toString());
        event.put("outcome", outcome(charge.salonId()));
        event.put("occurredAt", clock.instant().toString());
        byte[] body = json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
        events.receive(body, key.sign(body).orElseThrow());
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("local")
    static class Channel {
        @Bean
        OutboxProvider billingChargeChannel(LocalBillingProvider provider, JsonMapper json) {
            return new BillingChargeChannel(provider, json);
        }
    }
}
