package com.salonplatform.identity.local;

import com.salonplatform.shared.outbox.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
class LocalSmsProvider implements OutboxProvider {
    private final CustomerMessageCapture capture;

    LocalSmsProvider(CustomerMessageCapture capture) {
        this.capture = capture;
    }

    public String channel() {
        return "sms";
    }

    public void deliver(OutboxDelivery message) throws Exception {
        capture.deliver(message);
    }
}
