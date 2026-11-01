package com.salonplatform.platform.inprocess;

import java.util.UUID;

/**
 * What another module adds to a salon's staff-seat usage, beside its active accounts (PLT-04, decisions 099 and 100):
 * {@code staff}'s active team members with no linked account. Platform adds every implementation's answer for
 * {@code usage.staffSeats} and for the check on a plan move, so that both report the count {@code identity} enforces.
 *
 * <p>In-process only: the answer is read in the caller's platform transaction, which holds the salon's registry row
 * during a plan move, so that it cannot interleave with a request taking a seat, which holds that row for share. It
 * must use the platform connection and its module's own {@code SECURITY DEFINER} function. If a module is extracted,
 * this becomes a value platform keeps from that module's events, not a remote call.
 */
public interface SalonSeatUsage {

    /**
     * The seats this module's records take in the salon, in the caller's platform transaction: a count and nothing
     * else.
     *
     * @throws IllegalStateException outside a platform transaction
     */
    long seatsUsed(UUID tenantId);
}
