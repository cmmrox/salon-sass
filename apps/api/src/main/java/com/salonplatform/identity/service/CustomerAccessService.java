package com.salonplatform.identity.service;

import com.salonplatform.identity.dto.Challenge;
import com.salonplatform.identity.dto.Tokens;
import com.salonplatform.shared.outbox.OutboxDelivery;
import com.salonplatform.shared.outbox.OutboxMessageView;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Customer sign-in by one-time code (docs/architecture/identity-and-access.md): salon-scoped, one-use codes with
 * atomic accepted-send ceilings and encrypted delivery.
 */
public interface CustomerAccessService {

    /** What the outbox carries for a code: the challenge and its encrypted message, never the code in clear. */
    record Envelope(UUID challengeId, String encrypted) {}

    /** The decrypted message of a code: the number it goes to and the code. */
    record ClearMessage(String mobile, String code) {}

    /**
     * Sends a sign-in code to a Sri Lankan mobile number by SMS or WhatsApp, within the number's, device's,
     * address's and salon's ceilings.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 429 {@code rate_limited} with a retry delay
     *     when a ceiling is reached
     */
    Challenge request(String mobile, String channel, String deviceLabel, String deviceId, String address);

    /** Signs the customer in with the code of a challenge, on the device that asked for it. */
    Tokens verify(UUID challengeId, String code, String deviceId);

    /**
     * Called outside provider I/O after its delay, then atomically rechecks challenge state before capture.
     *
     * @return whether the message was still live and was captured
     */
    boolean captureIfLive(OutboxDelivery delivery, Consumer<ClearMessage> capture);

    /** Local fixtures only: the outbox message that carries a challenge's code. */
    Optional<UUID> messageFor(UUID challengeId);

    /** Local fixtures only: the delivery state of a challenge's message. */
    OutboxMessageView delivery(UUID challengeId);

    /** Local fixtures only: cancels the salon's queued codes and removes its customers and their records. */
    void resetCustomer();

    /**
     * Cancels and removes expired codes, and removes code rate buckets whose window has run out, in the caller's
     * transaction.
     */
    void cleanup(Instant now);
}
