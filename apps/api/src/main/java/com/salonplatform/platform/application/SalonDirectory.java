package com.salonplatform.platform.application;

import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.shared.infrastructure.PlatformDatabase;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The salons as the operator console lists and shows them (PLT-01; docs/architecture/operator-console.md):
 * the registry row, its provisioning profile and its plan, read with the platform role.
 */
@Service
public class SalonDirectory {

    @Schema(requiredProperties = {"id", "key", "name"})
    public record PlanSummary(UUID id, String key, String name) {}

    @Schema(requiredProperties = {"id", "name", "slug", "hostname", "status", "plan", "createdAt"})
    public record SalonSummary(
            UUID id,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            String slug,
            String hostname,

            @Schema(allowableValues = {"active", "suspended", "closed", "deleted"})
            String status,

            @Schema(
                    types = {"object", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            PlanSummary plan,

            Instant createdAt) {}

    @Schema(requiredProperties = {"items", "page", "size", "total"})
    public record SalonPage(List<SalonSummary> items, int page, int size, long total) {}

    @Schema(
            requiredProperties = {
                "id",
                "version",
                "name",
                "slug",
                "hostname",
                "status",
                "country",
                "currency",
                "timeZone",
                "defaultLanguage",
                "contactEmail",
                "contactPhone",
                "address",
                "plan",
                "ownerEmail",
                "createdAt",
                "updatedAt",
                "closedAt",
                "deleteAt",
                "deletedAt"
            })
    public record SalonDetail(
            UUID id,
            long version,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String name,

            String slug,
            String hostname,

            @Schema(allowableValues = {"active", "suspended", "closed", "deleted"})
            String status,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String country,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String currency,

            String timeZone,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String defaultLanguage,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String contactEmail,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String contactPhone,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String address,

            @Schema(
                    types = {"object", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            PlanSummary plan,

            @Schema(
                    types = {"string", "null"},
                    requiredMode = Schema.RequiredMode.REQUIRED)
            String ownerEmail,

            Instant createdAt,
            Instant updatedAt,

            @Schema(
                    types = {"string", "null"},
                    format = "date-time",
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "When the salon was closed (F03-S03)")
            Instant closedAt,

            @Schema(
                    types = {"string", "null"},
                    format = "date-time",
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "When a closed salon's data is deleted: 30 days after closure")
            Instant deleteAt,

            @Schema(
                    types = {"string", "null"},
                    format = "date-time",
                    requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "When a closed salon's data was deleted")
            Instant deletedAt) {}

    private final PlatformDatabase database;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final String platformDomain;

    public SalonDirectory(
            PlatformDatabase database,
            OperatorWork work,
            OperatorAccess access,
            @Value("${salon.platform-domain}") String platformDomain) {
        this.database = database;
        this.work = work;
        this.access = access;
        this.platformDomain = platformDomain.strip().toLowerCase(Locale.ROOT);
    }

    private String hostname(String slug) {
        return slug + "." + platformDomain;
    }

    private static final String SELECT = """
            SELECT t.id, t.version, t.name, t.slug, t.status, t.country, t.currency, t.time_zone, t.default_language,
                   t.contact_email, t.contact_phone, t.address, t.owner_email, t.created_at, t.updated_at,
                   t.closed_at, t.delete_at, t.deleted_at,
                   p.id AS plan_id, p.key AS plan_key, p.name AS plan_name
              FROM tenant t LEFT JOIN plan p ON p.id = t.plan_id
            """;

    private static PlanSummary plan(ResultSet row) throws SQLException {
        UUID id = row.getObject("plan_id", UUID.class);
        return id == null ? null : new PlanSummary(id, row.getString("plan_key"), row.getString("plan_name"));
    }

    private SalonDetail detail(ResultSet row) throws SQLException {
        return new SalonDetail(
                row.getObject("id", UUID.class),
                row.getLong("version"),
                row.getString("name"),
                row.getString("slug"),
                hostname(row.getString("slug")),
                row.getString("status"),
                row.getString("country"),
                row.getString("currency"),
                row.getString("time_zone"),
                row.getString("default_language"),
                row.getString("contact_email"),
                row.getString("contact_phone"),
                row.getString("address"),
                plan(row),
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

    public SalonPage list(Paging paging) {
        return work.run(() -> {
            access.require(OperatorPermission.SALON_READ);
            String filter = " WHERE (?::text IS NULL OR strpos(lower(coalesce(t.name, '')), lower(?::text)) > 0"
                    + " OR strpos(t.slug, lower(?::text)) > 0)";
            List<SalonSummary> items = database.jdbc()
                    .query(
                            SELECT + filter + " ORDER BY t.created_at DESC, t.id LIMIT ? OFFSET ?",
                            (row, n) -> new SalonSummary(
                                    row.getObject("id", UUID.class),
                                    row.getString("name"),
                                    row.getString("slug"),
                                    hostname(row.getString("slug")),
                                    row.getString("status"),
                                    plan(row),
                                    row.getTimestamp("created_at").toInstant()),
                            paging.q(),
                            paging.q(),
                            paging.q(),
                            paging.size(),
                            paging.offset());
            Long total = database.jdbc()
                    .queryForObject(
                            "SELECT count(*) FROM tenant t" + filter, Long.class, paging.q(), paging.q(), paging.q());
            return new SalonPage(items, paging.page(), paging.size(), total == null ? 0 : total);
        });
    }

    public SalonDetail get(UUID id) {
        return work.run(() -> {
            access.require(OperatorPermission.SALON_READ);
            return database.jdbc().query(SELECT + " WHERE t.id = ?", (row, n) -> detail(row), id).stream()
                    .findFirst()
                    .orElseThrow(NotFoundException::new);
        });
    }
}
