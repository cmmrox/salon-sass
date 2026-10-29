package com.salonplatform.platform.application;

import static com.salonplatform.platform.application.OperatorAccess.instant;
import static com.salonplatform.platform.application.OperatorAccess.invalidCredentials;
import static com.salonplatform.platform.application.OperatorAccess.time;

import com.salonplatform.platform.application.OperatorAccess.Account;
import com.salonplatform.platform.application.OperatorSessions.OperatorTokens;
import com.salonplatform.platform.domain.OperatorRole;
import com.salonplatform.platform.domain.PlainText;
import com.salonplatform.platform.infrastructure.OperatorCrypto;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import com.salonplatform.shared.web.FieldProblem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Operator sign-in (docs/architecture/operator-console.md, "Operator authentication"): a password, then
 * a five-minute single-use MFA challenge, which every operator completes (enrolling after a correct
 * password on first sign-in) before any session exists. Password attempts are limited per email and per
 * trusted address, unknown emails included, and every refusal commits its counters and audit.
 */
@Service
public class OperatorSignIns {

    static final int EMAIL_CEILING = 10;
    static final int ADDRESS_CEILING = 30;
    static final Duration RATE_WINDOW = Duration.ofMinutes(15);
    static final Duration CHALLENGE_LIFETIME = Duration.ofMinutes(5);
    static final int CHALLENGE_ATTEMPTS = 5;

    @Schema(requiredProperties = {"state", "challengeId", "challengeToken", "expiresAt", "credentials"})
    public record OperatorSignIn(
            String state,
            @Schema(format = "uuid") UUID challengeId,
            String challengeToken,
            Instant expiresAt,

            @Schema(
                    types = {"object", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            OperatorTokens credentials) {}

    @Schema(requiredProperties = {"challengeId", "secret", "otpauthUri", "expiresAt"})
    public record OperatorEnrollment(UUID challengeId, String secret, String otpauthUri, Instant expiresAt) {}

    private record Challenge(
            UUID id,
            UUID userId,
            String purpose,
            String credentialHash,
            String pendingSecret,
            String deviceLabel,
            Instant expiresAt,
            int attempts,
            Instant consumedAt) {}

    private final PlatformDatabase database;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final OperatorSessions sessions;
    private final OperatorCrypto crypto;
    private final PlatformAudit audit;
    private final Clock clock;

    public OperatorSignIns(
            PlatformDatabase database,
            OperatorWork work,
            OperatorAccess access,
            OperatorSessions sessions,
            OperatorCrypto crypto,
            PlatformAudit audit,
            Clock clock) {
        this.database = database;
        this.work = work;
        this.access = access;
        this.sessions = sessions;
        this.crypto = crypto;
        this.audit = audit;
        this.clock = clock;
    }

    private JdbcTemplate jdbc() {
        return database.jdbc();
    }

    /** Plain text of 1 to 80 characters: a label for the operator's own session list, never trusted. */
    static String deviceLabel(String label) {
        String value = PlainText.line(label, 80);
        if (value == null) {
            throw new ApplicationProblemException(
                    400,
                    ErrorCode.BAD_REQUEST,
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
                audit.record(
                        PlatformAudit.SYSTEM,
                        "sign-in",
                        "operator.sign_in_limited",
                        "operator-email",
                        crypto.hash("audit", key),
                        null,
                        now);
                long retry = Math.max(
                        byEmail.allowed() ? 0 : byEmail.retryAfter(), byAddress.allowed() ? 0 : byAddress.retryAfter());
                throw new ApplicationProblemException(429, ErrorCode.RATE_LIMITED).retryAfter(Math.max(1, retry));
            }
            Optional<Account> found = access.account("email", key);
            boolean correct = crypto.matchesPassword(
                    password, found.map(Account::passwordHash).orElse(null));
            if (found.isEmpty()
                    || !found.get().active()
                    || !correct
                    || OperatorRole.of(found.get().role()).isEmpty()) {
                audit.record(
                        PlatformAudit.SYSTEM,
                        "sign-in",
                        "operator.sign_in_failed",
                        "operator",
                        found.map(a -> a.id().toString()).orElseGet(() -> crypto.hash("audit", key)),
                        null,
                        now);
                throw invalidCredentials();
            }
            Account account = found.get();
            String purpose = account.mfaSecret() == null ? "enroll_mfa" : "verify_mfa";
            String token = crypto.randomToken();
            UUID challenge = UUID.randomUUID();
            Instant expires = now.plus(CHALLENGE_LIFETIME);
            jdbc().update(
                            """
                    INSERT INTO operator_mfa_challenge (id, user_id, purpose, credential_hash, device_label, expires_at,
                        attempts, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
                    """,
                            challenge,
                            account.id(),
                            purpose,
                            crypto.hash("challenge", token),
                            device,
                            time(expires),
                            time(now),
                            time(now));
            audit.record(
                    PlatformAudit.OPERATOR,
                    account.id().toString(),
                    "operator.mfa_challenge_issued",
                    "operator",
                    account.id().toString(),
                    Map.of("purpose", purpose),
                    now);
            return new OperatorSignIn(purpose, challenge, token, expires, null);
        });
    }

    private record Attempt(boolean allowed, long retryAfter) {}

    /** One atomic count in a fifteen-minute window that starts on its first attempt. */
    private Attempt attempt(String dimension, String key, int ceiling, Instant now) {
        Map<String, Object> row = jdbc().queryForMap(
                        """
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
                """,
                        UUID.randomUUID(),
                        dimension,
                        crypto.hash("rate-" + dimension, key),
                        time(now),
                        time(now),
                        time(now));
        int attempts = ((Number) row.get("attempts")).intValue();
        Instant windowEnd = instant(row.get("window_start")).plus(RATE_WINDOW);
        long remaining = (long) Math.ceil(Duration.between(now, windowEnd).toMillis() / 1000.0);
        return new Attempt(attempts <= ceiling, Math.max(1, remaining));
    }

    // --- MFA -------------------------------------------------------------------------------------

    private Challenge lockedChallenge(UUID id, String token) {
        Challenge challenge = jdbc()
                .query(
                        """
                SELECT id, user_id, purpose, credential_hash, pending_secret, device_label, expires_at, attempts,
                       consumed_at
                  FROM operator_mfa_challenge WHERE id = ? FOR UPDATE
                """,
                        (row, n) -> new Challenge(
                                row.getObject("id", UUID.class),
                                row.getObject("user_id", UUID.class),
                                row.getString("purpose"),
                                row.getString("credential_hash"),
                                row.getString("pending_secret"),
                                row.getString("device_label"),
                                instant(row.getObject("expires_at")),
                                row.getInt("attempts"),
                                instant(row.getObject("consumed_at"))),
                        id)
                .stream()
                .findFirst()
                .orElseThrow(OperatorAccess::invalidCredentials);
        if (!crypto.equalHash(challenge.credentialHash(), crypto.hash("challenge", token == null ? "" : token))
                || challenge.consumedAt() != null
                || !clock.instant().isBefore(challenge.expiresAt())
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
            Account account = access.account("id", challenge.userId())
                    .filter(Account::active)
                    .orElseThrow(OperatorAccess::invalidCredentials);
            if (!"enroll_mfa".equals(challenge.purpose()) || account.mfaSecret() != null) {
                throw invalidCredentials();
            }
            String pending = challenge.pendingSecret();
            if (pending == null) {
                pending = crypto.encrypt(totpPurpose(account.id()), crypto.newTotpSecret());
                jdbc().update(
                                "UPDATE operator_mfa_challenge SET pending_secret = ?, updated_at = ? WHERE id = ?",
                                pending,
                                time(clock.instant()),
                                challenge.id());
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
            jdbc().update(
                            "UPDATE operator_mfa_challenge SET attempts = attempts + 1, updated_at = ? WHERE id = ?",
                            time(now),
                            challenge.id());
            Account account = access.account("id", challenge.userId())
                    .filter(Account::active)
                    .filter(a -> OperatorRole.of(a.role()).isPresent())
                    .orElseThrow(OperatorAccess::invalidCredentials);
            boolean enrolling = "enroll_mfa".equals(challenge.purpose());
            String encrypted = enrolling ? challenge.pendingSecret() : account.mfaSecret();
            if ((enrolling && account.mfaSecret() != null) || encrypted == null) {
                mfaFailed(account, challenge, now);
            }
            Long step = crypto.matchingStep(crypto.decrypt(totpPurpose(account.id()), encrypted), code);
            int consumed =
                    step == null ? 0 : jdbc().update("""
                    INSERT INTO operator_mfa_step (id, user_id, step, created_at, updated_at) VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (user_id, step) DO NOTHING
                    """, UUID.randomUUID(), account.id(), step, time(now), time(now));
            if (consumed == 0) {
                mfaFailed(account, challenge, now);
            }
            jdbc().update(
                            "UPDATE operator_mfa_challenge SET consumed_at = ?, updated_at = ? WHERE id = ?",
                            time(now),
                            time(now),
                            challenge.id());
            if (enrolling) {
                jdbc().update("""
                        UPDATE operator_user SET mfa_secret = ?, version = version + 1, updated_at = ?
                         WHERE id = ? AND mfa_secret IS NULL
                        """, encrypted, time(now), account.id());
                sessions.revokeAll(account.id(), now);
                audit.record(
                        PlatformAudit.OPERATOR,
                        account.id().toString(),
                        "operator.mfa_enrolled",
                        "operator",
                        account.id().toString(),
                        null,
                        now);
            }
            OperatorTokens tokens = sessions.newSession(account.id(), challenge.deviceLabel(), now);
            audit.record(
                    PlatformAudit.OPERATOR,
                    account.id().toString(),
                    "operator.signed_in",
                    "operator_session",
                    tokens.sessionId().toString(),
                    null,
                    now);
            return tokens;
        });
    }

    private void mfaFailed(Account account, Challenge challenge, Instant now) {
        audit.record(
                PlatformAudit.OPERATOR,
                account.id().toString(),
                "operator.mfa_failed",
                "operator_challenge",
                challenge.id().toString(),
                null,
                now);
        throw invalidCredentials();
    }
}
