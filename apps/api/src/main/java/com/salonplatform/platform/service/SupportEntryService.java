package com.salonplatform.platform.service;

import com.salonplatform.platform.dto.SupportCredential;

/**
 * The support session itself, on the salon's host (PLT-07; docs/architecture/operator-console.md,
 * "Support sessions"): an entry code is exchanged once for the read-only support credential, the
 * session can end itself, and every request's credential is checked against the stored session, so an
 * end takes effect on the next request.
 */
public interface SupportEntryService {

    /**
     * Exchanges an entry code for the support credential: once, within its 60 seconds, on the host of
     * the salon it was issued for, while its session is open. Anything else is 401 {@code invalid_code}
     * and issues nothing.
     */
    SupportCredential enter(String code);

    /** The support session ends itself (the operator's End session in the workspace). */
    void endCurrent();
}
