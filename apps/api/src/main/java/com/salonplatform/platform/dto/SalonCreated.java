package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(requiredProperties = {"id", "slug", "hostname", "planId", "createdAt"})
public record SalonCreated(UUID id, String slug, String hostname, UUID planId, Instant createdAt) {}
