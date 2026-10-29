package com.salonplatform.platform.job;

import com.salonplatform.platform.repository.OperatorSecurityStore;
import com.salonplatform.shared.jobs.PlatformJob;
import com.salonplatform.shared.persistence.PlatformDatabase;
import java.time.Duration;
import java.time.Instant;
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
    private final OperatorSecurityStore security;

    OperatorSecurityCleanupJob(PlatformDatabase database, OperatorSecurityStore security) {
        this.database = database;
        this.security = security;
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
            // A step stays refused while it can still match (one adjacent step either side).
            security.removeExpired(now, now.getEpochSecond() / 30 - 2, BATCH);
            return null;
        });
    }
}
