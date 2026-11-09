package com.salonplatform.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** The professional who would take a free time: the one asked for, or the one "No preference" would assign now. */
@Schema(
        name = "AvailableMember",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id", "name"})
public record AvailableMemberResponse(UUID id, String name) {}
