package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(requiredProperties = {"accessToken", "sessionId", "issuedAt", "expiresAt"})
public record SupportCredential(String accessToken, UUID sessionId, Instant issuedAt, Instant expiresAt) {}
