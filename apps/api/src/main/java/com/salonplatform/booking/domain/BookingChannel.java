package com.salonplatform.booking.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Where a booking came from (BKG-16). Staff record the ones a front desk takes; {@link #CUSTOMER_APP} and
 * {@link #SHARED_APP_LINK} are written only by the customer app (F15).
 */
public enum BookingChannel {
    CUSTOMER_APP(false),
    STAFF_WEB(true),
    SHARED_APP_LINK(false),
    INSTAGRAM(true),
    GOOGLE(true),
    WHATSAPP(true),
    PHONE(true),
    FRONT_DESK(true),
    WALK_IN(true);

    private final boolean staff;

    BookingChannel(boolean staff) {
        this.staff = staff;
    }

    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static BookingChannel of(String value) {
        return Arrays.stream(values())
                .filter(channel -> channel.value().equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("not a booking channel"));
    }

    /** A channel staff may record: every one but the app's two. */
    public static Optional<BookingChannel> staff(String value) {
        return Arrays.stream(values())
                .filter(channel -> channel.staff && channel.value().equals(value))
                .findFirst();
    }
}
