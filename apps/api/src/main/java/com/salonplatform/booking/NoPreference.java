package com.salonplatform.booking;

import java.util.Arrays;
import java.util.Optional;

/**
 * Which eligible, free professional a "No preference" booking goes to (BKG-03, decision 108(a)): of those who perform
 * the service and are free for the whole booking, the one with the fewest or the most minutes of live bookings and
 * unexpired holds that local date. Ties go to the one the Team page lists first.
 */
public enum NoPreference {
    /** Whoever has the least booked that day: work is shared evenly. The default. */
    LEAST_BOOKED("least_booked"),
    /** Whoever has the most booked that day: one person's day fills first, keeping others free for walk-ins. */
    MOST_BOOKED("most_booked");

    private final String value;

    NoPreference(String value) {
        this.value = value;
    }

    /** As the API and the database write it. */
    public String value() {
        return value;
    }

    /** The strategy a written value names, if any. */
    public static Optional<NoPreference> parse(String value) {
        return Arrays.stream(values()).filter(each -> each.value.equals(value)).findFirst();
    }
}
