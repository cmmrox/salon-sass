package com.salonplatform.identity.dto;

import java.time.Instant;
import java.util.UUID;

public record Enrollment(UUID challengeId, String secret, String otpauthUri, Instant expiresAt) {}
