package com.salonplatform.platform.infrastructure;

import static com.salonplatform.platform.infrastructure.PlatformTimes.time;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.observability.Traces;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The messages the platform writes into one salon's outbox from the platform connection: billing emails
 * and charges, and the export-ready email. Named direct-SQL exception 2 of docs/engineering/backend.md:
 * the platform role has no grant on {@code outbox_message}, and {@code outbox_enqueue_for_salon} is a
 * {@code SECURITY DEFINER} function that writes the row of the salon it is given, in the caller's
 * platform transaction, with the current trace.
 */
@Component
public class PlatformOutbox {

    private final PlatformDatabase database;
    private final Traces traces;
    private final JsonMapper json;

    PlatformOutbox(PlatformDatabase database, Traces traces, JsonMapper json) {
        this.database = database;
        this.traces = traces;
        this.json = json;
    }

    public void enqueue(UUID salon, String channel, Map<String, Object> payload, Instant now) {
        enqueueWithId(salon, UUID.randomUUID(), channel, payload, now);
    }

    public void enqueueWithId(UUID salon, UUID id, String channel, Map<String, Object> payload, Instant now) {
        database.jdbc()
                .sql("SELECT outbox_enqueue_for_salon(?, ?, ?, ?::jsonb, ?, ?)::text")
                .params(
                        salon,
                        id,
                        channel,
                        json.writeValueAsString(payload),
                        traces.currentTraceParent().orElse(null),
                        time(now))
                .query(String.class)
                .list();
    }
}
