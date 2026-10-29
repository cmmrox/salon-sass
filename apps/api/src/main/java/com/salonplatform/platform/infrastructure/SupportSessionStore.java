package com.salonplatform.platform.infrastructure;

import static com.salonplatform.platform.infrastructure.PlatformTimes.time;

import com.salonplatform.shared.infrastructure.PlatformDatabase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Support sessions and their single-use entry codes ({@code support_session}, {@code support_entry_code}),
 * with the salon and operator each names, on the platform connection: named direct-SQL exception 1 of
 * docs/engineering/backend.md (platform tables with no {@code tenant_id}). The salon's own audit entry of
 * a session is written through {@code audit_record_support}, a {@code SECURITY DEFINER} function that
 * writes {@code audit_entry} for the salon it is given and no other (exception 2). Expiry claims due
 * sessions with {@code FOR UPDATE SKIP LOCKED}, so concurrent runs end each session once (exception 3).
 */
@Component
public class SupportSessionStore {

    /** A stored session with its salon and operator. */
    public record Session(
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

        public boolean open(Instant now) {
            return endedAt == null && now.isBefore(expiresAt);
        }
    }

    /** A salon as a support session's start reads it. */
    public record Salon(String slug, String status) {}

    /** A session just entered with a consumed code. */
    public record Entered(UUID sessionId, UUID operatorId, Instant expiresAt) {}

    /** A session to start. */
    public record NewSession(UUID id, UUID salonId, UUID operatorId, String reason, int durationMinutes) {}

    /** A salon's support-session audit entry, written through {@code audit_record_support}. */
    public record SalonAuditEntry(
            UUID salonId,
            String actorKind,
            String actorId,
            String action,
            UUID sessionId,
            String reason,
            String afterJson,
            String traceId,
            Instant at) {}

    private static final String SELECT = """
            SELECT s.id, s.salon_id, t.slug, t.name AS salon_name, s.operator_id, o.display_name, s.reason,
                   s.started_at, s.expires_at, s.ended_at, s.ended_by
              FROM support_session s JOIN tenant t ON t.id = s.salon_id JOIN operator_user o ON o.id = s.operator_id
            """;

    /** The sessions of one operator, one salon and one state, each filter left out when null. */
    private static final String MATCHING =
            " WHERE (?::uuid IS NULL OR s.operator_id = ?::uuid) AND (?::uuid IS NULL OR s.salon_id = ?::uuid)"
                    + " AND (?::text IS NULL OR (?::text = 'active') = (s.ended_at IS NULL AND s.expires_at > ?))";

    private static final String DUE = " AND s.ended_at IS NULL AND s.expires_at <= ?"
            + " ORDER BY s.expires_at, s.id FOR UPDATE OF s SKIP LOCKED";

    private final PlatformDatabase database;

    SupportSessionStore(PlatformDatabase database) {
        this.database = database;
    }

    public Optional<Session> find(UUID id) {
        return first(SELECT + " WHERE s.id = ?", id);
    }

    /** The session, locked until the transaction ends. */
    public Optional<Session> lock(UUID id) {
        return first(SELECT + " WHERE s.id = ? FOR UPDATE OF s", id);
    }

    private Optional<Session> first(String sql, UUID id) {
        return database.jdbc().sql(sql).param(id).query((row, n) -> session(row)).list().stream()
                .findFirst();
    }

    /** Every open session whose expiry has passed, claimed for this run; another run skips them. */
    public List<Session> claimExpired(Instant now) {
        return database.jdbc()
                .sql(SELECT + " WHERE true" + DUE)
                .param(time(now))
                .query((row, n) -> session(row))
                .list();
    }

    /** As {@link #claimExpired}, for one operator's sessions of one salon. */
    public List<Session> claimExpired(UUID operatorId, UUID salonId, Instant now) {
        return database.jdbc()
                .sql(SELECT + " WHERE operator_id = ? AND salon_id = ?" + DUE)
                .params(operatorId, salonId, time(now))
                .query((row, n) -> session(row))
                .list();
    }

    /** One page of the sessions matching the filters, the newest first. */
    public List<Session> page(UUID operatorId, UUID salonId, String state, Instant now, int size, long offset) {
        List<Object> params = new ArrayList<>(matching(operatorId, salonId, state, now));
        params.add(size);
        params.add(offset);
        return database.jdbc()
                .sql(SELECT + MATCHING + " ORDER BY s.started_at DESC, s.id LIMIT ? OFFSET ?")
                .params(params)
                .query((row, n) -> session(row))
                .list();
    }

    public long count(UUID operatorId, UUID salonId, String state, Instant now) {
        return database.jdbc()
                .sql("SELECT count(*) FROM support_session s" + MATCHING)
                .params(matching(operatorId, salonId, state, now))
                .query(Long.class)
                .single();
    }

    private static List<Object> matching(UUID operatorId, UUID salonId, String state, Instant now) {
        return java.util.Arrays.asList(operatorId, operatorId, salonId, salonId, state, state, time(now));
    }

    /** One page of a salon's sessions, the newest first. */
    public List<Session> pageOfSalon(UUID salonId, int size, long offset) {
        return database.jdbc()
                .sql(SELECT + " WHERE s.salon_id = ? ORDER BY s.started_at DESC, s.id LIMIT ? OFFSET ?")
                .params(salonId, size, offset)
                .query((row, n) -> session(row))
                .list();
    }

    public long countOfSalon(UUID salonId) {
        return database.jdbc()
                .sql("SELECT count(*) FROM support_session WHERE salon_id = ?")
                .param(salonId)
                .query(Long.class)
                .single();
    }

    /**
     * The salon a session would start for, share-locked so that a suspension or closure waits for the
     * start, or the start sees its result.
     */
    public Optional<Salon> salonForStart(UUID salonId) {
        return database
                .jdbc()
                .sql("SELECT slug, status FROM tenant WHERE id = ? FOR SHARE")
                .param(salonId)
                .query((row, n) -> new Salon(row.getString("slug"), row.getString("status")))
                .list()
                .stream()
                .findFirst();
    }

    /**
     * Starts a session.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the operator has an open one for the salon
     */
    public void start(NewSession session, Instant now, Instant expiresAt) {
        database.jdbc()
                .sql("""
                        INSERT INTO support_session (id, salon_id, operator_id, reason, duration_minutes, started_at,
                            expires_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        session.id(),
                        session.salonId(),
                        session.operatorId(),
                        session.reason(),
                        session.durationMinutes(),
                        time(now),
                        time(expiresAt),
                        time(now),
                        time(now))
                .update();
    }

    /** Ends a session at {@code endedAt}, saying how and by whom. */
    public void end(UUID id, Instant endedAt, String by, String byId, Instant now) {
        database.jdbc().sql("""
                        UPDATE support_session SET ended_at = ?, ended_by = ?, ended_by_id = ?, updated_at = ? WHERE id = ?
                        """).params(time(endedAt), by, byId, time(now), id).update();
    }

    public void issueEntryCode(UUID sessionId, String codeHash, Instant expiresAt, Instant now) {
        database.jdbc()
                .sql("""
                        INSERT INTO support_entry_code (id, session_id, code_hash, expires_at, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), sessionId, codeHash, time(expiresAt), time(now), time(now))
                .update();
    }

    /**
     * Consumes an unexpired entry code of an open session of this salon, in one statement: of
     * simultaneous exchanges exactly one updates the row.
     */
    public Optional<Entered> consumeEntryCode(String codeHash, UUID salonId, Instant now) {
        return database
                .jdbc()
                .sql("""
                        UPDATE support_entry_code c SET consumed_at = ?, updated_at = ?
                          FROM support_session s
                         WHERE c.code_hash = ? AND c.consumed_at IS NULL AND c.expires_at > ?
                           AND s.id = c.session_id AND s.salon_id = ? AND s.ended_at IS NULL AND s.expires_at > ?
                        RETURNING s.id, s.operator_id, s.expires_at
                        """)
                .params(time(now), time(now), codeHash, time(now), salonId, time(now))
                .query((row, n) -> new Entered(
                        row.getObject(1, UUID.class),
                        row.getObject(2, UUID.class),
                        row.getTimestamp(3).toInstant()))
                .list()
                .stream()
                .findFirst();
    }

    public String operatorName(UUID operatorId) {
        return database.jdbc()
                .sql("SELECT display_name FROM operator_user WHERE id = ?")
                .param(operatorId)
                .query(String.class)
                .single();
    }

    public boolean operatorActive(UUID operatorId) {
        return database
                .jdbc()
                .sql("SELECT active FROM operator_user WHERE id = ?")
                .param(operatorId)
                .query((row, n) -> row.getBoolean(1))
                .list()
                .stream()
                .findFirst()
                .orElse(false);
    }

    /** Writes the salon's own audit entry of a support session, for that salon only. */
    public void recordInSalonAudit(SalonAuditEntry entry) {
        database.jdbc()
                .sql("SELECT audit_record_support(?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)")
                .params(
                        entry.salonId(),
                        UUID.randomUUID(),
                        entry.actorKind(),
                        entry.actorId(),
                        entry.action(),
                        entry.sessionId().toString(),
                        entry.reason(),
                        entry.afterJson(),
                        entry.traceId(),
                        time(entry.at()))
                .query(Boolean.class)
                .list();
    }

    private static Session session(ResultSet row) throws SQLException {
        Timestamp ended = row.getTimestamp("ended_at");
        return new Session(
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
}
