package com.salonplatform.identity.application;

import com.salonplatform.identity.inprocess.PreparedOwner;

/**
 * Identity's own {@link PreparedOwner}: the only kind {@code bootstrapOwner} accepts. Package-private, so
 * nothing outside identity's application layer can create one or read its hash.
 */
record ValidatedOwner(String name, String email, String passwordHash) implements PreparedOwner {

    /** Never the name, the email or the hash: an Owner being prepared may be logged by its caller. */
    @Override
    public String toString() {
        return "PreparedOwner[redacted]";
    }
}
