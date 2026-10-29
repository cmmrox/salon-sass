package com.salonplatform.identity.dto;

import java.time.Instant;
import java.util.UUID;

public record CapturedMessage(UUID challengeId, String channel, String code, int attempt, Instant deliveredAt) {}
