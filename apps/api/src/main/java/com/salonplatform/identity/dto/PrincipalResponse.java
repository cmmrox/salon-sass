package com.salonplatform.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Who the caller is, as {@code GET /api/v1/identity/me} answers. */
@Schema(
        name = "Principal",
        requiredProperties = {"id", "kind", "role", "permissions", "scope", "staffId", "mfaEnabled", "expiresAt"})
public record PrincipalResponse(
        UUID id,
        String kind,

        @Schema(
                types = {"string", "null"},
                requiredMode = RequiredMode.REQUIRED)
        String role,

        Set<String> permissions,
        String scope,

        @Schema(
                types = {"string", "null"},
                format = "uuid",
                requiredMode = RequiredMode.REQUIRED)
        UUID staffId,

        boolean mfaEnabled,

        @Schema(
                types = {"string", "null"},
                format = "date-time",
                description = "When a support session (kind support) ends; null for every other kind",
                requiredMode = RequiredMode.REQUIRED)
        Instant expiresAt) {}
