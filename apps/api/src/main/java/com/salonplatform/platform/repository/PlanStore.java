package com.salonplatform.platform.repository;

import static com.salonplatform.platform.repository.PlatformTimes.time;

import com.salonplatform.shared.persistence.PlatformDatabase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Plan definitions ({@code plan}), on the platform connection: named direct-SQL exception 1 of
 * docs/engineering/backend.md, a platform table with no {@code tenant_id}. Plans are never changed or
 * removed, so this store only lists, reads and adds them.
 */
@Component
public class PlanStore {

    /** A stored plan definition. */
    public record Plan(
            UUID id,
            String key,
            String name,
            int staffSeats,
            int branches,
            int messageAllowance,
            boolean customDomain,
            boolean dedicatedApp,
            Instant createdAt,
            Instant updatedAt) {}

    /** A plan to add: the key and name already normalised, every limit validated. */
    public record NewPlan(
            String key,
            String name,
            int staffSeats,
            int branches,
            int messageAllowance,
            boolean customDomain,
            boolean dedicatedApp) {}

    private static final String COLUMNS = "id, key, name, staff_seats, branches, message_allowance, custom_domain, "
            + "dedicated_app, created_at, updated_at";

    /** A literal, case-insensitive match on the name or the key, or every plan when the text is null. */
    private static final String MATCHING = " WHERE (?::text IS NULL OR strpos(lower(name), lower(?::text)) > 0"
            + " OR strpos(key, lower(?::text)) > 0)";

    private final PlatformDatabase database;

    PlanStore(PlatformDatabase database) {
        this.database = database;
    }

    /** One page of the plans matching the text, newest first. */
    public List<Plan> page(String text, int size, long offset) {
        return database.jdbc()
                .sql("SELECT " + COLUMNS + " FROM plan" + MATCHING + " ORDER BY created_at DESC, id LIMIT ? OFFSET ?")
                .params(text, text, text, size, offset)
                .query((row, n) -> plan(row))
                .list();
    }

    public long count(String text) {
        return database.jdbc()
                .sql("SELECT count(*) FROM plan" + MATCHING)
                .params(text, text, text)
                .query(Long.class)
                .single();
    }

    public Optional<Plan> findById(UUID id) {
        return database
                .jdbc()
                .sql("SELECT " + COLUMNS + " FROM plan WHERE id = ?")
                .param(id)
                .query((row, n) -> plan(row))
                .list()
                .stream()
                .findFirst();
    }

    public boolean exists(UUID id) {
        return database.jdbc()
                        .sql("SELECT count(*) FROM plan WHERE id = ?")
                        .param(id)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    /** The plan's staff seat limit, when the plan exists. */
    public Optional<Integer> staffSeatsOf(UUID id) {
        return database
                .jdbc()
                .sql("SELECT staff_seats FROM plan WHERE id = ?")
                .param(id)
                .query(Integer.class)
                .list()
                .stream()
                .findFirst();
    }

    /**
     * Adds a plan.
     *
     * @throws org.springframework.dao.DuplicateKeyException when another plan has the key
     */
    public void add(UUID id, NewPlan plan, Instant now) {
        database.jdbc()
                .sql("""
                        INSERT INTO plan (id, key, name, staff_seats, branches, message_allowance, custom_domain,
                            dedicated_app, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        plan.key(),
                        plan.name(),
                        plan.staffSeats(),
                        plan.branches(),
                        plan.messageAllowance(),
                        plan.customDomain(),
                        plan.dedicatedApp(),
                        time(now),
                        time(now))
                .update();
    }

    private static Plan plan(ResultSet row) throws SQLException {
        return new Plan(
                row.getObject("id", UUID.class),
                row.getString("key"),
                row.getString("name"),
                row.getInt("staff_seats"),
                row.getInt("branches"),
                row.getInt("message_allowance"),
                row.getBoolean("custom_domain"),
                row.getBoolean("dedicated_app"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }
}
