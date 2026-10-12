package com.salonplatform.shared.outbox;

import java.time.Duration;
import java.time.Instant;

import com.salonplatform.shared.jobs.JobSettings;
import com.salonplatform.shared.jobs.TenantJob;
import com.salonplatform.shared.tenancy.CurrentTenant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Removes the messages a salon had delivered more than {@code salon.outbox.retention} ago (30 days),
 * salon by salon, every {@code salon.jobs.outbox-cleanup} (an hour).
 */
@Component
class OutboxCleanupJob implements TenantJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);

    private final OutboxStore store;

    private final Duration retention;

    private final Duration every;

    OutboxCleanupJob(OutboxStore store, OutboxSettings outbox, JobSettings jobs) {
        this.store = store;
        this.retention = outbox.retention();
        this.every = jobs.outboxCleanup();
    }

    @Override
    public String name() {
        return "outbox-cleanup";
    }

    @Override
    public Duration every() {
        return every;
    }

    @Override
    public void runFor(CurrentTenant salon, Instant now) {
        Instant before = now.minus(retention);
        int removed = store.removeSentBefore(salon.id(), before);
        log.info("Removed {} delivered outbox messages sent before {}", removed, before);
    }
}
