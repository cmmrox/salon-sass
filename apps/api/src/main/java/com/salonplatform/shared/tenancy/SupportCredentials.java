package com.salonplatform.shared.tenancy;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Verifies a support credential (F03-S04) for the request filter. Platform implements this seam: the
 * credential is minted by platform and checked against its live support session on every request.
 */
public interface SupportCredentials {

    /**
     * The support session behind a bearer credential presented on a salon's hostname.
     *
     * @param credential the bearer credential
     * @param salonId the salon the hostname names, asked only once the credential is known to be a
     *        support credential (it may refuse: no such salon, a suspended one)
     * @return empty when the credential is not a support credential at all
     * @throws com.salonplatform.shared.api.NotFoundException when it is one for another salon
     * @throws com.salonplatform.shared.api.ApplicationProblemException 401 {@code invalid_session} when
     *         its session has ended, expired or cannot be found
     */
    Optional<SupportGrant> verify(String credential, Supplier<UUID> salonId);
}
