package com.salonplatform.platform.infrastructure;

import static com.salonplatform.platform.infrastructure.PlatformTimes.instant;
import static com.salonplatform.platform.infrastructure.PlatformTimes.time;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * An operator's short-lived security state: password counters, MFA challenges and consumed TOTP steps
 * ({@code operator_rate_bucket}, {@code operator_mfa_challenge}, {@code operator_mfa_step}), on the
 * platform connection. Named direct-SQL exception 1 of docs/engineering/backend.md (platform tables
 * with no {@code tenant_id}); the cleanup is also exception 4, bounded set-based removal. Counting and
 * step consumption are single atomic statements, so simultaneous attempts are counted and refused
 * exactly as the database orders them.
 */
@Component
public class OperatorSecurityStore {

    /** A password counter after counting one attempt: the attempts in the window and when it started. */
    public record RateCount(int attempts, Instant windowStart) {}

    /** An MFA challenge as verification reads it, locked. */
    public record Challenge(
            UUID id,
            UUID userId,
            String purpose,
            String credentialHash,
            String pendingSecret,
            String deviceLabel,
            Instant expiresAt,
            int attempts,
            Instant consumedAt) {}

    /** A challenge to issue. */
    public record NewChallenge(
            UUID id, UUID userId, String purpose, String credentialHash, String deviceLabel, Instant expiresAt) {}

    private final PlatformDatabase database;

    OperatorSecurityStore(PlatformDatabase database) {
        this.database = database;
    }

    /**
     * Counts one attempt in a fifteen-minute window that starts on its first attempt, in one statement.
     */
    public RateCount countAttempt(String dimension, String keyHash, Instant now) {
        Map<String, Object> row = database.jdbc()
                .sql("""
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
                        """)
                .params(UUID.randomUUID(), dimension, keyHash, time(now), time(now), time(now))
                .query()
                .singleRow();
        return new RateCount(((Number) row.get("attempts")).intValue(), instant(row.get("window_start")));
    }

    public void issueChallenge(NewChallenge challenge, Instant now) {
        database.jdbc()
                .sql("""
                        INSERT INTO operator_mfa_challenge (id, user_id, purpose, credential_hash, device_label, expires_at,
                            attempts, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?)
                        """)
                .params(
                        challenge.id(),
                        challenge.userId(),
                        challenge.purpose(),
                        challenge.credentialHash(),
                        challenge.deviceLabel(),
                        time(challenge.expiresAt()),
                        time(now),
                        time(now))
                .update();
    }

    /** The challenge, locked until the verifying transaction ends. */
    public Optional<Challenge> lockChallenge(UUID id) {
        return database
                .jdbc()
                .sql("""
                        SELECT id, user_id, purpose, credential_hash, pending_secret, device_label, expires_at, attempts,
                               consumed_at
                          FROM operator_mfa_challenge WHERE id = ? FOR UPDATE
                        """)
                .param(id)
                .query((row, n) -> new Challenge(
                        row.getObject("id", UUID.class),
                        row.getObject("user_id", UUID.class),
                        row.getString("purpose"),
                        row.getString("credential_hash"),
                        row.getString("pending_secret"),
                        row.getString("device_label"),
                        instant(row.getObject("expires_at")),
                        row.getInt("attempts"),
                        instant(row.getObject("consumed_at"))))
                .list()
                .stream()
                .findFirst();
    }

    public void savePendingSecret(UUID challenge, String encryptedSecret, Instant now) {
        database.jdbc()
                .sql("UPDATE operator_mfa_challenge SET pending_secret = ?, updated_at = ? WHERE id = ?")
                .params(encryptedSecret, time(now), challenge)
                .update();
    }

    public void countChallengeAttempt(UUID challenge, Instant now) {
        database.jdbc()
                .sql("UPDATE operator_mfa_challenge SET attempts = attempts + 1, updated_at = ? WHERE id = ?")
                .params(time(now), challenge)
                .update();
    }

    public void consumeChallenge(UUID challenge, Instant now) {
        database.jdbc()
                .sql("UPDATE operator_mfa_challenge SET consumed_at = ?, updated_at = ? WHERE id = ?")
                .params(time(now), time(now), challenge)
                .update();
    }

    /**
     * Consumes a TOTP step for the operator once: a step already consumed, even by a simultaneous
     * request, is not consumed again.
     *
     * @return whether this call consumed it
     */
    public boolean consumeStep(UUID operator, long step, Instant now) {
        return database.jdbc()
                        .sql("""
                        INSERT INTO operator_mfa_step (id, user_id, step, created_at, updated_at) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (user_id, step) DO NOTHING
                        """)
                        .params(UUID.randomUUID(), operator, step, time(now), time(now))
                        .update()
                > 0;
    }

    /** Local fixtures only: the fixture operators' challenges and consumed steps. */
    public void clearFixtureChallenges() {
        database.jdbc()
                .sql("DELETE FROM operator_mfa_challenge WHERE user_id IN (SELECT id FROM operator_user WHERE fixture)")
                .update();
        database.jdbc()
                .sql("DELETE FROM operator_mfa_step WHERE user_id IN (SELECT id FROM operator_user WHERE fixture)")
                .update();
    }

    /** Local fixtures only: one email's password counter. */
    public void clearEmailCounter(String keyHash) {
        database.jdbc()
                .sql("DELETE FROM operator_rate_bucket WHERE dimension = 'operator-email' AND key_hash = ?")
                .param(keyHash)
                .update();
    }

    /** Local fixtures only: every address counter. */
    public void clearAddressCounters() {
        database.jdbc()
                .sql("DELETE FROM operator_rate_bucket WHERE dimension = 'operator-address'")
                .update();
    }

    /**
     * Removes expired state, at most {@code batch} rows of each kind: counters whose window has ended,
     * challenges past their expiry, and steps before {@code oldestRefusedStep}.
     */
    public void removeExpired(Instant now, long oldestRefusedStep, int batch) {
        database.jdbc().sql("""
                        DELETE FROM operator_rate_bucket WHERE id IN (SELECT id FROM operator_rate_bucket
                         WHERE window_start + interval '15 minutes' <= ? LIMIT ?)
                        """).params(time(now), batch).update();
        database.jdbc().sql("""
                        DELETE FROM operator_mfa_challenge WHERE id IN (SELECT id FROM operator_mfa_challenge
                         WHERE expires_at <= ? LIMIT ?)
                        """).params(time(now), batch).update();
        database.jdbc().sql("""
                        DELETE FROM operator_mfa_step WHERE id IN (SELECT id FROM operator_mfa_step WHERE step < ? LIMIT ?)
                        """).params(oldestRefusedStep, batch).update();
    }
}
