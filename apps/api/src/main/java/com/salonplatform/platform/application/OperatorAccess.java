package com.salonplatform.platform.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.salonplatform.platform.domain.OperatorRole;
import com.salonplatform.platform.domain.PlainText;
import com.salonplatform.platform.infrastructure.OperatorCrypto;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.api.ApplicationProblemException;
import com.salonplatform.shared.api.ErrorCode;
import com.salonplatform.shared.api.FieldProblem;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.tenancy.RequestCredential;
import io.swagger.v3.oas.annotations.media.Schema;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Operator sign-in, MFA, sessions and authorization (docs/architecture/operator-console.md). Every
 * operator completes MFA (enrolling after a correct password on first sign-in) before any session
 * exists. Access lasts five minutes, sessions seven days from sign-in; refresh rotates once and a
 * spent credential revokes its family. Account activity, role, MFA and revocation are read from the
 * database on every protected action. Password attempts are limited per email and per trusted
 * address, unknown emails included, and every refusal commits its counters and audit.
 */
@Service
public class OperatorAccess {

    static final int EMAIL_CEILING = 10;
    static final int ADDRESS_CEILING = 30;
    static final Duration RATE_WINDOW = Duration.ofMinutes(15);
    static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    static final Duration SESSION_LIFETIME = Duration.ofDays(7);
    static final int CHALLENGE_ATTEMPTS = 5;

    @Schema(requiredProperties = {"state", "challengeId", "challengeToken", "expiresAt", "credentials"})
    public record OperatorSignIn(String state, @Schema(format = "uuid") UUID challengeId, String challengeToken,
            Instant expiresAt,
            @Schema(types = {"object", "null"}, requiredMode = Schema.RequiredMode.REQUIRED) OperatorTokens credentials) {
    }

    @Schema(requiredProperties = {"challengeId", "secret", "otpauthUri", "expiresAt"})
    public record OperatorEnrollment(UUID challengeId, String secret, String otpauthUri, Instant expiresAt) {
    }

    @Schema(requiredProperties = {"state", "sessionId", "accessToken", "refreshToken", "accessExpiresAt",
        "refreshExpiresAt"})
    public record OperatorTokens(String state, UUID sessionId, String accessToken, String refreshToken,
            Instant accessExpiresAt, Instant refreshExpiresAt) {
    }

    @Schema(requiredProperties = {"id", "kind", "role", "permissions", "mfaEnabled"})
    public record OperatorPrincipal(UUID id, String kind, String role, List<String> permissions, boolean mfaEnabled) {
    }

    /** The authenticated operator of the current request, inside its transaction. */
    public record Operator(UUID id, OperatorRole role, UUID sessionId) {
    }

    private record Account(UUID id, String email, String passwordHash, String role, boolean active, String mfaSecret) {
    }

    private record Challenge(UUID id, UUID userId, String purpose, String credentialHash, String pendingSecret,
            String deviceLabel, Instant expiresAt, int attempts, Instant consumedAt) {
    }

    private record Session(UUID id, UUID userId, boolean mfaVerified, long generation, Instant expiresAt,
            Instant revokedAt) {
    }

    private final PlatformDatabase database;
    private final OperatorWork work;
    private final OperatorCrypto crypto;
    private final PlatformAudit audit;
    private final Clock clock;

    public OperatorAccess(PlatformDatabase database, OperatorWork work, OperatorCrypto crypto, PlatformAudit audit,
            Clock clock) {
        this.database = database;
        this.work = work;
        this.crypto = crypto;
        this.audit = audit;
        this.clock = clock;
    }

    private JdbcTemplate jdbc() {
        return database.jdbc();
    }

    private static ApplicationProblemException invalidCredentials() {
        return new ApplicationProblemException(401, ErrorCode.INVALID_CREDENTIALS);
    }

    private static ApplicationProblemException invalidSession() {
        return new ApplicationProblemException(401, ErrorCode.INVALID_SESSION);
    }

    private static Instant instant(Object value) {
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

    private static Timestamp time(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    /** Plain text of 1 to 80 characters: a label for the operator's own session list, never trusted. */
    static String deviceLabel(String label) {
        String value = PlainText.line(label, 80);
        if (value == null) {
            throw new ApplicationProblemException(400, ErrorCode.BAD_REQUEST,
                    List.of(new FieldProblem("deviceLabel", "must be plain text between 1 and 80 characters")));
        }
        return value;
    }

    // --- sign-in ---------------------------------------------------------------------------------

    /**
     * Verifies the password and issues a five-minute single-use MFA challenge: {@code enroll_mfa} for an
     * operator without a secret, {@code verify_mfa} otherwise. Never a session.
     */
    public OperatorSignIn signIn(String email, String password, String label, String address) {
        String device = deviceLabel(label);
        return work.run(() -> {
            String key = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
            Instant now = clock.instant();
            Attempt byEmail = attempt("operator-email", key, EMAIL_CEILING, now);
            Attempt byAddress = attempt("operator-address", address == null ? "" : address, ADDRESS_CEILING, now);
            if (!byEmail.allowed() || !byAddress.allowed()) {
                audit.record(PlatformAudit.SYSTEM, "sign-in", "operator.sign_in_limited", "operator-email",
                        crypto.hash("audit", key), null, now);
                long retry = Math.max(byEmail.allowed() ? 0 : byEmail.retryAfter(),
                        byAddress.allowed() ? 0 : byAddress.retryAfter());
                throw new ApplicationProblemException(429, ErrorCode.RATE_LIMITED).retryAfter(Math.max(1, retry));
            }
            Optional<Account> found = account("email", key);
            boolean correct = crypto.matchesPassword(password, found.map(Account::passwordHash).orElse(null));
            if (found.isEmpty() || !found.get().active() || !correct
                    || OperatorRole.of(found.get().role()).isEmpty()) {
                audit.record(PlatformAudit.SYSTEM, "sign-in", "operator.sign_in_failed", "operator",
                        found.map(a -> a.id().toString()).orElseGet(() -> crypto.hash("audit", key)), null, now);
                throw invalidCredentials();
            }
            Account account = found.get();
            String purpose = account.mfaSecret() == null ? "enroll_mfa" : "verify_mfa";
            String token = crypto.randomToken();
            UUID challenge = UUID.randomUUID();
            Instant expires = now.plus(CHALLENGE_LIFETIME);
            jdbc().update("""
                    INSERT INTO operator_mfa_challenge (id, user_id, purpose, credential_hash, device_label, expires_at,
                        attempts, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """, challenge, account.id(), purpose, crypto.hash("challenge", token), device, time(expires),
                    time(now), time(now));
            audit.record(PlatformAudit.OPERATOR, account.id().toString(), "operator.mfa_challenge_issued",
                    "operator", account.id().toString(), Map.of("purpose", purpose), now);
            return new OperatorSignIn(purpose, challenge, token, expires, null);
        });
    }

    private record Attempt(boolean allowed, long retryAfter) {
    }

    /** One atomic count in a fifteen-minute window that starts on its first attempt. */
    private Attempt attempt(String dimension, String key, int ceiling, Instant now) {
        Map<String, Object> row = jdbc().queryForMap("""
                INSERT INTO operator_rate_bucket AS b (id, dimension, key_hash, window_start, attempts, created_at,
                    updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                ON CONFLICT (dimension, key_hash) DO UPDATE SET
                    window_start = CASE WHEN b.window_start + interval '15 minutes' <= EXCLUDED.window_start
                        THEN EXCLUDED.window_start ELSE b.window_start END,
                    attempts = CASE WHEN b.window_start + interval '15 minutes' <= EXCLUDED.window_start
                        THEN 1 ELSE b.attempts + 1 END,
                    updated_at = EXCLUDED.updated_at
                RETURNING attempts, window_start
                """, UUID.randomUUID(), dimension, crypto.hash("rate-" + dimension, key), time(now), time(now),
                time(now));
        int attempts = ((Number) row.get("attempts")).intValue();
        Instant windowEnd = instant(row.get("window_start")).plus(RATE_WINDOW);
        long remaining = (long) Math.ceil(Duration.between(now, windowEnd).toMillis() / 1000.0);
        return new Attempt(attempts <= ceiling, Math.max(1, remaining));
    }

    private Optional<Account> account(String column, Object value) {
        return jdbc().query("SELECT id, email, password_hash, role, active, mfa_secret FROM operator_user WHERE "
                        + ("email".equals(column) ? "email" : "id") + " = ?",
                (row, n) -> new Account(row.getObject("id", UUID.class), row.getString("email"),
                        row.getString("password_hash"), row.getString("role"), row.getBoolean("active"),
                        row.getString("mfa_secret")),
                value).stream().findFirst();
    }

    // --- MFA -------------------------------------------------------------------------------------

    private Challenge lockedChallenge(UUID id, String token) {
        Challenge challenge = jdbc().query("""
                SELECT id, user_id, purpose, credential_hash, pending_secret, device_label, expires_at, attempts,
                       consumed_at
                  FROM operator_mfa_challenge WHERE id = ? FOR UPDATE
                """, (row, n) -> new Challenge(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                row.getString("purpose"), row.getString("credential_hash"), row.getString("pending_secret"),
                row.getString("device_label"), instant(row.getObject("expires_at")), row.getInt("attempts"),
                instant(row.getObject("consumed_at"))), id).stream().findFirst().orElseThrow(OperatorAccess::invalidCredentials);
        if (!crypto.equalHash(challenge.credentialHash(), crypto.hash("challenge", token == null ? "" : token))
                || challenge.consumedAt() != null || !clock.instant().isBefore(challenge.expiresAt())
                || challenge.attempts() >= CHALLENGE_ATTEMPTS) {
            throw invalidCredentials();
        }
        return challenge;
    }

    private static String totpPurpose(UUID operator) {
        return "operator-totp:" + operator;
    }

    /**
     * The authenticator setup for an enrollment challenge only, after a correct password: the secret
     * and its {@code otpauth} URI, shown once to be copied. Repeated calls show the same pending secret.
     */
    public OperatorEnrollment enroll(UUID challengeId, String token) {
        return work.run(() -> {
            Challenge challenge = lockedChallenge(challengeId, token);
            Account account = account("id", challenge.userId()).filter(Account::active)
                    .orElseThrow(OperatorAccess::invalidCredentials);
            if (!"enroll_mfa".equals(challenge.purpose()) || account.mfaSecret() != null) {
                throw invalidCredentials();
            }
            String pending = challenge.pendingSecret();
            if (pending == null) {
                pending = crypto.encrypt(totpPurpose(account.id()), crypto.newTotpSecret());
                jdbc().update("UPDATE operator_mfa_challenge SET pending_secret = ?, updated_at = ? WHERE id = ?",
                        pending, time(clock.instant()), challenge.id());
            }
            String secret = crypto.decrypt(totpPurpose(account.id()), pending);
            String uri = "otpauth://totp/Salon%20Console:" + account.id() + "?secret=" + secret
                    + "&issuer=Salon%20Console&algorithm=SHA1&digits=6&period=30";
            return new OperatorEnrollment(challenge.id(), secret, uri, challenge.expiresAt());
        });
    }

    /**
     * Verifies a six-digit code against the challenge once: a consumed step, an expired, consumed or
     * exhausted challenge, or a wrong code issues nothing, and each attempt counts even when it fails.
     * Enrollment saves the secret and revokes any earlier session.
     */
    public OperatorTokens verify(UUID challengeId, String token, String code) {
        return work.run(() -> {
            Instant now = clock.instant();
            Challenge challenge = lockedChallenge(challengeId, token);
            jdbc().update("UPDATE operator_mfa_challenge SET attempts = attempts + 1, updated_at = ? WHERE id = ?",
                    time(now), challenge.id());
            Account account = account("id", challenge.userId()).filter(Account::active)
                    .filter(a -> OperatorRole.of(a.role()).isPresent()).orElseThrow(OperatorAccess::invalidCredentials);
            boolean enrolling = "enroll_mfa".equals(challenge.purpose());
            String encrypted = enrolling ? challenge.pendingSecret() : account.mfaSecret();
            if ((enrolling && account.mfaSecret() != null) || encrypted == null) {
                mfaFailed(account, challenge, now);
            }
            Long step = crypto.matchingStep(crypto.decrypt(totpPurpose(account.id()), encrypted), code);
            int consumed = step == null ? 0 : jdbc().update("""
                    INSERT INTO operator_mfa_step (id, user_id, step, created_at, updated_at) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (user_id, step) DO NOTHING
                    """, UUID.randomUUID(), account.id(), step, time(now), time(now));
            if (consumed == 0) {
                mfaFailed(account, challenge, now);
            }
            jdbc().update("UPDATE operator_mfa_challenge SET consumed_at = ?, updated_at = ? WHERE id = ?", time(now),
                    time(now), challenge.id());
            if (enrolling) {
                jdbc().update("""
                        UPDATE operator_user SET mfa_secret = ?, version = version + 1, updated_at = ?
                         WHERE id = ? AND mfa_secret IS NULL
                        """, encrypted, time(now), account.id());
                revokeAll(account.id(), now);
                audit.record(PlatformAudit.OPERATOR, account.id().toString(), "operator.mfa_enrolled", "operator",
                        account.id().toString(), null, now);
            }
            OperatorTokens tokens = newSession(account.id(), challenge.deviceLabel(), now);
            audit.record(PlatformAudit.OPERATOR, account.id().toString(), "operator.signed_in", "operator_session",
                    tokens.sessionId().toString(), null, now);
            return tokens;
        });
    }

    private void mfaFailed(Account account, Challenge challenge, Instant now) {
        audit.record(PlatformAudit.OPERATOR, account.id().toString(), "operator.mfa_failed", "operator_challenge",
                challenge.id().toString(), null, now);
        throw invalidCredentials();
    }

    // --- sessions --------------------------------------------------------------------------------

    private OperatorTokens newSession(UUID operator, String label, Instant now) {
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
        return new OperatorTokens("authenticated", session, crypto.access(operator, session, generation, issued),
                refresh, issued.plusSeconds(300), expires);
    }

    private void revokeAll(UUID operator, Instant now) {
        jdbc().update("UPDATE operator_session SET revoked_at = ?, updated_at = ? WHERE user_id = ? AND revoked_at IS NULL",
                time(now), time(now), operator);
    }

    private static Session session(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Session(row.getObject("id", UUID.class), row.getObject("user_id", UUID.class),
                row.getBoolean("mfa_verified"), row.getLong("generation"), instant(row.getObject("expires_at")),
                instant(row.getObject("revoked_at")));
    }

    private boolean live(Session session, Optional<Account> account, Instant now) {
        return session.revokedAt() == null && now.isBefore(session.expiresAt()) && session.mfaVerified()
                && account.isPresent() && account.get().active() && account.get().mfaSecret() != null
                && OperatorRole.of(account.get().role()).isPresent();
    }

    /**
     * Rotates a refresh credential once. A spent credential revokes its whole family, so of two
     * simultaneous renewals at most one succeeds and a replay can never leave a usable family.
     */
    public OperatorTokens refresh(String refreshToken) {
        return work.run(() -> {
            Instant now = clock.instant();
            String hash = crypto.hash("refresh", refreshToken == null ? "" : refreshToken);
            Optional<Session> current = jdbc().query("""
                    SELECT id, user_id, mfa_verified, generation, expires_at, revoked_at
                      FROM operator_session WHERE refresh_hash = ? FOR UPDATE
                    """, (row, n) -> session(row), hash).stream().findFirst();
            if (current.isEmpty()) {
                List<UUID> family = jdbc().queryForList(
                        "SELECT session_id FROM operator_refresh_history WHERE credential_hash = ?", UUID.class, hash);
                for (UUID id : family) {
                    int revoked = jdbc().update("""
                            UPDATE operator_session SET revoked_at = ?, updated_at = ? WHERE id = ? AND revoked_at IS NULL
                            """, time(now), time(now), id);
                    if (revoked > 0) {
                        audit.record(PlatformAudit.SYSTEM, "refresh", "operator.refresh_replayed", "operator_session",
                                id.toString(), null, now);
                    }
                }
                throw invalidSession();
            }
            Session session = current.get();
            if (!live(session, account("id", session.userId()), now)) {
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
            audit.record(PlatformAudit.OPERATOR, session.userId().toString(), "operator.session_renewed",
                    "operator_session", session.id().toString(), null, now);
            return tokens(session.userId(), session.id(), session.generation() + 1, next, session.expiresAt());
        });
    }

    /** Revokes the current session: no access or refresh credential of it works afterwards. */
    public void signOut() {
        work.run(() -> {
            Operator operator = authenticated();
            Instant now = clock.instant();
            jdbc().update("UPDATE operator_session SET revoked_at = ?, updated_at = ? WHERE id = ? AND revoked_at IS NULL",
                    time(now), time(now), operator.sessionId());
            audit.record(PlatformAudit.OPERATOR, operator.id().toString(), "operator.signed_out", "operator_session",
                    operator.sessionId().toString(), null, now);
            return null;
        });
    }

    /** The current operator: no tenant identity, permissions from the role as stored now. */
    public OperatorPrincipal me() {
        return work.run(() -> {
            Operator operator = require(com.salonplatform.platform.domain.OperatorPermission.SESSION_MANAGE);
            return new OperatorPrincipal(operator.id(), OperatorCrypto.KIND, operator.role().name(),
                    operator.role().permissions().stream().sorted().toList(), true);
        });
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
                """, (row, n) -> session(row), claims.sessionId()).stream().findFirst()
                .orElseThrow(OperatorAccess::invalidSession);
        Optional<Account> account = account("id", session.userId());
        if (!session.userId().equals(claims.operatorId()) || session.generation() != claims.generation()
                || !live(session, account, clock.instant())) {
            throw invalidSession();
        }
        return new Operator(account.get().id(), OperatorRole.of(account.get().role()).orElseThrow(), session.id());
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
