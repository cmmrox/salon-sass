package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(requiredProperties = {"state", "challengeId", "challengeToken", "expiresAt", "credentials"})
public record OperatorSignIn(
        String state,
        @Schema(format = "uuid") UUID challengeId,
        String challengeToken,
        Instant expiresAt,

        @Schema(
                types = {"object", "null"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        OperatorTokens credentials) {}
