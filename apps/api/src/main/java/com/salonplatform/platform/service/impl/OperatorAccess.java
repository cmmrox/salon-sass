package com.salonplatform.platform.service.impl;

import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.domain.OperatorRole;
import com.salonplatform.platform.repository.OperatorSessionStore;
import com.salonplatform.platform.repository.OperatorSessionStore.Session;
import com.salonplatform.platform.repository.OperatorUserStore;
import com.salonplatform.platform.repository.OperatorUserStore.Account;
import com.salonplatform.platform.service.OperatorSessionService;
import com.salonplatform.platform.service.OperatorSignInService;
import com.salonplatform.shared.persistence.PlatformDatabase;
import com.salonplatform.shared.tenancy.RequestCredential;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Operator authorization (docs/architecture/operator-console.md): the operator of the current request's
 * bearer credential, and the permission a use case requires. Account activity, role, MFA and revocation
 * are read from the database on every protected action. Sign-in is {@link OperatorSignInService}, sessions
 * {@link OperatorSessionService}.
 */
@Component
class OperatorAccess {

    /** The authenticated operator of the current request, inside its transaction. */
    public record Operator(UUID id, OperatorRole role, UUID sessionId) {}

    private final PlatformDatabase database;
    private final OperatorWork work;
    private final OperatorUserStore users;
    private final OperatorSessionStore sessions;
    private final OperatorCrypto crypto;
    private final Clock clock;

    public OperatorAccess(
            PlatformDatabase database,
            OperatorWork work,
            OperatorUserStore users,
            OperatorSessionStore sessions,
            OperatorCrypto crypto,
            Clock clock) {
        this.database = database;
        this.work = work;
        this.users = users;
        this.sessions = sessions;
        this.crypto = crypto;
        this.clock = clock;
    }

    static ApplicationProblemException invalidCredentials() {
        return new ApplicationProblemException(401, ErrorCode.INVALID_CREDENTIALS);
    }

    static ApplicationProblemException invalidSession() {
        return new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
    }

    boolean live(Session session, Optional<Account> account, Instant now) {
        return session.revokedAt() == null
                && now.isBefore(session.expiresAt())
                && session.mfaVerified()
                && account.isPresent()
                && account.get().active()
                && account.get().mfaSecret() != null
                && OperatorRole.of(account.get().role()).isPresent();
    }

    // --- authorization ---------------------------------------------------------------------------

    /**
     * The operator of the current request's bearer credential, read from the database: a live, MFA
     * verified session of the generation the token names, of an active operator with a known role.
     * Call inside a platform transaction.
     *
     * @throws ApplicationProblemException 401 {@code invalid_session}
     */
    public Operator authenticated() {
        database.requireTransaction();
        OperatorCrypto.Claims claims = crypto.verify(RequestCredential.require());
        Session session = sessions.findById(claims.sessionId()).orElseThrow(OperatorAccess::invalidSession);
        Optional<Account> account = users.findById(session.userId());
        if (!session.userId().equals(claims.operatorId())
                || session.generation() != claims.generation()
                || !live(session, account, clock.instant())) {
            throw invalidSession();
        }
        return new Operator(
                account.get().id(), OperatorRole.of(account.get().role()).orElseThrow(), session.id());
    }

    /**
     * The authenticated operator, refused 403 {@code permission_denied} without the permission. Call
     * inside a platform transaction, before any change.
     */
    public Operator require(String permission) {
        Operator operator = authenticated();
        if (!operator.role().permissions().contains(permission)) {
            throw new ApplicationProblemException(403, ErrorCode.PERMISSION_DENIED);
        }
        return operator;
    }

    /** Checks the permission in a transaction of its own, before work done outside one (password hashing). */
    public Operator check(String permission) {
        return work.run(() -> require(permission));
    }
}
