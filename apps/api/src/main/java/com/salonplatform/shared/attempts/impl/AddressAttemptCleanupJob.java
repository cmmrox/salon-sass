package com.salonplatform.shared.attempts.impl;

import com.salonplatform.shared.jobs.PlatformJob;
import com.salonplatform.shared.persistence.PlatformDatabase;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Removes the address counters whose window has ended, every fifteen minutes, in bounded batches, each in a platform
 * transaction of its own, through {@code shared_remove_spent_address_attempts()}: a platform job on the platform
 * connection (docs/architecture/multi-tenancy.md, "Background jobs"), the one role that function is granted to. It
 * reads nothing of any salon.
 */
@Component
class AddressAttemptCleanupJob implements PlatformJob {

    private static final Logger log = LoggerFactory.getLogger(AddressAttemptCleanupJob.class);

    private static final int BATCH = 1_000;

    private final PlatformDatabase database;

    AddressAttemptCleanupJob(PlatformDatabase database) {
        this.database = database;
    }

    @Override
    public String name() {
        return "address-attempt-cleanup";
    }

    @Override
    public Duration every() {
        return Duration.ofMinutes(15);
    }

    @Override
    public void run(Instant now) {
        int removed = 0;
        int batch;
        do {
            batch = database.inTransaction(status -> database.jdbc()
                    .sql("SELECT shared_remove_spent_address_attempts(?, ?)")
                    .params(now.atOffset(ZoneOffset.UTC), BATCH)
                    .query(Integer.class)
                    .single());
            removed += batch;
        } while (batch == BATCH);
        log.info("Removed {} address counters whose window ended before {}", removed, now);
    }
}
