package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(
        requiredProperties = {
            "id",
            "key",
            "name",
            "staffSeats",
            "branches",
            "messageAllowance",
            "customDomain",
            "dedicatedApp",
            "createdAt",
            "updatedAt"
        })
public record PlanDefinition(
        UUID id,
        String key,
        String name,
        int staffSeats,
        int branches,
        int messageAllowance,
        boolean customDomain,
        boolean dedicatedApp,
        Instant createdAt,
        Instant updatedAt) {}
