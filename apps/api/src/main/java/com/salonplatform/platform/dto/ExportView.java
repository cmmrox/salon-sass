package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(requiredProperties = {"status", "createdAt", "readyAt", "deleteAt", "sizeBytes", "sha256"})
public record ExportView(
        @Schema(allowableValues = {"pending", "ready", "deleted"})
        String status,

        Instant createdAt,

        @Schema(
                types = {"string", "null"},
                format = "date-time",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant readyAt,

        Instant deleteAt,

        @Schema(
                types = {"integer", "null"},
                format = "int64",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long sizeBytes,

        @Schema(
                types = {"string", "null"},
                pattern = "^[0-9a-f]{64}$",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String sha256) {}
