package com.salonplatform.identity.service;

import com.salonplatform.identity.Principal;
import com.salonplatform.identity.dto.Page;
import com.salonplatform.identity.dto.SessionView;
import com.salonplatform.identity.dto.Tokens;
import java.util.UUID;

/**
 * A staff member's or customer's sessions (docs/architecture/identity-and-access.md): one session mechanism for
 * both principal kinds, whose kind and owner are checked for every use case.
 */
public interface SessionService {

    /**
     * Who the current request acts as: its staff or customer session, or the read-only principal of a support
     * session.
     *
     * @throws com.salonplatform.shared.web.ApplicationProblemException 401 {@code invalid_session} when the request
     *     carries no live session of the hostname's salon
     */
    Principal me();

    /**
     * Exchanges a refresh credential for the session's next one and a new access token. Presenting a spent
     * credential again revokes its whole session.
     */
    Tokens refresh(String refreshToken);

    /** The live sessions of the signed-in staff member or customer, the current one marked. */
    Page<SessionView> sessions(int page, int size);

    /** Signs out one of the signed-in person's own sessions. */
    void revoke(UUID sessionId);

    /** Signs out every session of the signed-in person. */
    void revokeAll();

    /**
     * Opens a session for a customer whose sign-in has just been verified, in the caller's identity transaction.
     */
    Tokens openCustomerSession(UUID customerAccountId, String deviceLabel);
}
