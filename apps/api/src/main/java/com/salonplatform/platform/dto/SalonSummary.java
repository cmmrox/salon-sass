package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

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
