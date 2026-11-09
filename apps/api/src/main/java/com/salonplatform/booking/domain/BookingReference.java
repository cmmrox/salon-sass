package com.salonplatform.booking.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * A booking's reference (BKG-14, decision 108(d)): six characters drawn at random from an alphabet with no letter or
 * digit that reads like another (no 0, O, 1, I or L), so it can be read aloud and reveals nothing about how many
 * bookings a salon has. Stored as the six characters, shown with a hyphen after the third: {@code K7M-4QX}.
 */
public final class BookingReference {

    public static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    public static final int LENGTH = 6;

    private BookingReference() {}

    /** A new reference, drawn from the generator. */
    public static String draw(RandomGenerator random) {
        StringBuilder reference = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            reference.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return reference.toString();
    }

    /** {@code K7M4QX} as it is shown: {@code K7M-4QX}. */
    public static String shown(String stored) {
        return stored.substring(0, 3) + "-" + stored.substring(3);
    }

    /**
     * What a search for a reference names, whatever its case and with or without its hyphen; empty when the text
     * cannot be a reference.
     */
    public static Optional<String> searched(String text) {
        String compact = text.strip().toUpperCase(Locale.ROOT);
        if (compact.length() == LENGTH + 1 && compact.charAt(3) == '-') {
            compact = compact.substring(0, 3) + compact.substring(4);
        }
        if (compact.length() != LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < LENGTH; i++) {
            if (ALPHABET.indexOf(compact.charAt(i)) < 0) {
                return Optional.empty();
            }
        }
        return Optional.of(compact);
    }
}
