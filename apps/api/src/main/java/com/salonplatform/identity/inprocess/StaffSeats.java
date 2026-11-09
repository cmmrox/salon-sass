package com.salonplatform.identity.inprocess;

import java.util.UUID;

/**
 * The staff-seat rule, offered to {@code staff} (PLT-04, decisions 099 and 100): seats used are the salon's active
 * staff accounts plus its active team members with no linked account, under the salon's identity lock, the same
 * lock every identity use case takes.
 *
 * <p>In-process only: the count and the write that takes the seat must commit together under one lock, so every
 * method joins the caller's writable tenant transaction and the lock is held until that transaction ends. As
 * separate transactions, two simultaneous requests could both take the last seat. If {@code identity} or
 * {@code staff} is extracted, this becomes a reservation with a compensating event, not a remote call.
 *
 * <p>{@link #hold()} comes first, before the transaction touches any row of the caller's module. That one order
 * everywhere is what keeps a request adding a member and a request creating or removing an account from
 * deadlocking: identity takes the lock and then reaches {@code staff}'s rows through {@link TeamMemberSeats}.
 */
public interface StaffSeats {

    /**
     * Takes the salon's identity lock in the caller's transaction, until that transaction ends.
     *
     * @throws IllegalStateException outside a writable tenant transaction
     */
    void hold();

    /**
     * Refuses when the salon has no free seat. A salon with no plan is not limited. Share-locks the salon's registry
     * row until the caller's transaction ends, so that a plan move cannot interleave with it. Call it after
     * {@link #hold()} and before the write that takes the seat.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 409 {@code entitlement_exceeded} naming
     *     {@code staffSeats}
     * @throws IllegalStateException outside a writable tenant transaction
     */
    void requireFreeSeat();

    /**
     * Whether the id names an active staff account of the current salon, read in the caller's transaction. Another
     * salon's account, a removed one and an unknown id all answer {@code false}. Call it after {@link #hold()}, so
     * that the account cannot be removed before the caller commits.
     */
    boolean isActiveAccount(UUID accountId);
}
