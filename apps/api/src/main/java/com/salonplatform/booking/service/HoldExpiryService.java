package com.salonplatform.booking.service;

import java.time.Instant;

/**
 * Ending holds whose expiry has passed (BKG-04, AC-6), for the recurring job. A hold waiting for its payment
 * ({@code pending_payment}) is never ended. Nothing depends on when it runs: a hold that has run out already occupies
 * nothing and is shown to no one.
 */
public interface HoldExpiryService {

    /**
     * Ends the current salon's run-out holds, as the system, each with its history and audit entry. Joins the caller's
     * tenant transaction: the scheduler's, for one salon.
     *
     * @return how many it ended
     */
    int endRunOutHolds(Instant now);
}
