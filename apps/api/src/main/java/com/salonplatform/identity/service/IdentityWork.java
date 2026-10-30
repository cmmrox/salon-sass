package com.salonplatform.identity.service;

import java.util.function.Supplier;

/**
 * One tenant transaction per identity use case, holding the salon's identity lock. A refused sign-in, code or
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
}
