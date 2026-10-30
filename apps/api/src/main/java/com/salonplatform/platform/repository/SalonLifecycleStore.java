package com.salonplatform.platform.repository;

import static com.salonplatform.platform.repository.PlatformTimes.time;

import com.salonplatform.shared.persistence.PlatformDatabase;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A salon's lifecycle in the registry: suspension, reactivation, closure and the tombstone after
 * deletion, and the lifecycle history ({@code tenant}, {@code salon_lifecycle_event}), on the platform
 * connection. Named direct-SQL exception 1 of docs/engineering/backend.md: platform tables with no
 * {@code tenant_id}. A change is made only while the salon's registry row is locked.
 */
@Component
public class SalonLifecycleStore {

    /** A salon's registry row as a lifecycle change reads it, locked. */
    public record Locked(UUID id, String slug, String status, long version, String ownerEmail) {}

    /** A salon's lifecycle and access state, with what is needed to act for it. */
    public record State(String slug, String status, String accessState, String timeZone) {}

    /** One lifecycle event, with the display name of the operator who made it, if one did. */
    public record Event(
            UUID id,
            String kind,
            String reason,
            String actorKind,
            String actorId,
            String operatorName,
            Instant createdAt) {}

    private final PlatformDatabase database;

    SalonLifecycleStore(PlatformDatabase database) {
        this.database = database;
    }

    /** The salon's registry row, locked until the transaction ends. */
    public Optional<Locked> lock(UUID salonId) {
        return database
                .jdbc()
                .sql("""
                        SELECT id, slug, status, version, owner_email FROM tenant WHERE id = ? FOR UPDATE
                        """)
                .param(salonId)
                .query((row, n) -> new Locked(
                        row.getObject("id", UUID.class),
                        row.getString("slug"),
                        row.getString("status"),
                        row.getLong("version"),
                        row.getString("owner_email")))
                .list()
                .stream()
                .findFirst();
    }

    /** The salon's lifecycle status, its registry row locked until the transaction ends. */
    public Optional<String> lockStatus(UUID salonId) {
        return database
                .jdbc()
                .sql("SELECT status FROM tenant WHERE id = ? FOR UPDATE")
                .param(salonId)
                .query(String.class)
                .list()
                .stream()
                .findFirst();
    }

    public boolean exists(UUID salonId) {
        return database.jdbc()
                        .sql("SELECT count(*) FROM tenant WHERE id = ?")
                        .param(salonId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    public String accessState(UUID salonId) {
        return database.jdbc()
                .sql("SELECT access_state FROM tenant WHERE id = ?")
                .param(salonId)
                .query(String.class)
                .single();
    }

    public void suspend(UUID salonId, Instant at) {
        database.jdbc().sql("""
                        UPDATE tenant SET status = 'suspended', version = version + 1, updated_at = ? WHERE id = ?
                        """).params(time(at), salonId).update();
    }

    public void reactivate(UUID salonId, Instant at) {
        database.jdbc().sql("""
                        UPDATE tenant SET status = 'active', version = version + 1, updated_at = ? WHERE id = ?
                        """).params(time(at), salonId).update();
    }

    /** Closes the salon for good, its data to be deleted at {@code deleteAt}. */
    public void close(UUID salonId, Instant at, Instant deleteAt) {
        database.jdbc()
                .sql("""
                        UPDATE tenant SET status = 'closed', closed_at = ?, delete_at = ?, version = version + 1,
                            updated_at = ?
                         WHERE id = ?
                        """)
                .params(time(at), time(deleteAt), time(at), salonId)
                .update();
    }

    /** The closed salons whose deletion instant has come, the earliest first. */
    public List<UUID> dueForDeletion(Instant now) {
        return database.jdbc().sql("""
                        SELECT id FROM tenant WHERE status = 'closed' AND delete_at <= ? ORDER BY delete_at, id
                        """).param(time(now)).query(UUID.class).list();
    }

    /** Leaves the deleted salon's registry row as a tombstone. */
    public void markDeleted(UUID salonId, Instant at) {
        database.jdbc().sql("""
                        UPDATE tenant SET status = 'deleted', deleted_at = ?, version = version + 1, updated_at = ? WHERE id = ?
                        """).params(time(at), time(at), salonId).update();
    }

    public void recordEvent(UUID salonId, String kind, String reason, String actorKind, String actorId, Instant at) {
        database.jdbc()
                .sql("""
                        INSERT INTO salon_lifecycle_event (id, salon_id, kind, reason, actor_kind, actor_id, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), salonId, kind, reason, actorKind, actorId, time(at), time(at))
                .update();
    }

    /** One page of the salon's lifecycle history, newest first. */
    public List<Event> history(UUID salonId, int size, long offset) {
        return database.jdbc()
                .sql("""
                        SELECT e.id, e.kind, e.reason, e.actor_kind, e.actor_id, e.created_at, u.display_name
                          FROM salon_lifecycle_event e
                          LEFT JOIN operator_user u ON e.actor_kind = 'operator' AND u.id::text = e.actor_id
                         WHERE e.salon_id = ?
                         ORDER BY e.created_at DESC, e.id
                         LIMIT ? OFFSET ?
                        """)
                .params(salonId, size, offset)
                .query((row, n) -> new Event(
                        row.getObject("id", UUID.class),
                        row.getString("kind"),
                        row.getString("reason"),
                        row.getString("actor_kind"),
                        row.getString("actor_id"),
                        row.getString("display_name"),
                        row.getTimestamp("created_at").toInstant()))
                .list();
    }

    public long countHistory(UUID salonId) {
        return database.jdbc()
                .sql("SELECT count(*) FROM salon_lifecycle_event WHERE salon_id = ?")
                .param(salonId)
                .query(Long.class)
                .single();
    }

    public Optional<State> state(UUID salonId) {
        return database
                .jdbc()
                .sql("""
                        SELECT slug, status, access_state, time_zone FROM tenant WHERE id = ?
                        """)
                .param(salonId)
                .query((row, n) -> new State(
                        row.getString("slug"),
                        row.getString("status"),
                        row.getString("access_state"),
                        row.getString("time_zone")))
                .list()
                .stream()
                .findFirst();
    }
}
