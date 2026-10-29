package com.salonplatform.platform.application;

import com.salonplatform.platform.application.SupportSessions.SupportOperatorRef;
import com.salonplatform.platform.application.SupportSessions.SupportSalonRef;
import com.salonplatform.platform.application.SupportSessions.SupportSessionView;
import com.salonplatform.platform.infrastructure.PlatformAudit;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.observability.Traces;
import com.salonplatform.shared.web.ApplicationProblemException;
import com.salonplatform.shared.web.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stored support sessions as every support use case reads and ends them: one session row with its
 * salon and operator, and the one way a session ends, with its platform audit and the salon's own audit
 * entry. Runs inside the caller's platform transaction.
 */
@Component
class SupportSessionRecords {

    static final String ACTIVE = "active";
    static final String ENDED = "ended";

    static final String SELECT = """
            SELECT s.id, s.salon_id, t.slug, t.name AS salon_name, s.operator_id, o.display_name, s.reason,
                   s.started_at, s.expires_at, s.ended_at, s.ended_by
              FROM support_session s JOIN tenant t ON t.id = s.salon_id JOIN operator_user o ON o.id = s.operator_id
            """;

    /** One stored session row. */
    record Row(
            UUID id,
            UUID salonId,
            String slug,
            String salonName,
            UUID operatorId,
            String operatorName,
            String reason,
            Instant startedAt,
            Instant expiresAt,
            Instant endedAt,
            String endedBy) {

        boolean open(Instant now) {
            return endedAt == null && now.isBefore(expiresAt);
        }
    }

    private final PlatformDatabase database;
    private final PlatformAudit audit;
    private final Traces traces;
    private final JsonMapper json;
    private final Clock clock;
    private final String platformDomain;

    SupportSessionRecords(
            PlatformDatabase database,
            PlatformAudit audit,
            Traces traces,
            JsonMapper json,
            Clock clock,
            @Value("${salon.platform-domain}") String platformDomain) {
        this.database = database;
        this.audit = audit;
        this.traces = traces;
        this.json = json;
        this.clock = clock;
        this.platformDomain = platformDomain;
    }

    static Row row(ResultSet row) throws SQLException {
        Timestamp ended = row.getTimestamp("ended_at");
        return new Row(
                row.getObject("id", UUID.class),
                row.getObject("salon_id", UUID.class),
                row.getString("slug"),
                row.getString("salon_name"),
                row.getObject("operator_id", UUID.class),
                row.getString("display_name"),
                row.getString("reason"),
                row.getTimestamp("started_at").toInstant(),
                row.getTimestamp("expires_at").toInstant(),
                ended == null ? null : ended.toInstant(),
                row.getString("ended_by"));
    }

    SupportSessionView view(Row row, Instant now) {
        boolean open = row.open(now);
        Instant endedAt = row.endedAt() != null ? row.endedAt() : open ? null : row.expiresAt();
        String endedBy = row.endedBy() != null ? row.endedBy() : open ? null : "expiry";
        return new SupportSessionView(
                row.id(),
                new SupportSalonRef(row.salonId(), row.slug(), row.salonName(), hostname(row.slug())),
                new SupportOperatorRef(row.operatorId(), row.operatorName()),
                row.reason(),
                row.startedAt(),
                row.expiresAt(),
                endedAt,
                endedBy,
                open ? ACTIVE : ENDED);
    }

    Optional<Row> find(UUID id) {
        return database.jdbcTemplate().query(SELECT + " WHERE s.id = ?", (row, n) -> row(row), id).stream()
                .findFirst();
    }

    Optional<Row> lockRow(UUID id) {
        return database
                .jdbcTemplate()
                .query(SELECT + " WHERE s.id = ? FOR UPDATE OF s", (row, n) -> row(row), id)
                .stream()
                .findFirst();
    }

    /**
     * Ends an open session now (or, for expiry, at its expiry instant): its row, the platform audit and
     * the salon's audit entry. An ended session is 409 {@code invalid_state}: it is never reopened.
     */
    void finish(
            Row row,
            String by,
            String byId,
            String platformActorKind,
            String platformActorId,
            String salonActorKind,
            String salonActorId) {
        Instant now = clock.instant();
        boolean expiry = "expiry".equals(by);
        if (row.endedAt() != null || (!expiry && !row.open(now))) {
            throw new ApplicationProblemException(409, ErrorCode.INVALID_STATE);
        }
        Instant at = expiry ? row.expiresAt() : now;
        database.jdbcTemplate().update("""
                UPDATE support_session SET ended_at = ?, ended_by = ?, ended_by_id = ?, updated_at = ? WHERE id = ?
                """, Timestamp.from(at), by, byId, Timestamp.from(now), row.id());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("salonId", row.salonId().toString());
        details.put("endedBy", by);
        String action = switch (by) {
            case "admin", "owner" -> "support.session_revoked";
            case "expiry" -> "support.session_expired";
            default -> "support.session_ended";
        };
        audit.record(
                platformActorKind,
                platformActorId,
                action,
                "support_session",
                row.id().toString(),
                details,
                now);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("endedBy", by);
        salonAudit(row.salonId(), salonActorKind, salonActorId, "support.session_ended", row.id(), null, after, at);
    }

    void salonAudit(
            UUID salonId,
            String actorKind,
            String actorId,
            String action,
            UUID sessionId,
            String reason,
            Map<String, Object> after,
            Instant at) {
        database.jdbcTemplate()
                .queryForObject(
                        "SELECT audit_record_support(?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                        Boolean.class,
                        salonId,
                        UUID.randomUUID(),
                        actorKind,
                        actorId,
                        action,
                        sessionId.toString(),
                        reason,
                        json.writeValueAsString(after),
                        traces.currentTraceId().orElse(null),
                        Timestamp.from(at));
    }

    String operatorName(UUID operatorId) {
        return database.jdbcTemplate()
                .queryForObject("SELECT display_name FROM operator_user WHERE id = ?", String.class, operatorId);
    }

    boolean operatorActive(UUID operatorId) {
        return Boolean.TRUE.equals(database
                .jdbcTemplate()
                .query("SELECT active FROM operator_user WHERE id = ?", (row, n) -> row.getBoolean(1), operatorId)
                .stream()
                .findFirst()
                .orElse(false));
    }

    String hostname(String slug) {
        return slug + "." + platformDomain;
    }
}
