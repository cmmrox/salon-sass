package com.salonplatform.identity.application;

import java.time.Instant;
import java.util.UUID;

public record SessionView(UUID id, String deviceLabel, Instant createdAt, Instant expiresAt, boolean current) {}
