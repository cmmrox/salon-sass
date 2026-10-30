package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(
        requiredProperties = {"state", "sessionId", "accessToken", "refreshToken", "accessExpiresAt", "refreshExpiresAt"
        })
public record OperatorTokens(
        String state,
        UUID sessionId,
        String accessToken,
        String refreshToken,
        Instant accessExpiresAt,
        Instant refreshExpiresAt) {}
