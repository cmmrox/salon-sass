package com.salonplatform.shared.outbox.impl;

import com.salonplatform.shared.jobs.JobSettings;
import com.salonplatform.shared.jobs.TenantJob;
import com.salonplatform.shared.outbox.OutboxSettings;
import com.salonplatform.shared.tenancy.CurrentTenant;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The outbox's housekeeping, salon by salon, every {@code salon.jobs.outbox-cleanup} (an hour): closes as
 * {@code unrouted} the events that no subscriber of this build takes and that have waited, never attempted, for
 * {@code salon.outbox.unrouted-after} (seven days, decision 119), and removes the messages delivered, and the
 * events closed as unrouted, more than {@code salon.outbox.retention} ago (30 days).
 */
@Component
class OutboxCleanupJob implements TenantJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxCleanupJob.class);

    private final OutboxStore store;

    private final EventSubscribers subscribers;

    private final Duration retention;

    private final Duration unroutedAfter;

    private final Duration every;

    OutboxCleanupJob(OutboxStore store, EventSubscribers subscribers, OutboxSettings outbox, JobSettings jobs) {
        this.store = store;
        this.subscribers = subscribers;
        this.retention = outbox.retention();
        this.unroutedAfter = outbox.unroutedAfter();
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
        Instant publishedBefore = now.minus(unroutedAfter);
        int closed = store.closeUnrouted(salon.id(), subscribers.types(), publishedBefore, now);
        if (closed > 0) {
            log.info(
                    "Closed {} events as unrouted: published before {}, with no subscriber in this build",
                    closed,
                    publishedBefore);
        }
        Instant before = now.minus(retention);
        int removed = store.removeSentBefore(salon.id(), before);
        int unrouted = store.removeUnroutedBefore(salon.id(), before);
        log.info(
                "Removed {} delivered outbox messages sent before {}, and {} unrouted events closed before then",
                removed,
                before,
                unrouted);
    }
}
