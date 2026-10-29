package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(requiredProperties = {"challengeId", "secret", "otpauthUri", "expiresAt"})
public record OperatorEnrollment(UUID challengeId, String secret, String otpauthUri, Instant expiresAt) {}
