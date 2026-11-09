package com.salonplatform.booking.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** The professional, with their name as it was when the hold was placed. */
@Schema(
        name = "BookingMember",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties = {"id", "name"})
public record BookingMemberResponse(UUID id, String name) {}
