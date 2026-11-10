package com.salonplatform.shared.attempts;

import java.time.Duration;

/**
 * Counts an address's attempts at something across every salon (decision 120). The address is never stored: the
 * caller passes its own keyed hash of it, the same whichever salon the attempt was for.
 */
public interface AddressAttempts {

    /**
     * Counts one attempt and returns the attempts in its window, this one included. One atomic statement, so
     * simultaneous attempts are counted as the database orders them. It joins the caller's writable transaction, so
     * that a refused attempt's count commits with the caller's other counters, and refuses to run outside one.
     *
     * @param purpose what is attempted, such as {@code staff-sign-in}: lowercase letters, digits and hyphens
     * @param addressHash the caller's keyed hash of the address
     * @param window how long a window lasts from its first attempt
     */
    int count(String purpose, String addressHash, Duration window);
}
