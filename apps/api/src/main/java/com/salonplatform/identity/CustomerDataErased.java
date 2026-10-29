package com.salonplatform.identity;

import com.salonplatform.shared.outbox.EventType;
import java.util.Set;
import java.util.UUID;

/**
 * A customer's personal data was erased in the event's salon: the one-time-code challenges whose
 * messages went with it. Published through the outbox in the erasing transaction, so it exists only
 * once the erasure committed. It holds opaque ids and never a personal value; the salon is the event's
 * own, not a field.
 *
 * @param challengeIds the erased challenges, whose delivered or captured messages must go too
 */
@EventType("identity.CustomerDataErased.v1")
public record CustomerDataErased(Set<UUID> challengeIds) {

    public CustomerDataErased {
        challengeIds = Set.copyOf(challengeIds);
    }
}
