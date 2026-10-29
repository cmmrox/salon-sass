package com.salonplatform.platform.application;

import static com.salonplatform.platform.application.OperatorAccess.invalidSession;

import com.salonplatform.platform.application.OperatorAccess.Operator;
import com.salonplatform.platform.infrastructure.OperatorCrypto;
import com.salonplatform.platform.infrastructure.OperatorSessionStore;
import com.salonplatform.platform.infrastructure.OperatorSessionStore.Session;
import com.salonplatform.platform.infrastructure.OperatorUserStore;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * An operator's console sessions (docs/architecture/operator-console.md, "Operator authentication"):
 * access lasts five minutes and a session seven days from sign-in; refresh rotates once and a spent
 * credential revokes its family.
 */
@Service
public class OperatorSessions {

    static final Duration SESSION_LIFETIME = Duration.ofDays(7);

    @Schema(
            requiredProperties = {
                "state",
                "sessionId",
                "accessToken",
                "refreshToken",
                "accessExpiresAt",
                "refreshExpiresAt"
            })
    public record OperatorTokens(
            String state,
            UUID sessionId,
            String accessToken,
            String refreshToken,
            Instant accessExpiresAt,
            Instant refreshExpiresAt) {}

    @Schema(requiredProperties = {"id", "kind", "role", "permissions", "mfaEnabled"})
    public record OperatorPrincipal(UUID id, String kind, String role, List<String> permissions, boolean mfaEnabled) {}

    private final OperatorWork work;
    private final OperatorAccess access;
    private final OperatorSessionStore sessions;
    private final OperatorUserStore users;
    private final OperatorCrypto crypto;
    private final PlatformAudit audit;
    private final Clock clock;

    public OperatorSessions(
            OperatorWork work,
            OperatorAccess access,
            OperatorSessionStore sessions,
            OperatorUserStore users,
            OperatorCrypto crypto,
            PlatformAudit audit,
            Clock clock) {
        this.work = work;
        this.access = access;
        this.sessions = sessions;
        this.users = users;
        this.crypto = crypto;
        this.audit = audit;
        this.clock = clock;
    }

    OperatorTokens newSession(UUID operator, String label, Instant now) {
        UUID id = UUID.randomUUID();
        String refresh = crypto.randomToken();
        Instant expires = now.plus(SESSION_LIFETIME);
        sessions.create(id, operator, crypto.hash("refresh", refresh), label, expires, now);
        return tokens(operator, id, 1, refresh, expires);
    }

    private OperatorTokens tokens(UUID operator, UUID session, long generation, String refresh, Instant expires) {
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

    /**
     * Rotates a refresh credential once. A spent credential revokes its whole family, so of two
     * simultaneous renewals at most one succeeds and a replay can never leave a usable family.
     */
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
            return tokens(session.userId(), session.id(), session.generation() + 1, next, session.expiresAt());
        });
    }

    /** Revokes the current session: no access or refresh credential of it works afterwards. */
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

    /** The current operator: no tenant identity, permissions from the role as stored now. */
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
