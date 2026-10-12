package com.salonplatform.shared.outbox;

/**
 * Delivers the messages of one channel: an adapter to an external provider (a messaging service, a
 * payment gateway) or its local fake. The dispatcher looks providers up by channel and calls each
 * with a time limit, a limit on calls at once and a circuit breaker, outside any transaction and
 * with the message's salon set.
 *
 * <p>A call that returns delivered the message; a call that throws, or does not return within the
 * time limit, failed and is tried again later. A provider passes the delivery's idempotency key on
 * to the external service, so that a message attempted again after an interrupted call is not
 * delivered twice. A failure's message is stored as the message's last error and logged: it must
 * never contain a secret or personal data.
 */
public interface OutboxProvider {

    /** The channel this provider delivers, such as {@code sms}. */
    String channel();

    void deliver(OutboxDelivery delivery) throws Exception;
}
