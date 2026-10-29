package com.salonplatform.platform.application;

import com.salonplatform.platform.domain.OperatorPermission;
import com.salonplatform.platform.infrastructure.SalonStore;
import com.salonplatform.platform.infrastructure.SalonStore.Salon;
import com.salonplatform.shared.web.NotFoundException;
import io.swagger.v3.oas.annotations.media.Schema;
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

    private final SalonStore salons;
    private final OperatorWork work;
    private final OperatorAccess access;
    private final String platformDomain;

    public SalonDirectory(
            SalonStore salons,
            OperatorWork work,
            OperatorAccess access,
            @Value("${salon.platform-domain}") String platformDomain) {
        this.salons = salons;
        this.work = work;
        this.access = access;
        this.platformDomain = platformDomain.strip().toLowerCase(Locale.ROOT);
    }

    private String hostname(String slug) {
        return slug + "." + platformDomain;
    }

    private static PlanSummary plan(SalonStore.PlanSummary plan) {
        return plan == null ? null : new PlanSummary(plan.id(), plan.key(), plan.name());
    }

    private SalonSummary summary(Salon salon) {
        return new SalonSummary(
                salon.id(),
                salon.name(),
                salon.slug(),
                hostname(salon.slug()),
                salon.status(),
                plan(salon.plan()),
                salon.createdAt());
    }

    private SalonDetail detail(Salon salon) {
        return new SalonDetail(
                salon.id(),
                salon.version(),
                salon.name(),
                salon.slug(),
                hostname(salon.slug()),
                salon.status(),
                salon.country(),
                salon.currency(),
                salon.timeZone(),
                salon.defaultLanguage(),
                salon.contactEmail(),
                salon.contactPhone(),
                salon.address(),
                plan(salon.plan()),
                salon.ownerEmail(),
                salon.createdAt(),
                salon.updatedAt(),
                salon.closedAt(),
                salon.deleteAt(),
                salon.deletedAt());
    }

    public SalonPage list(Paging paging) {
        return work.run(() -> {
            access.require(OperatorPermission.SALON_READ);
            List<SalonSummary> items = salons.page(paging.q(), paging.size(), paging.offset()).stream()
                    .map(this::summary)
                    .toList();
            long total = salons.count(paging.q());
            return new SalonPage(items, paging.page(), paging.size(), total);
        });
    }

    public SalonDetail get(UUID id) {
        return work.run(() -> {
            access.require(OperatorPermission.SALON_READ);
            return salons.findById(id).map(this::detail).orElseThrow(NotFoundException::new);
        });
    }
}
