package com.salonplatform.shared.attempts;

import java.time.Duration;
import java.time.Instant;

/**
 * Counts an address's failed attempts at something across every salon (decision 120). The address is never stored:
 * the caller passes its own keyed hash of it, the same whichever salon the attempt was for. An attempt is counted
 * when it starts, so that it counts even if what follows fails, and an attempt that succeeds is given back.
 */
public interface AddressAttempts {

    /** An attempt as counted: the attempts in its window, this one included, and when it was counted. */
    record Counted(int attempts, Instant at) {}

    /**
     * Counts one attempt. One atomic statement, so simultaneous attempts are counted as the database orders them. It
     * joins the caller's writable transaction, so that a refused attempt's count commits with the caller's other
     * counters, and refuses to run outside one.
     *
     * @param purpose what is attempted, such as {@code staff-sign-in}: lowercase letters, digits and hyphens
     * @param addressHash the caller's keyed hash of the address
     * @param window how long a window lasts from its first attempt
     */
    Counted count(String purpose, String addressHash, Duration window);

    /**
     * Gives back an attempt that succeeded: one atomic statement on that address's counter only, never below zero,
     * and only while the window it was counted in lasts. Joins the caller's writable transaction, and refuses to run
     * outside one.
     */
    void giveBack(String purpose, String addressHash, Counted counted);
}
