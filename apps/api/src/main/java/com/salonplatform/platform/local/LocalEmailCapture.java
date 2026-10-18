package com.salonplatform.platform.local;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.salonplatform.shared.outbox.OutboxDelivery;
import com.salonplatform.shared.outbox.OutboxProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@code local} only: the {@code email} channel's capturing fake, as for {@code sms} and
 * {@code whatsapp} (and, through {@link LocalPlatformEmailCapture}, the {@code platform-email} channel). Nothing is sent; each delivered email is kept in memory (until cleared or the API
 * restarts) for the hook to list by recipient. A redelivery of the same message is captured once.
 */
@Component
@Profile("local")
class LocalEmailCapture implements OutboxProvider {

    record CapturedEmail(UUID messageId, UUID salonId, String recipient, String kind, String subject, String body,
            List<String> links, Instant deliveredAt) {
    }

    private final List<CapturedEmail> captured = new CopyOnWriteArrayList<>();
    private final JsonMapper json;
    private final Clock clock;

    LocalEmailCapture(JsonMapper json, Clock clock) {
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String channel() {
        return "email";
    }

    @Override
    public void deliver(OutboxDelivery delivery) {
        JsonNode payload = json.readTree(delivery.payload());
        List<String> links = new ArrayList<>();
        payload.path("links").forEach(link -> links.add(link.asString()));
        capture(delivery, payload, links);
    }

    /** Keeps one delivered email (once per message), whichever email channel delivered it. */
    synchronized void capture(OutboxDelivery delivery, JsonNode payload, List<String> links) {
        if (captured.stream().anyMatch(each -> each.messageId().equals(delivery.messageId()))) {
            return;
        }
        captured.add(new CapturedEmail(delivery.messageId(), delivery.tenantId(), payload.path("recipient").asString(),
                payload.path("kind").asString(), payload.path("subject").asString(), payload.path("body").asString(),
                List.copyOf(links), clock.instant()));
    }

    /** Forgets the emails of these salons (the local reset removes them). */
    void clearSalons(java.util.Set<UUID> salons) {
        captured.removeIf(each -> salons.contains(each.salonId()));
    }

    List<CapturedEmail> list(String recipient) {
        String wanted = recipient == null ? null : recipient.strip().toLowerCase(Locale.ROOT);
        return captured.stream().filter(each -> wanted == null || each.recipient().equalsIgnoreCase(wanted)).toList();
    }

    void clear(String recipient) {
        String wanted = recipient == null ? null : recipient.strip().toLowerCase(Locale.ROOT);
        captured.removeIf(each -> wanted == null || each.recipient().equalsIgnoreCase(wanted));
    }
}
