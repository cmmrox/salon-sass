package com.salonplatform.shared.audit;

import java.util.Locale;

/** Who acted. */
public enum ActorKind {
    /** A member of the salon's staff, signed in. */
    STAFF,
    /** One of the salon's customers. */
    CUSTOMER,
    /** A platform operator, in a support session (PLT-07). */
    OPERATOR,
    /** The platform itself: a job, a delivery, a test hook. */
    SYSTEM;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }
}
