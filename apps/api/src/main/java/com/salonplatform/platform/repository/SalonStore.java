package com.salonplatform.platform.repository;

import static com.salonplatform.platform.repository.PlatformTimes.time;

import com.salonplatform.shared.persistence.PlatformDatabase;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The salon registry ({@code tenant}) and its plan, as the operator console lists, shows and creates
 * salons, on the platform connection: named direct-SQL exception 1 of docs/engineering/backend.md.
 * {@code tenant} is the platform's registry, not a tenant table: it has no {@code tenant_id}, and the
 * tenant role cannot read it.
 */
@Component
public class SalonStore {

    /** A salon's registry row with its provisioning profile and its plan's summary (null without a plan). */
    public record Salon(
            UUID id,
            long version,
            String name,
            String slug,
            String status,
            String country,
            String currency,
            String timeZone,
            String defaultLanguage,
            String contactEmail,
            String contactPhone,
            String address,
            PlanSummary plan,
            String ownerEmail,
            Instant createdAt,
            Instant updatedAt,
            Instant closedAt,
            Instant deleteAt,
            Instant deletedAt) {}

    public record PlanSummary(UUID id, String key, String name) {}

    /** A salon to create: its slug and profile normalised, its first Owner's email and its plan. */
    public record NewSalon(
            String slug,
            String timeZone,
            String name,
            String country,
            String currency,
            String defaultLanguage,
            String contactEmail,
            String contactPhone,
            String address,
            String ownerEmail,
            UUID planId) {}

    private static final String SELECT = """
            SELECT t.id, t.version, t.name, t.slug, t.status, t.country, t.currency, t.time_zone, t.default_language,
                   t.contact_email, t.contact_phone, t.address, t.owner_email, t.created_at, t.updated_at,
                   t.closed_at, t.delete_at, t.deleted_at,
                   p.id AS plan_id, p.key AS plan_key, p.name AS plan_name
              FROM tenant t LEFT JOIN plan p ON p.id = t.plan_id
            """;

    /** A literal, case-insensitive match on the name or the slug, or every salon when the text is null. */
    private static final String MATCHING =
            " WHERE (?::text IS NULL OR strpos(lower(coalesce(t.name, '')), lower(?::text)) > 0"
                    + " OR strpos(t.slug, lower(?::text)) > 0)";

    private final PlatformDatabase database;

    SalonStore(PlatformDatabase database) {
        this.database = database;
    }

    /** One page of the salons matching the text, newest first. */
    public List<Salon> page(String text, int size, long offset) {
        return database.jdbc()
                .sql(SELECT + MATCHING + " ORDER BY t.created_at DESC, t.id LIMIT ? OFFSET ?")
                .params(text, text, text, size, offset)
                .query((row, n) -> salon(row))
                .list();
    }

    public long count(String text) {
        return database.jdbc()
                .sql("SELECT count(*) FROM tenant t" + MATCHING)
                .params(text, text, text)
                .query(Long.class)
                .single();
    }

    public Optional<Salon> findById(UUID id) {
        return database.jdbc().sql(SELECT + " WHERE t.id = ?").param(id).query((row, n) -> salon(row)).list().stream()
                .findFirst();
    }

    /** Adds an active salon to the registry. */
    public void create(UUID id, NewSalon salon, Instant now) {
        database.jdbc()
                .sql("""
                        INSERT INTO tenant (id, slug, status, time_zone, name, country, currency, default_language,
                            contact_email, contact_phone, address, owner_email, plan_id, created_at, updated_at)
                        VALUES (?, ?, 'active', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        salon.slug(),
                        salon.timeZone(),
                        salon.name(),
                        salon.country(),
                        salon.currency(),
                        salon.defaultLanguage(),
                        salon.contactEmail(),
                        salon.contactPhone(),
                        salon.address(),
                        salon.ownerEmail(),
                        salon.planId(),
                        time(now),
                        time(now))
                .update();
    }

    private static Salon salon(ResultSet row) throws SQLException {
        UUID planId = row.getObject("plan_id", UUID.class);
        return new Salon(
                row.getObject("id", UUID.class),
                row.getLong("version"),
                row.getString("name"),
                row.getString("slug"),
                row.getString("status"),
                row.getString("country"),
                row.getString("currency"),
                row.getString("time_zone"),
                row.getString("default_language"),
                row.getString("contact_email"),
                row.getString("contact_phone"),
                row.getString("address"),
                planId == null ? null : new PlanSummary(planId, row.getString("plan_key"), row.getString("plan_name")),
                row.getString("owner_email"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(),
                instant(row, "closed_at"),
                instant(row, "delete_at"),
                instant(row, "deleted_at"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    /**
     * Local fixtures only: an active salon with no subscription and none of the newer profile fields, as
     * the salons created before billing were.
     *
     * @throws org.springframework.dao.DuplicateKeyException when the slug is taken
     */
    public void createUnbilled(UUID id, String slug, Instant now) {
        database.jdbc().sql("""
                        INSERT INTO tenant (id, slug, status, time_zone, created_at, updated_at)
                        VALUES (?, ?, 'active', 'Asia/Colombo', ?, ?)
                        """).params(id, slug, time(now), time(now)).update();
    }
}
