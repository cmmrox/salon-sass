package com.salonplatform.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditView(
        UUID id,
        Instant occurredAt,
        String actorKind,
        String actorId,
        String action,
        String subjectKind,
        String subjectId,

        @Schema(
                types = {"object", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        Map<String, Object> before,

        @Schema(
                types = {"object", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        Map<String, Object> after) {}
