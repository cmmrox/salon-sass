package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(
        requiredProperties = {
            "id",
            "salon",
            "operator",
            "reason",
            "startedAt",
            "expiresAt",
            "endedAt",
            "endedBy",
            "state"
        })
public record SupportSessionView(
        UUID id,
        SupportSalonRef salon,
        SupportOperatorRef operator,
        String reason,
        Instant startedAt,
        Instant expiresAt,

        @Schema(
                types = {"string", "null"},
                format = "date-time",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant endedAt,

        @Schema(
                types = {"string", "null"},
                allowableValues = {"operator", "admin", "owner", "expiry"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String endedBy,

        @Schema(allowableValues = {"active", "ended"}) String state) {}
