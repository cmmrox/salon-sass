package com.salonplatform.platform.infrastructure;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.jobs.PlatformJob;

import org.springframework.stereotype.Component;

/**
 * The bounded platform job that removes expired operator security state every fifteen minutes:
 * password counters whose window has ended, MFA challenges past their expiry, and consumed TOTP steps
 * outside the accepted tolerance. At most a fixed number of rows of each kind per run.
 */
@Component
class OperatorSecurityCleanupJob implements PlatformJob {

    static final int BATCH = 1000;

    private final PlatformDatabase database;

    OperatorSecurityCleanupJob(PlatformDatabase database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "operator-security-cleanup";
    }

    @Override
    public Duration every() {
        return Duration.ofMinutes(15);
    }

    @Override
    public void run(Instant now) {
        database.inTransaction(status -> {
            database.jdbc().update("""
                    DELETE FROM operator_rate_bucket WHERE id IN (SELECT id FROM operator_rate_bucket
                     WHERE window_start + interval '15 minutes' <= ? LIMIT ?)
                    """, Timestamp.from(now), BATCH);
            database.jdbc().update("""
                    DELETE FROM operator_mfa_challenge WHERE id IN (SELECT id FROM operator_mfa_challenge
                     WHERE expires_at <= ? LIMIT ?)
                    """, Timestamp.from(now), BATCH);
            // A step stays refused while it can still match (one adjacent step either side).
            database.jdbc().update("""
                    DELETE FROM operator_mfa_step WHERE id IN (SELECT id FROM operator_mfa_step WHERE step < ? LIMIT ?)
                    """, now.getEpochSecond() / 30 - 2, BATCH);
            return null;
        });
    }
}
