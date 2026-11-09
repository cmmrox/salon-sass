package com.salonplatform.booking.domain;

import java.util.Optional;

/** A customer's name on a booking: 2 to 60 characters once trimmed (F07-S01 AC-2). */
public final class CustomerName {

    public static final int MIN = 2;

    public static final int MAX = 60;

    private CustomerName() {}

    /** The trimmed name, when it is long enough and not too long; characters are counted as code points. */
    public static Optional<String> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String trimmed = text.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        return length >= MIN && length <= MAX ? Optional.of(trimmed) : Optional.empty();
    }
}
