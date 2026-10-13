package com.salonplatform.identity.local;

import com.salonplatform.shared.outbox.*;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
class LocalWhatsAppProvider implements OutboxProvider {
    private final CustomerMessageCapture capture;

    LocalWhatsAppProvider(CustomerMessageCapture capture) {
        this.capture = capture;
    }

    public String channel() {
        return "whatsapp";
    }

    public void deliver(OutboxDelivery message) throws Exception {
        capture.deliver(message);
    }
}
