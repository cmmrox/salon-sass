package com.salonplatform.platform.application;

import com.salonplatform.platform.domain.OperatorRole;
import com.salonplatform.platform.infrastructure.OperatorCrypto;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.tenancy.RequestCredential;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Operator authorization (docs/architecture/operator-console.md): the operator of the current request's
 * bearer credential, and the permission a use case requires. Account activity, role, MFA and revocation
 * are read from the database on every protected action. Sign-in is {@link OperatorSignIns}, sessions
 * {@link OperatorSessions}.
 */
@Service
public class OperatorAccess {

    /** The authenticated operator of the current request, inside its transaction. */
    public record Operator(UUID id, OperatorRole role, UUID sessionId) {}

    record Account(UUID id, String email, String passwordHash, String role, boolean active, String mfaSecret) {}

    record Session(UUID id, UUID userId, boolean mfaVerified, long generation, Instant expiresAt, Instant revokedAt) {}

    private final PlatformDatabase database;
    private final OperatorWork work;
    private final OperatorCrypto crypto;
    private final Clock clock;

    public OperatorAccess(PlatformDatabase database, OperatorWork work, OperatorCrypto crypto, Clock clock) {
        this.database = database;
        this.work = work;
        this.crypto = crypto;
        this.clock = clock;
    }

    private JdbcTemplate jdbc() {
        return database.jdbcTemplate();
    }

    static ApplicationProblemException invalidCredentials() {
        return new ApplicationProblemException(401, ErrorCode.INVALID_CREDENTIALS);
    }

    static ApplicationProblemException invalidSession() {
        return new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
    }

    static Instant instant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        throw new IllegalStateException("Unexpected time value");
    }

    static Timestamp time(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    Optional<Account> account(String column, Object value) {
        return jdbc()
                .query(
                        "SELECT id, email, password_hash, role, active, mfa_secret FROM operator_user WHERE "
                                + ("email".equals(column) ? "email" : "id") + " = ?",
                        (row, n) -> new Account(
                                row.getObject("id", UUID.class),
                                row.getString("email"),
                                row.getString("password_hash"),
                                row.getString("role"),
                                row.getBoolean("active"),
                                row.getString("mfa_secret")),
                        value)
                .stream()
                .findFirst();
    }

    static Session session(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Session(
                row.getObject("id", UUID.class),
                row.getObject("user_id", UUID.class),
                row.getBoolean("mfa_verified"),
                row.getLong("generation"),
                instant(row.getObject("expires_at")),
                instant(row.getObject("revoked_at")));
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
        Session session = jdbc().query("""
                SELECT id, user_id, mfa_verified, generation, expires_at, revoked_at FROM operator_session WHERE id = ?
                """, (row, n) -> session(row), claims.sessionId()).stream()
                .findFirst()
                .orElseThrow(OperatorAccess::invalidSession);
        Optional<Account> account = account("id", session.userId());
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
