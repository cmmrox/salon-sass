package com.salonplatform.platform.application;

import static com.salonplatform.platform.application.OperatorAccess.invalidSession;
import static com.salonplatform.platform.application.OperatorAccess.session;
import static com.salonplatform.platform.application.OperatorAccess.time;

import com.salonplatform.platform.application.OperatorAccess.Operator;
import com.salonplatform.platform.application.OperatorAccess.Session;
import com.salonplatform.platform.infrastructure.OperatorCrypto;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final PlatformDatabase database;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final OperatorCrypto crypto;
    private final PlatformAudit audit;
    private final Clock clock;

    public OperatorSessions(
            PlatformDatabase database,
            OperatorWork work,
            OperatorAccess access,
            OperatorCrypto crypto,
            PlatformAudit audit,
            Clock clock) {
        this.database = database;
        this.work = work;
        this.access = access;
        this.crypto = crypto;
        this.audit = audit;
        this.clock = clock;
    }

    private JdbcTemplate jdbc() {
        return database.jdbc();
    }

    OperatorTokens newSession(UUID operator, String label, Instant now) {
        UUID id = UUID.randomUUID();
        String refresh = crypto.randomToken();
        Instant expires = now.plus(SESSION_LIFETIME);
        jdbc().update("""
                INSERT INTO operator_session (id, user_id, refresh_hash, device_label, mfa_verified, generation,
                    expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, true, 1, ?, ?, ?)
                """, id, operator, crypto.hash("refresh", refresh), label, time(expires), time(now), time(now));
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
        jdbc().update(
                        "UPDATE operator_session SET revoked_at = ?, updated_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                        time(now),
                        time(now),
                        operator);
    }

    /**
     * Rotates a refresh credential once. A spent credential revokes its whole family, so of two
     * simultaneous renewals at most one succeeds and a replay can never leave a usable family.
     */
    public OperatorTokens refresh(String refreshToken) {
        return work.run(() -> {
            Instant now = clock.instant();
            String hash = crypto.hash("refresh", refreshToken == null ? "" : refreshToken);
            Optional<Session> current =
                    jdbc().query("""
                    SELECT id, user_id, mfa_verified, generation, expires_at, revoked_at
                      FROM operator_session WHERE refresh_hash = ? FOR UPDATE
                    """, (row, n) -> session(row), hash).stream().findFirst();
            if (current.isEmpty()) {
                List<UUID> family = jdbc().queryForList(
                                "SELECT session_id FROM operator_refresh_history WHERE credential_hash = ?",
                                UUID.class,
                                hash);
                for (UUID id : family) {
                    int revoked = jdbc().update("""
                            UPDATE operator_session SET revoked_at = ?, updated_at = ? WHERE id = ? AND revoked_at IS NULL
                            """, time(now), time(now), id);
                    if (revoked > 0) {
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
            if (!access.live(session, access.account("id", session.userId()), now)) {
                throw invalidSession();
            }
            String next = crypto.randomToken();
            jdbc().update("""
                    INSERT INTO operator_refresh_history (id, session_id, credential_hash, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), session.id(), hash, time(now), time(now));
            jdbc().update("""
                    UPDATE operator_session SET refresh_hash = ?, generation = generation + 1, updated_at = ? WHERE id = ?
                    """, crypto.hash("refresh", next), time(now), session.id());
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
            jdbc().update(
                            "UPDATE operator_session SET revoked_at = ?, updated_at = ? WHERE id = ? AND revoked_at IS NULL",
                            time(now),
                            time(now),
                            operator.sessionId());
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
