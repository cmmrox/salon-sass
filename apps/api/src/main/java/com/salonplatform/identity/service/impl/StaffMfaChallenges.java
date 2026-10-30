package com.salonplatform.identity.service.impl;

import com.salonplatform.identity.adapter.IdentityCrypto;
import com.salonplatform.identity.dto.SignIn;
import com.salonplatform.identity.entity.AccessSession;
import com.salonplatform.identity.entity.MfaChallenge;
import com.salonplatform.identity.entity.StaffAccount;
import com.salonplatform.identity.repository.MfaChallengeRepository;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** The single-use challenges of a staff member's second step, issued after a password sign-in or from a session. */
@Component
@RequiredArgsConstructor
class StaffMfaChallenges {

    private final MfaChallengeRepository challenges;
    private final IdentityCrypto crypto;
    private final Clock clock;

    /**
     * A new challenge, whose token is handed to the caller once and kept only as a hash.
     *
     * @param session the session that asked for it, or null after a password sign-in
     */
    SignIn issue(StaffAccount account, AccessSession session, String purpose, String deviceLabel) {
        String token = crypto.randomToken();
        MfaChallenge challenge = challenges.persist(MfaChallenge.issue(
                account.getId(),
                session == null ? null : session.getId(),
                purpose,
                deviceLabel,
                crypto.hash("challenge", token),
                clock.instant()));
        return new SignIn(purpose, challenge.getId(), token, challenge.getExpiresAt(), null);
    }
}
