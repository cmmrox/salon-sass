package com.salonplatform.shared.attempts.impl;

import com.salonplatform.shared.jobs.PlatformJob;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Removes the address counters whose window has ended, every fifteen minutes, in bounded batches, through
 * {@code shared_remove_spent_address_attempts()}. The counters are a platform-level table reached only through
 * that function and its counting one, so this runs with no salon set and reads nothing of any salon.
 */
@Component
class AddressAttemptCleanupJob implements PlatformJob {

    private static final Logger log = LoggerFactory.getLogger(AddressAttemptCleanupJob.class);

    private static final int BATCH = 1_000;

    private final JdbcClient jdbc;

    AddressAttemptCleanupJob(JdbcClient jdbc) {
        this.jdbc = jdbc;
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
            batch = jdbc.sql("SELECT shared_remove_spent_address_attempts(?, ?)")
                    .params(now.atOffset(ZoneOffset.UTC), BATCH)
                    .query(Integer.class)
                    .single();
            removed += batch;
        } while (batch == BATCH);
        log.info("Removed {} address counters whose window ended before {}", removed, now);
    }
}
