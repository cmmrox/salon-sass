package com.salonplatform.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(requiredProperties = {"challengeId", "expiresAt"})
public record Challenge(UUID challengeId, Instant expiresAt) {}
