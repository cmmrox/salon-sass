package com.salonplatform.identity.service;

import java.util.function.Supplier;

/**
 * One tenant transaction per identity use case: holding the salon's identity lock for a use case that changes
 * something ({@link #run}), none for one that only reads ({@link #read}). A refused sign-in, code or
 * rate limit (401 or 429) commits its counters, consumption and audit before the error is answered, so that a
 * failed HTTP outcome can never undo those controls; every other failure rolls the whole transaction back.
 */
public interface IdentityWork {

    /**
     * Runs the use case in the current salon's identity transaction.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException the use case's refusal, after a 401 or
     *     429 refusal's transaction has committed
     */
    <T> T run(Supplier<T> useCase);

    /**
     * Runs a use case that only reads (who the request is, a list) in a read-only tenant transaction that takes
     * no lock and writes nothing (decision 120): authenticating a request never queues behind the salon's other
     * identity work, and never serialises the salon's requests on one row. The session and its account are still
     * read from the database every time, so a session revoked, or a member removed, by a transaction that has
     * committed is refused on the next request. Called inside another transaction, it joins it.
     */
    <T> T read(Supplier<T> useCase);
}
