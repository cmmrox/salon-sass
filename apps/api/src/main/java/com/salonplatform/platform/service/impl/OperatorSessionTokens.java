package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.dto.OperatorTokens;
import com.salonplatform.platform.repository.OperatorSessionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * An operator session's credentials (docs/architecture/operator-console.md, "Operator authentication"): access
 * lasts five minutes and a session seven days from sign-in. Runs inside the caller's platform transaction.
 */
@Component
class OperatorSessionTokens {

    static final Duration SESSION_LIFETIME = Duration.ofDays(7);

    private final OperatorSessionStore sessions;
    private final OperatorCrypto crypto;

    OperatorSessionTokens(OperatorSessionStore sessions, OperatorCrypto crypto) {
        this.sessions = sessions;
        this.crypto = crypto;
    }

    OperatorTokens newSession(UUID operator, String label, Instant now) {
        UUID id = UUID.randomUUID();
        String refresh = crypto.randomToken();
        Instant expires = now.plus(SESSION_LIFETIME);
        sessions.create(id, operator, crypto.hash("refresh", refresh), label, expires, now);
        return tokens(operator, id, 1, refresh, expires);
    }

    OperatorTokens tokens(UUID operator, UUID session, long generation, String refresh, Instant expires) {
        Instant issued = crypto.issuedNow();
        return new OperatorTokens(
                "authenticated",
                session,
                crypto.access(operator, session, generation, issued),
                refresh,
                issued.plusSeconds(300),
                expires);
    }

    void revokeAll(UUID operator, Instant now) {
        sessions.revokeAllOf(operator, now);
    }
}
