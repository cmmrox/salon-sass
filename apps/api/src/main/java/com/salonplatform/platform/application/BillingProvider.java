package com.salonplatform.platform.application;

import java.util.UUID;

/**
 * The platform's billing provider (docs/architecture/operator-console.md, "Payment"): asked to charge
 * an invoice with an attempt's stable idempotency key, outside any transaction, by the outbox. Its
 * result arrives later as a signed provider event. Only a local fake exists while D-06 is open; in
 * any other environment no provider is configured and charge messages wait undelivered.
 */
public interface BillingProvider {

    /** One charge: the salon, the invoice, the attempt's idempotency key and the exact amount. */
    record Charge(UUID salonId, UUID invoiceId, UUID attemptKey, long amount, String currency) {}

    void charge(Charge charge) throws Exception;
}
