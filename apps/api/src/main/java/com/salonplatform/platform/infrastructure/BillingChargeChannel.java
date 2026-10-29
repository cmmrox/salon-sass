package com.salonplatform.platform.infrastructure;

import com.salonplatform.platform.application.BillingProvider;
import com.salonplatform.shared.outbox.OutboxDelivery;
import com.salonplatform.shared.outbox.OutboxProvider;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outbox channel that makes a charge attempt's provider call, with no transaction open, passing
 * the attempt's idempotency key, so a delivery attempted again after an interruption never charges
 * twice. Created only where a billing provider exists.
 */
public final class BillingChargeChannel implements OutboxProvider {

    private final BillingProvider provider;
    private final JsonMapper json;

    public BillingChargeChannel(BillingProvider provider, JsonMapper json) {
        this.provider = provider;
        this.json = json;
    }

    @Override
    public String channel() {
        return BillingLedger.CHARGE;
    }

    @Override
    public void deliver(OutboxDelivery delivery) throws Exception {
        JsonNode payload = json.readTree(delivery.payload());
        provider.charge(new BillingProvider.Charge(
                delivery.tenantId(),
                UUID.fromString(payload.get("invoiceId").asString()),
                UUID.fromString(payload.get("attemptKey").asString()),
                payload.get("amount").asLong(),
                payload.get("currency").asString()));
    }
}
