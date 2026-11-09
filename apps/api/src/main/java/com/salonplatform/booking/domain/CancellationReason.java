package com.salonplatform.booking.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** Why staff cancelled a booking (BKG-06: every change records why). */
public enum CancellationReason {
    CUSTOMER_ASKED,
    SALON_UNABLE,
    BOOKED_BY_MISTAKE,
    OTHER;

    /** The longest note a cancellation may carry. */
    public static final int NOTE_MAX = 200;

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<CancellationReason> parse(String value) {
        return Arrays.stream(values())
                .filter(reason -> reason.value().equals(value))
                .findFirst();
    }
}
