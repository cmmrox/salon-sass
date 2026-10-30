package com.salonplatform.platform.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

/** Instants to and from the platform tables' {@code timestamptz} columns, as the driver hands them over. */
final class PlatformTimes {

    private PlatformTimes() {}

    static Timestamp time(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        throw new IllegalStateException("Unexpected time value");
    }
}
