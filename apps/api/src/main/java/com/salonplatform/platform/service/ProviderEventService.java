package com.salonplatform.platform.service;

import com.salonplatform.platform.dto.Acknowledged;

/**
 * The billing provider's signed events (docs/architecture/operator-console.md, "Payment"): JSON
 * {@code {eventId,invoiceId,attemptKey,outcome,occurredAt}} with an HMAC-SHA256 of the exact body.
 * An unsigned or wrongly signed event is refused 401 {@code invalid_credentials} before it is read,
 * with no effect. A repeated event id is acknowledged without effect; an event for an unknown attempt
 * or invoice, or for an invoice already paid (a late success after a manual payment), is recorded
 * without changing it. A success pays the invoice once; a failure sends one failed-charge notice per
 * attempt. Events for one id are serialized, and the payment is unique per invoice in the database.
 */
public interface ProviderEventService {

    /** Receives one signed event from the billing provider. */
    Acknowledged receive(byte[] body, String signature);
}
