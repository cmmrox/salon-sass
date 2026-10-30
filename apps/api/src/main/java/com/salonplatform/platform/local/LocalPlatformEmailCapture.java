package com.salonplatform.platform.local;

import com.salonplatform.platform.service.SalonExportService;
import com.salonplatform.shared.outbox.OutboxDelivery;
import com.salonplatform.shared.outbox.OutboxLifecycle;
import com.salonplatform.shared.outbox.OutboxProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code local} only: the {@code platform-email} channel's capturing fake (F03-S03), into the same
 * capture as billing's emails. The export-ready email's links are sealed with the export key in the
 * outbox; they are opened here, as a real mail adapter would, and nowhere else.
 */
@Component
@Profile("local")
class LocalPlatformEmailCapture implements OutboxProvider {

    private final LocalEmailCapture capture;
    private final SalonExportService exports;
    private final JsonMapper json;

    LocalPlatformEmailCapture(LocalEmailCapture capture, SalonExportService exports, JsonMapper json) {
        this.capture = capture;
        this.exports = exports;
        this.json = json;
    }

    @Override
    public String channel() {
        return OutboxLifecycle.PLATFORM_EMAIL;
    }

    @Override
    public void deliver(OutboxDelivery delivery) {
        JsonNode payload = json.readTree(delivery.payload());
        capture.capture(
                delivery,
                payload,
                exports.openLinks(
                        payload.path("exportId").asString(),
                        payload.path("sealedLinks").asString()));
    }
}
