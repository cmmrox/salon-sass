package com.salonplatform.shared.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * The outbox's part in a salon's closure (PLT-06): every undelivered message of the closed salon is
 * cancelled in the closing platform transaction, through the outbox's own guarded function. Afterwards
 * only {@value #PLATFORM_EMAIL} messages (the platform's own email to the Owner contact, such as the
 * export-ready email) are delivered for a closed salon.
 */
public interface OutboxLifecycle {

    /** The one channel delivered for a closed salon: the platform's own email to its Owner contact. */
    String PLATFORM_EMAIL = "platform-email";

    /** Cancels every pending or in-flight message of a closed salon; returns how many. */
    int cancelClosedSalon(UUID salonId, Instant at);
}
