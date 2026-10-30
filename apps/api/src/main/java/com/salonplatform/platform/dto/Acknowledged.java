package com.salonplatform.platform.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(requiredProperties = {"eventId", "result"})
public record Acknowledged(
        String eventId,

        @Schema(allowableValues = {"paid", "failed", "already_paid", "unknown", "duplicate"})
        String result) {}
