package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

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
