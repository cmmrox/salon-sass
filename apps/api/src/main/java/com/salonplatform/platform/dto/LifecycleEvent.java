package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(requiredProperties = {"id", "kind", "reason", "actor", "at"})
public record LifecycleEvent(
        UUID id,

        @Schema(allowableValues = {"suspended", "reactivated", "closed", "deleted"})
        String kind,

        @Schema(
                types = {"string", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String reason,

        EventActor actor,
        Instant at) {}
