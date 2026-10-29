package com.salonplatform.shared.outbox;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Which subscriber has processed which event, per salon ({@code shared_processed_event}). Direct SQL because
 * the record is a claim taken with {@code INSERT ... ON CONFLICT DO NOTHING} (named direct-SQL exception 3,
 * {@code docs/engineering/backend.md}): a second delivery of the same event to the same subscriber waits for
 * the first one's transaction and then finds the record, where a repository would fail one of them instead.
 * Runs in the subscriber's transaction with the event's salon set, so row-level security holds, and names
 * that salon in the statement as well.
 */
@Component
class ProcessedEventStore {

    private static final String RECORD = """
            INSERT INTO shared_processed_event (tenant_id, event_id, subscriber, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    private final JdbcClient jdbc;

    ProcessedEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records that the subscriber processed the event, in the caller's transaction; false when it already
     * had, and its change must not be applied again.
     */
    boolean record(UUID tenantId, UUID eventId, String subscriber, Instant at) {
        OffsetDateTime utc = at.atOffset(ZoneOffset.UTC);
        return jdbc.sql(RECORD).params(tenantId, eventId, subscriber, utc, utc).update() == 1;
    }
}
