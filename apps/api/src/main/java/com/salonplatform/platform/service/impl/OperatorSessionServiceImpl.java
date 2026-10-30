package com.salonplatform.platform.service.impl;

import static com.salonplatform.platform.service.impl.OperatorAccess.invalidSession;

import com.salonplatform.platform.adapter.OperatorCrypto;
import com.salonplatform.platform.dto.OperatorPrincipal;
import com.salonplatform.platform.dto.OperatorTokens;
import com.salonplatform.platform.repository.OperatorSessionStore;
import com.salonplatform.platform.repository.OperatorSessionStore.Session;
import com.salonplatform.platform.repository.OperatorUserStore;
import com.salonplatform.platform.repository.PlatformAudit;
import com.salonplatform.platform.service.OperatorSessionService;
import com.salonplatform.platform.service.impl.OperatorAccess.Operator;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class OperatorSessionServiceImpl implements OperatorSessionService {

    private final OperatorWork work;
    private final OperatorAccess access;
    private final OperatorSessionStore sessions;
    private final OperatorUserStore users;
    private final OperatorCrypto crypto;
    private final OperatorSessionTokens tokens;
    private final PlatformAudit audit;
    private final Clock clock;

    OperatorSessionServiceImpl(
            OperatorWork work,
            OperatorAccess access,
            OperatorSessionStore sessions,
            OperatorUserStore users,
            OperatorCrypto crypto,
            OperatorSessionTokens tokens,
            PlatformAudit audit,
            Clock clock) {
        this.work = work;
        this.access = access;
        this.sessions = sessions;
        this.users = users;
        this.crypto = crypto;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    public OperatorTokens refresh(String refreshToken) {
        return work.run(() -> {
            Instant now = clock.instant();
            String hash = crypto.hash("refresh", refreshToken == null ? "" : refreshToken);
            Optional<Session> current = sessions.lockByRefreshHash(hash);
            if (current.isEmpty()) {
                for (UUID id : sessions.sessionsThatSpent(hash)) {
                    if (sessions.revoke(id, now)) {
                        audit.record(
                                PlatformAudit.SYSTEM,
                                "refresh",
                                "operator.refresh_replayed",
                                "operator_session",
                                id.toString(),
                                null,
                                now);
                    }
                }
                throw invalidSession();
            }
            Session session = current.get();
            if (!access.live(session, users.findById(session.userId()), now)) {
                throw invalidSession();
            }
            String next = crypto.randomToken();
            sessions.rotate(session.id(), hash, crypto.hash("refresh", next), now);
            audit.record(
                    PlatformAudit.OPERATOR,
                    session.userId().toString(),
                    "operator.session_renewed",
                    "operator_session",
                    session.id().toString(),
                    null,
                    now);
            return tokens.tokens(session.userId(), session.id(), session.generation() + 1, next, session.expiresAt());
        });
    }

    @Override
    public void signOut() {
        work.run(() -> {
            Operator operator = access.authenticated();
            Instant now = clock.instant();
            sessions.revoke(operator.sessionId(), now);
            audit.record(
                    PlatformAudit.OPERATOR,
                    operator.id().toString(),
                    "operator.signed_out",
                    "operator_session",
                    operator.sessionId().toString(),
                    null,
                    now);
            return null;
        });
    }

    @Override
    public OperatorPrincipal me() {
        return work.run(() -> {
            Operator operator = access.require(com.salonplatform.platform.domain.OperatorPermission.SESSION_MANAGE);
            return new OperatorPrincipal(
                    operator.id(),
                    OperatorCrypto.KIND,
                    operator.role().name(),
                    operator.role().permissions().stream().sorted().toList(),
                    true);
        });
    }
}
