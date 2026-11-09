package com.salonplatform.identity.inprocess;

import java.time.Instant;
import java.util.UUID;

/**
 * What {@code identity} asks of {@code staff} about seats and links (decisions 099 and 100). Declared here and
 * implemented by {@code staff}, so that dependencies stay one way ({@code staff} depends on {@code identity}), and
 * {@code identity} works with no implementation registered: its seats are then its active accounts alone.
 *
 * <p>In-process only: both methods join the caller's identity transaction, which already holds the salon's identity
 * lock. A count taken apart from the write that takes the seat could let two simultaneous requests both take the
 * last seat, and an account must leave its member in the transaction that removes it. If either module is extracted,
 * the count becomes a value {@code identity} keeps from {@code staff}'s events and the clearing an event with a
 * compensating action, not remote calls.
 */
public interface TeamMemberSeats {

    /** How many active team members of the current salon have no linked account: one seat each. */
    long activeMembersWithoutAccount();

    /**
     * Clears the link of the active member holding this account, if any, keeping the member on the team, and writes
     * that to the salon's audit trail.
     *
     * @param accountId the account being removed
     * @param removedBy the staff account removing it, or {@code null} when a local test hook empties the salon
     * @param at when, from the application's clock
     */
    void accountRemoved(UUID accountId, UUID removedBy, Instant at);
}
