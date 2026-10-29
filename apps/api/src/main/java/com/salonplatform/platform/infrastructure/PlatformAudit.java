package com.salonplatform.platform.infrastructure;

import com.salonplatform.shared.audit.SecretValues;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.observability.Traces;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The append-only platform audit (docs/architecture/operator-console.md): actor, action, subject,
 * the application's clock and the trace, in the caller's platform transaction, so a rolled-back
 * creation leaves no success record and a failed sign-in's record commits with its counters. Details
 * are an allowlist the caller chooses; a value under a key that names a secret is redacted anyway.
 * Never passwords, hashes, MFA secrets, tokens, contact or profile values, or raw addresses. Named
 * direct-SQL exception 1 of docs/engineering/backend.md: a platform table with no {@code tenant_id}.
 */
@Component
public class PlatformAudit {

    public static final String OPERATOR = "operator";
    public static final String SYSTEM = "system";

    private final PlatformDatabase database;
    private final Clock clock;
    private final Traces traces;
    private final JsonMapper json;

    public PlatformAudit(PlatformDatabase database, Clock clock, Traces traces, JsonMapper json) {
        this.database = database;
        this.clock = clock;
        this.traces = traces;
        this.json = json;
    }

    public UUID record(
            String actorKind,
            String actorId,
            String action,
            String subjectKind,
            String subjectId,
            Map<String, ?> details) {
        return record(actorKind, actorId, action, subjectKind, subjectId, details, clock.instant());
    }

    public UUID record(
            String actorKind,
            String actorId,
            String action,
            String subjectKind,
            String subjectId,
            Map<String, ?> details,
            Instant at) {
        database.requireTransaction();
        UUID id = UUID.randomUUID();
        Timestamp time = Timestamp.from(at);
        database.jdbc()
                .sql("""
                        INSERT INTO platform_audit_entry (id, actor_kind, actor_id, action, subject_kind, subject_id, details,
                            trace_id, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                        """)
                .params(
                        id,
                        actorKind,
                        actorId,
                        action,
                        subjectKind,
                        subjectId,
                        details == null ? null : json.writeValueAsString(SecretValues.redact(details)),
                        traces.currentTraceId().orElse(null),
                        time,
                        time)
                .update();
        return id;
    }
}
