package com.salonplatform.booking.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The states of a hold or a booking (BKG-06, decision 075): the eight a caller sees, and the two that end a hold,
 * {@link #RELEASED} and {@link #EXPIRED}, after which the row is never shown again and occupies nothing (F07-S01 AC-5).
 */
public enum BookingState {
    HELD,
    PENDING_PAYMENT,
    CONFIRMED,
    ARRIVED,
    IN_SERVICE,
    COMPLETED,
    CANCELLED,
    NO_SHOW,
    RELEASED,
    EXPIRED;

    /** The states whose time occupies the professional: the exclusion constraint's predicate (AC-3). */
    public static final Set<BookingState> LIVE =
            Set.of(HELD, PENDING_PAYMENT, CONFIRMED, ARRIVED, IN_SERVICE, COMPLETED);

    /** The states a caller may see; a hold past its expiry is not seen either, though it is still {@link #HELD}. */
    public static final Set<BookingState> SHOWN =
            Set.of(HELD, PENDING_PAYMENT, CONFIRMED, ARRIVED, IN_SERVICE, COMPLETED, CANCELLED, NO_SHOW);

    /**
     * The states of a booking that is still expected to take place: what the guards and the affected-booking
     * answers of {@code staff} and {@code catalogue} count (AC-13). Holds and pending payments count; a booking that
     * is completed, cancelled or a no-show does not.
     */
    public static final Set<BookingState> EXPECTED = Set.of(HELD, PENDING_PAYMENT, CONFIRMED, ARRIVED, IN_SERVICE);

    /** The states "Most booked" counts (CAT-06): bookings that were made, not holds or ones cancelled or missed. */
    public static final Set<BookingState> BOOKED = Set.of(CONFIRMED, ARRIVED, IN_SERVICE, COMPLETED);

    /** The name stored and shown: {@code pending_payment}. */
    public String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static BookingState of(String value) {
        return parse(value).orElseThrow(() -> new IllegalArgumentException("not a booking state"));
    }

    /** A state a caller may name, such as a list's filter; the two ended-hold states are not among them. */
    public static Optional<BookingState> parseShown(String value) {
        return parse(value).filter(SHOWN::contains);
    }

    private static Optional<BookingState> parse(String value) {
        return Arrays.stream(values())
                .filter(state -> state.value().equals(value))
                .findFirst();
    }
}
