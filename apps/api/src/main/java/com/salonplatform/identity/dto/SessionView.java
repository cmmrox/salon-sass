package com.salonplatform.identity.dto;

import java.time.Instant;
import java.util.UUID;

public record SessionView(UUID id, String deviceLabel, Instant createdAt, Instant expiresAt, boolean current) {}
